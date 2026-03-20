use aiken/crypto.{Blake2b_224, Hash, VerificationKey}
use cardano/assets.{PolicyId, Value}
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

// ── KYC Whitelist ─────────────────────────────────────────────────────────────

/// Node datum for the KYC Whitelist.
pub type WLNode {
  /// The user's payment key hash (sort key)
  pkh: PubKeyHash,
  /// Whether this user is currently allowed to transact
  active: Bool,
  /// Trust level assigned to this user
  role: Role,
  next: NodeKey,
}

/// Redeemer for the KYC Whitelist validator
pub type WLRedeemer {
  /// Initialise the list.  Tx must be signed by a key present in the TEL.
  WLInit
  /// Tear down an empty list.
  WLDeinit
  /// Add user to the whitelist.
  /// Requirements:
  ///   1. Tx is signed by `new_pkh` (user self-registers)
  ///   2. `entity_endorsement` is the 64-byte Ed25519 signature of `new_pkh` bytes
  ///      produced off-chain by the trusted entity's private key.
  ///   3. `endorsing_entity_vkey` is the corresponding 32-byte raw Ed25519 public key.
  ///      Its blake2b_224 hash must match a TEL member visible in reference inputs.
  ///   4. `new_role` must be ≤ the endorsing entity's role in the TEL (enforced by `role_lte`).
  ///
  /// This allows the trusted entity to sign off-chain and hand the user a
  /// "credential" they can use to add themselves without any further co-operation.
  WLAdd {
    new_pkh: PubKeyHash,
    /// Role to assign to the new user
    new_role: Role,
    covering_node: WLNode,
    /// Raw 32-byte Ed25519 verification key of the trusted entity
    endorsing_entity_vkey: ByteArray,
    /// 64-byte Ed25519 signature of `new_pkh` bytes by the trusted entity
    entity_endorsement: ByteArray,
  }
  /// Set the active flag on an existing node.
  /// Only a trusted entity (present in TEL reference inputs) may do this.
  WLSetActive { target_pkh: PubKeyHash, new_active: Bool }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

/// Value + TE node pair (for internal passing)
pub type TENodePair {
  val: Value,
  node: TENode,
}

/// Value + WL node pair
pub type WLNodePair {
  val: Value,
  node: WLNode,
}

/// Config for the Trusted Entity List validator.
/// The `oref` is a UTxO reference consumed exactly once in TEInit,
/// guaranteeing that only one TEL can ever be created under this policy id.
pub type TEConfig {
  issuer_pkh: PubKeyHash,
  oref: OutputReference,
}

/// Config for the KYC Whitelist validator.
/// The `oref` is consumed exactly once in WLInit – prevents duplicate whitelists.
pub type WLConfig {
  te_policy_id: PolicyId,
  oref: OutputReference,
}
