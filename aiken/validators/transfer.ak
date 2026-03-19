use aiken/collection/list
use cardano/assets.{PolicyId}
use cardano/transaction.{InlineDatum, Input, OutputReference, Transaction}
use kyc/types.{PubKeyHash, WLNode}
use kyc/utils.{has_policy}

/// Transfer validator — checked as a withdrawal script or reference script
/// on stablecoin UTxOs.
/// Parameterised by the whitelist policy id.
validator transfer(whitelist_policy_id: PolicyId) {
  spend(
    _datum: Option<Data>,
    sender_pkh: PubKeyHash,
    _utxo: OutputReference,
    self: Transaction,
  ) {
    let Transaction { reference_inputs, extra_signatories: sigs, .. } = self

    // 1. Tx signed by sender
    let must_signed = list.has(sigs, sender_pkh)

    // 2. Find sender's WL node in reference inputs
    expect Some(wl_node) =
      list.find_map(
        reference_inputs,
        fn(input) {
          let Input { output, .. } = input
          if has_policy(whitelist_policy_id, output.value) {
            when output.datum is {
              InlineDatum(raw) -> {
                expect node: WLNode = raw
                if node.pkh == sender_pkh {
                  Some(node)
                } else {
                  None
                }
              }
              _ -> None
            }
          } else {
            None
          }
        },
      )

    // 3. Node must be active
    let must_active = wl_node.active

    must_signed? && must_active?
  }

  else(_) {
    fail
  }
}
