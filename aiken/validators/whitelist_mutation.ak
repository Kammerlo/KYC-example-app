use aiken/collection/list
use aiken/crypto.{verify_ed25519_signature}
use cardano/assets.{PolicyId}
use cardano/transaction.{
  InlineDatum, Input, Output, OutputReference, Transaction,
}
use kyc/types.{
  Empty, Key, WLAdd, WLConfig, WLDeinit, WLInit, WLNode, WLRedeemer, WLSetActive,
}
use kyc/utils.{
  covers_key, signed_by_te, te_nodes_from_inputs, validate_mint,
  wl_nodes_from_inputs, wl_nodes_from_outputs, wl_prefix, wl_token_name,
}

/// ─────────────────────────────────────────────────────────────────────────────
/// KYC Whitelist validator
///
/// Parameterised by `cfg: WLConfig` which carries the TE list policy id.
///
/// Structure
///   • Head node  ➟  WLNode { pkh: head_pkh, active: True, next: … }
///                   token-name: "WHL"
///   • Each entry ➟  WLNode { pkh, active, next }
///                   token-name: "WHL" ++ pkh
///
/// Redeemers
/// ───────────
/// WLInit
///   • Tx signed by a key that appears in the TEL (reference inputs)
///   • Mints the head sentinel node
///
/// WLDeinit
///   • Tx signed by any TEL member
///   • Head node.next must be Empty
///
/// WLAdd { new_pkh, covering_node, endorsing_entity_vkey, entity_endorsement }
///   • Tx signed by `new_pkh`  (user self-registers)
///   • `entity_endorsement` is the 64-byte Ed25519 signature of `new_pkh` bytes
///     produced off-chain by a trusted entity — verified on-chain via
///     `verify_ed25519_signature`.  No TE co-signature required on the tx.
///   • `endorsing_entity_vkey` must equal the `vkey` field of a TEL node in
///     reference inputs (the raw 32-byte Ed25519 public key is stored on-chain)
///   • Covering node must straddle `new_pkh` in sort order
///   • New node output has active = True
///
/// WLSetActive { target_pkh, new_active }
///   • Spend path: spends the target node UTxO
///   • Tx signed by any TEL member (ref inputs carry the TEL node)
///   • All other fields of the datum are unchanged
/// ─────────────────────────────────────────────────────────────────────────────
validator whitelist(cfg: WLConfig) {
  // ── Mint/Burn endpoint ─────────────────────────────────────────────────────
  mint(redeemer: WLRedeemer, policy_id: PolicyId, self: Transaction) {
    let Transaction {
      inputs,
      outputs,
      mint,
      extra_signatories: sigs,
      reference_inputs,
      ..
    } = self

    let node_ins = wl_nodes_from_inputs(inputs, policy_id)
    let node_outs = wl_nodes_from_outputs(outputs, policy_id)

    // TE nodes provided as reference inputs — used to verify trusted entities
    let te_refs = te_nodes_from_inputs(reference_inputs, cfg.te_policy_id)

    when redeemer is {
      // ── WLInit ─────────────────────────────────────────────────────────────
      // Requires the bootstrap UTxO (`cfg.oref`) to be consumed so only one
      // whitelist can ever exist under this policy id.
      WLInit -> {
        let must_oref_consumed =
          list.any(inputs, fn(i: Input) { i.output_reference == cfg.oref })
        let must_te_signed = signed_by_te(sigs, te_refs)
        let must_no_existing = list.is_empty(node_ins)
        let must_one_out = list.length(node_outs) == 1
        let must_mint_head = validate_mint(mint, policy_id, wl_prefix, 1)

        must_oref_consumed? && must_te_signed? && must_no_existing? && must_one_out? && must_mint_head?
      }

      // ── WLDeinit ───────────────────────────────────────────────────────────
      WLDeinit -> {
        let must_te_signed = signed_by_te(sigs, te_refs)
        expect [head_in] = node_ins
        let must_empty = head_in.node.next == Empty
        let must_no_outs = list.is_empty(node_outs)
        let must_burn_head = validate_mint(mint, policy_id, wl_prefix, -1)

        must_te_signed? && must_empty? && must_no_outs? && must_burn_head?
      }

      // ── WLAdd ──────────────────────────────────────────────────────────────
      WLAdd {
        new_pkh,
        covering_node,
        endorsing_entity_vkey,
        entity_endorsement,
      } -> {
        // 1. User signs their own insertion (self-registration)
        let must_user_signed = list.has(sigs, new_pkh)

        // 2. Off-chain endorsement: the trusted entity signed `new_pkh` bytes with
        //    their Ed25519 private key.  The user proves the TE authorised them by
        //    supplying the 64-byte signature in the redeemer — no TE co-signature
        //    needed at submission time.
        let must_endorsement_valid =
          verify_ed25519_signature(
            endorsing_entity_vkey,
            new_pkh,
            entity_endorsement,
          )

        // 3. The endorsing entity's vkey must be stored in a TEL node visible in
        //    reference inputs — the raw 32-byte key is stored on-chain so any user
        //    can look it up without needing the entity to reveal it out-of-band
        let must_entity_in_tel =
          list.any(te_refs, fn(p) { p.node.vkey == endorsing_entity_vkey })

        // 4. Covering node straddles new_pkh
        // Head sentinel has an empty pkh byte-array — treat as Empty NodeKey
        let covering_key =
          if covering_node.pkh == #"" {
            Empty
          } else {
            Key { key: covering_node.pkh }
          }
        let must_covers = covers_key(covering_key, covering_node.next, new_pkh)

        // 5. Predecessor output: covering_node with next → new_pkh
        let pred_out_expected =
          WLNode { ..covering_node, next: Key { key: new_pkh } }
        let must_pred_updated =
          list.any(node_outs, fn(p) { p.node == pred_out_expected })

        // 6. New node output must exist with correct fields and active = True
        let new_node_expected =
          WLNode { pkh: new_pkh, active: True, next: covering_node.next }
        let must_new_node =
          list.any(node_outs, fn(p) { p.node == new_node_expected })

        // 7. Mint exactly one WL token for new_pkh
        let must_mint =
          validate_mint(mint, policy_id, wl_token_name(new_pkh), 1)

        must_user_signed? && must_endorsement_valid? && must_entity_in_tel? && must_covers? && must_pred_updated? && must_new_node? && must_mint?
      }

      // WLSetActive is handled in the spend endpoint below
      WLSetActive { .. } -> fail @"WLSetActive must use spend endpoint"
    }
  }

  // ── Spend endpoint — used for WLSetActive, WLAdd (covering node), and WLDeinit ─
  spend(
    datum_opt: Option<WLNode>,
    redeemer: WLRedeemer,
    _utxo: OutputReference,
    self: Transaction,
  ) {
    let Transaction { outputs, extra_signatories: sigs, reference_inputs, .. } =
      self

    when redeemer is {
      WLSetActive { target_pkh, new_active } -> {
        // 1. Spend datum must exist and belong to target_pkh
        expect Some(current_node) = datum_opt
        expect current_node.pkh == target_pkh

        // 2. Tx must be signed by a trusted entity in the TEL
        let te_refs = te_nodes_from_inputs(reference_inputs, cfg.te_policy_id)
        let must_te_signed = signed_by_te(sigs, te_refs)

        // 3. Exactly one output continues the node with only `active` changed;
        //    all other fields (pkh, next) are identical
        let expected_out = WLNode { ..current_node, active: new_active }
        let must_correct_out =
          list.any(
            outputs,
            fn(out) {
              when out.datum is {
                InlineDatum(raw) -> {
                  expect node: WLNode = raw
                  node == expected_out
                }
                _ -> False
              }
            },
          )

        must_te_signed? && must_correct_out?
      }

      // Covering node is spent during WLAdd.  Allow it if the new user signs.
      WLAdd { new_pkh, .. } -> list.has(sigs, new_pkh)

      // Head node is spent during WLDeinit.  Allow it if a trusted entity signs.
      WLDeinit -> {
        let te_refs = te_nodes_from_inputs(reference_inputs, cfg.te_policy_id)
        signed_by_te(sigs, te_refs)
      }

      _ -> fail @"Only WLSetActive, WLAdd, WLDeinit are valid in spend"
    }
  }

  else(_) {
    fail
  }
}