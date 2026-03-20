use aiken/collection/list
use aiken/crypto.{blake2b_224}
use cardano/assets.{PolicyId}
use cardano/transaction.{Input, OutputReference, Transaction}
use kyc/types.{
  Empty, Key, PubKeyHash, TEAdd, TEDeinit, TEInit, TENode, TERedeemer, TERemove,
  VLei,
}
use kyc/utils.{
  covers_key, te_nodes_from_inputs, te_nodes_from_outputs, te_prefix,
  te_token_name, validate_mint,
}

/// Trusted Entity List validator
/// Parameterised by `cfg: TEConfig` which bundles:
///   • `issuer_pkh` – the protocol deployer who controls the list
///   • `oref`       – a UTxO reference consumed exclusively in TEInit,
///                    ensuring only one TEL can ever be created under this policy.
validator trusted_entity(issuer_pkh: PubKeyHash, oref: OutputReference) {
  mint(redeemer: TERedeemer, policy_id: PolicyId, self: Transaction) {
    let Transaction { inputs, outputs, mint, extra_signatories: sigs, .. } =
      self

    // Only the bootstrap issuer may mutate the TE list
    let must_issuer_signed = list.has(sigs, issuer_pkh)

    let node_ins = te_nodes_from_inputs(inputs, policy_id)
    let node_outs = te_nodes_from_outputs(outputs, policy_id)

    when redeemer is {
      // ── Init ───────────────────────────────────────────────────────────────
      // Mint the sentinel head node: TENode { vkey: issuerVkey, next: Empty }
      // Requires the bootstrap UTxO (`oref`) to be consumed so only one TEL
      // can ever exist under this policy id.
      TEInit -> {
        let must_oref_consumed =
          list.any(inputs, fn(i: Input) { i.output_reference == oref })
        let must_no_existing = list.is_empty(node_ins)
        let must_one_out = list.length(node_outs) == 1

        expect [head_out] = node_outs
        let head_datum_ok =
          blake2b_224(head_out.node.vkey) == issuer_pkh && head_out.node.role == VLei && head_out.node.next == Empty

        let must_mint_head = validate_mint(mint, policy_id, te_prefix, 1)

        must_issuer_signed? && must_oref_consumed? && must_no_existing? && must_one_out? && head_datum_ok? && must_mint_head?
      }

      // ── Deinit ─────────────────────────────────────────────────────────────
      // Burn the head node; list must already be empty (next == Empty)
      TEDeinit -> {
        expect [head_in] = node_ins
        let must_empty_list = head_in.node.next == Empty
        let must_no_outs = list.is_empty(node_outs)
        let must_burn_head = validate_mint(mint, policy_id, te_prefix, -1)

        must_issuer_signed? && must_empty_list? && must_no_outs? && must_burn_head?
      }

      // ── Add ────────────────────────────────────────────────────────────────
      // Insert `new_vkey` (sorted by its derived pkh) after `covering_node`.
      //
      // Before:  … → covering_node → covering_node.next → …
      // After:   … → covering_node[next:=new_pkh] → new_node → covering_node.next → …
      TEAdd { new_vkey, new_role, covering_node } -> {
        // Derive pkh from vkey for sorting / minting
        let new_pkh = blake2b_224(new_vkey)
        let covering_pkh = blake2b_224(covering_node.vkey)

        // The issuer's head node acts as an absolute-minimum sentinel (like Empty),
        // allowing any entity to be inserted after it regardless of byte ordering.
        let covering_key =
          if covering_pkh == issuer_pkh {
            Empty
          } else {
            Key { key: covering_pkh }
          }

        // Covering node must straddle new_pkh
        let must_covers = covers_key(covering_key, covering_node.next, new_pkh)

        // Predecessor output: covering_node with next updated to new_pkh
        let pred_expected =
          TENode { ..covering_node, next: Key { key: new_pkh } }
        let must_pred_updated =
          list.any(node_outs, fn(p) { p.node == pred_expected })

        // New node output
        let new_node_expected =
          TENode { vkey: new_vkey, role: new_role, next: covering_node.next }
        let must_new_node =
          list.any(node_outs, fn(p) { p.node == new_node_expected })

        // Mint exactly one token for new_pkh
        let must_mint_node =
          validate_mint(mint, policy_id, te_token_name(new_pkh), 1)

        must_issuer_signed? && must_covers? && must_pred_updated? && must_new_node? && must_mint_node?
      }

      // ── Remove ─────────────────────────────────────────────────────────────
      // Remove the entity identified by `remove_vkey`; restore covering_node.next.
      TERemove { remove_vkey, covering_node } -> {
        let remove_pkh = blake2b_224(remove_vkey)

        // The covering_node must currently point to remove_pkh
        let covering_currently_points =
          covering_node.next == Key { key: remove_pkh }
        expect covering_currently_points

        // Predecessor restored: covering_node with next = removed_node.next
        expect Some(removed_pair) =
          list.find(node_ins, fn(p) { blake2b_224(p.node.vkey) == remove_pkh })
        let pred_restored =
          TENode { ..covering_node, next: removed_pair.node.next }
        let must_pred_restored =
          list.any(node_outs, fn(p) { p.node == pred_restored })

        // Burn the removed node's token
        let must_burn_node =
          validate_mint(mint, policy_id, te_token_name(remove_pkh), -1)

        must_issuer_signed? && must_pred_restored? && must_burn_node?
      }
    }
  }

  // Spending a TEL node is permitted iff the issuer signs.
  // Full mutation correctness is enforced by the accompanying mint handler.
  spend(
    _datum: Option<Data>,
    _redeemer: Data,
    _utxo: OutputReference,
    self: Transaction,
  ) {
    list.has(self.extra_signatories, issuer_pkh)
  }

  else(_) {
    fail
  }
}
