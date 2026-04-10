use aiken/collection/dict
use aiken/collection/list
use aiken/crypto.{blake2b_224}
use aiken/primitive/bytearray
use cardano/assets.{AssetName, PolicyId, Value, flatten, tokens}
use cardano/transaction.{InlineDatum, Input, Output}
use kyc/types.{Empty, Key, NodeKey, PubKeyHash, TENode, TENodePair}

// ── Token naming ──────────────────────────────────────────────────────────────

/// Prefix for all TE list tokens (head node gets exactly this name)
pub const te_prefix: ByteArray = "TEL"

/// Token name for a TE node whose key is `pkh`
pub fn te_token_name(pkh: PubKeyHash) -> AssetName {
  bytearray.concat(te_prefix, pkh)
}

// ── Mint validation ───────────────────────────────────────────────────────────

/// Assert that the mint field contains exactly one entry:
///   policy `cs`, token `tn`, amount `amt`  (use -1 for burns)
pub fn validate_mint(
  mint: Value,
  cs: ByteArray,
  tn: ByteArray,
  amt: Int,
) -> Bool {
  tokens(mint, cs) == dict.from_pairs([Pair(tn, amt)])
}

// ── Value membership ──────────────────────────────────────────────────────────

/// True if `value` holds any token under policy `cs`
pub fn has_policy(cs: PolicyId, value: Value) -> Bool {
  value
    |> flatten
    |> list.any(
        fn(triple) {
          let (p, _, _) = triple
          p == cs
        },
      )
}

// ── TE node extraction ────────────────────────────────────────────────────────

/// Collect all TE node datums from a list of inputs whose value holds `cs`
pub fn te_nodes_from_inputs(
  inputs: List<Input>,
  cs: PolicyId,
) -> List<TENodePair> {
  list.filter_map(
    inputs,
    fn(input) {
      let Input { output, .. } = input
      if has_policy(cs, output.value) {
        when output.datum is {
          InlineDatum(raw) -> {
            expect node: TENode = raw
            Some(TENodePair { val: output.value, node })
          }
          _ -> None
        }
      } else {
        None
      }
    },
  )
}

/// Collect all TE node datums from a list of outputs whose value holds `cs`
pub fn te_nodes_from_outputs(
  outputs: List<Output>,
  cs: PolicyId,
) -> List<TENodePair> {
  list.filter_map(
    outputs,
    fn(output) {
      if has_policy(cs, output.value) {
        when output.datum is {
          InlineDatum(raw) -> {
            expect node: TENode = raw
            Some(TENodePair { val: output.value, node })
          }
          _ -> None
        }
      } else {
        None
      }
    },
  )
}

// ── Sorted-list helpers ───────────────────────────────────────────────────────

/// True if `node` is the correct predecessor for `insert_pkh`:
///   node.pkh < insert_pkh  AND  (node.next == Empty OR node.next > insert_pkh)
pub fn covers_key(
  node_pkh: NodeKey,
  node_next: NodeKey,
  insert_pkh: PubKeyHash,
) -> Bool {
  let left_ok =
    when node_pkh is {
      Empty -> True
      Key { key } -> bytearray.compare(key, insert_pkh) == Less
    }
  let right_ok =
    when node_next is {
      Empty -> True
      Key { key } -> bytearray.compare(insert_pkh, key) == Less
    }
  left_ok && right_ok
}

// ── Trusted-entity lookup ─────────────────────────────────────────────────────

/// True if at least one signer in `sigs` is a TEL member visible in `te_ref_nodes`
pub fn signed_by_te(
  sigs: List<PubKeyHash>,
  te_ref_nodes: List<TENodePair>,
) -> Bool {
  list.any(
    te_ref_nodes,
    fn(pair) { list.has(sigs, blake2b_224(pair.node.vkey)) },
  )
}
