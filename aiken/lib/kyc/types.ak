use aiken/crypto.{Blake2b_224, Hash, VerificationKey}
use cardano/assets.{Value}
use cardano/transaction.{OutputReference}

/// Cardano payment key hash
pub type PubKeyHash =
  Hash<Blake2b_224, VerificationKey>

/// Linked-list next pointer
pub type NodeKey {
  Key { key: PubKeyHash }
  Empty
}

/// Trust / privilege level.
/// Serialises as a Plutus Constr with no fields:
///   User          → Constr 0 []
///   Institutional → Constr 1 []
///   VLei          → Constr 2 []
pub type Role {
  User
  Institutional
  VLei
}

/// True if role `a` is at most role `b` (User ≤ Institutional ≤ VLei).
pub fn role_lte(a: Role, b: Role) -> Bool {
  when (a, b) is {
    (User, _) -> True
    (Institutional, Institutional) -> True
    (Institutional, VLei) -> True
    (VLei, VLei) -> True
    _ -> False
  }
}

// ── Trusted Entity List ────────────────────────────────────────────────────────

/// Node datum for the Trusted Entity List.
pub type TENode {
  /// Raw 32-byte Ed25519 verification key (sort key is blake2b_224(vkey))
  vkey: ByteArray,
  /// Trust level of this entity
  role: Role,
  next: NodeKey,
}

/// Redeemer for the Trusted Entity validator
pub type TERedeemer {
  /// Initialise the list (mint head node).  Tx must be signed by the bootstrap issuer.
  TEInit
  /// Tear down an empty list.
  TEDeinit
  /// Add a new trusted entity.  Covering node must be provided for insertion order.
  TEAdd { new_vkey: ByteArray, new_role: Role, covering_node: TENode }
  /// Remove a trusted entity.  Covering node must be provided.
  TERemove { remove_vkey: ByteArray, covering_node: TENode }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

/// Value + TE node pair (for internal passing)
pub type TENodePair {
  val: Value,
  node: TENode,
}

/// Config for the Trusted Entity List validator.
/// The `oref` is a UTxO reference consumed exactly once in TEInit,
/// guaranteeing that only one TEL can ever be created under this policy id.
pub type TEConfig {
  issuer_pkh: PubKeyHash,
  oref: OutputReference,
}

// ── Off-chain KYC Proof ──────────────────────────────────────────────────────
//
// After KYC credential verification, the trusted entity signs a 37-byte payload:
//
//   user_pkh (28 bytes) || role (1 byte) || valid_until (8 bytes, big-endian POSIX ms)
//
// Any on-chain validator can verify this proof using:
//   verify_ed25519_signature(entity_vkey, payload, signature)
// where `entity_vkey` is the 32-byte Ed25519 key stored in a TEL node.
//
// The `valid_until` field uses Cardano's POSIXTime (milliseconds since epoch)
// and can be checked against the transaction validity range.
