use aiken/builtin
use aiken/collection/list
use aiken/crypto.{verify_ed25519_signature}
use aiken/primitive/bytearray
use cardano/assets.{PolicyId}
use cardano/transaction.{InlineDatum, Transaction}

/// Redeemer: the off-chain KYC proof produced by the trusted entity.
pub type KycProofRedeemer {
  /// 29-byte payload: user_pkh(28) || role(1)
  payload: ByteArray,
  /// 64-byte Ed25519 signature over `payload`
  signature: ByteArray,
}

/// Parameterised by the TEL policy id.
/// Locks funds that can only be spent by presenting a valid KYC proof.
///
/// The entity's verification key is extracted from the TEL node in the
/// reference inputs — it does not need to be provided in the redeemer.
///
/// Checks:
///   1. A TEL node exists in the reference inputs (carries a TEL token)
///   2. The Ed25519 signature over the payload is valid (using the node's vkey)
///   3. The user (first 28 bytes of payload) signed the transaction
validator kyc_lock(tel_policy_id: PolicyId) {
  spend(
    _datum: Option<Data>,
    redeemer: KycProofRedeemer,
    _utxo_ref: Data,
    self: Transaction,
  ) {
    let KycProofRedeemer { payload, signature } = redeemer

    // ── 1. Find TEL node in reference inputs & verify signature ─────────
    // Extract the entity vkey directly from the datum's first field using
    // builtin functions. This avoids `expect TENode = raw` which would
    // crash the entire script if any datum field has an unexpected encoding.
    let signed_by_tel_entity =
      list.any(
        self.reference_inputs,
        fn(ref_input) {
          let has_tel_token =
            ref_input.output.value
              |> assets.flatten
              |> list.any(fn(triple) { triple.1st == tel_policy_id })
          when has_tel_token is {
            True ->
              when ref_input.output.datum is {
                InlineDatum(raw) -> {
                  // TENode datum: Constr 0 [vkey, role, next]
                  // We only need the vkey (first field) for signature check.
                  let fields = builtin.snd_pair(builtin.un_constr_data(raw))
                  let vkey = builtin.un_b_data(builtin.head_list(fields))
                  verify_ed25519_signature(vkey, payload, signature)
                }
                _ -> False
              }
            False -> False
          }
        },
      )
    // ── 2. User signed the transaction ──────────────────────────────────
    let user_pkh = bytearray.slice(payload, 0, 27)
    let user_signed = list.has(self.extra_signatories, user_pkh)

    signed_by_tel_entity? && user_signed?
  }

  else(_) {
    fail
  }
}
