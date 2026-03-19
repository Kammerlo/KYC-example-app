# KYC Whitelist Scripts

JBang scripts that demonstrate the full on-chain KYC workflow against a local
[yaci-devkit](https://github.com/bloxbean/yaci-devkit) devnet (or any Cardano
network with a Blockfrost-compatible API).

---

## Architecture overview

Two on-chain sorted linked lists are maintained as UTxOs at Plutus V3 script
addresses:

| List | Token prefix | Purpose |
|------|-------------|---------|
| **Trusted Entity List (TEL)** | `TEL` | Accounts authorised to approve KYC and manage whitelist nodes |
| **KYC Whitelist (WL)** | `WHL` | Users who have passed KYC and are allowed to transact |

Each node in both lists is a UTxO that holds exactly one NFT (policy + prefixed
token name) and an inline datum.  Nodes are sorted by public-key hash so the
validator can enforce correct insertion order with O(1) predecessor proofs.

### Oneshot minting policy

Both validators use the **oneshot** pattern: each is parameterised by a specific
UTxO reference (`OutputReference`) in addition to its admin key or TEL policy
id.  The `TEInit` / `WLInit` redeemer checks that this UTxO is **consumed** in
the initialisation transaction.  Because a UTxO can only be spent once, it
is impossible for a second TEL or WL to be created under the same policy id —
even with identical admin keys.

The bootstrap UTxO references are written to `scripts/.env` automatically on
the first successful run (`TE_BOOT_TXHASH` / `TE_BOOT_INDEX` by
`InitTrustedEntityList`, and `WL_BOOT_TXHASH` / `WL_BOOT_INDEX` by
`AddUserToWhitelist`).  Subsequent runs read these variables to reconstruct
the correct policy id for idempotency checks.

The endorsement model is deliberately **off-chain** for the user addition step:
the trusted entity signs the user's public-key hash bytes with their Ed25519
private key and hands the 64-byte signature to the user.  The user then submits
the whitelist-addition transaction alone — the entity does not need to be online
at submission time.

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| [JBang](https://www.jbang.dev) | any recent |
| Docker (for local devnet) | any recent |

All Cardano library dependencies are declared as `//DEPS` headers inside each
script and are resolved automatically by JBang on first run.

---

## Quick start

```bash
# 1. Start the local devnet (Cardano node + indexer in Docker)
docker compose up -d

# 2. Copy the example config and edit it (or run without editing –
#    ENTITY_MNEMONIC and USER_MNEMONIC will be generated automatically)
cp scripts/.env.example scripts/.env
$EDITOR scripts/.env          # fill in your own mnemonics if desired

# 3. Run the scripts in order (from the project root)
jbang scripts/InitTrustedEntityList.java
jbang scripts/AddUserToWhitelist.java
jbang scripts/ToggleActiveFlag.java
```

---

## Configuration — `scripts/.env`

All scripts read `scripts/.env` (falling back to environment variables).

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `BLOCKFROST_URL` | no | `http://localhost:8081/api/v1/` | Blockfrost-compatible API endpoint |
| `BLOCKFROST_PROJECT_ID` | no | `localnet` | API key (unused for yaci-devkit) |
| `ISSUER_MNEMONIC` | **yes** | – | 24-word mnemonic for the protocol deployer. Owns and administers the TEL. |
| `ENTITY_MNEMONIC` | no | auto-generated | 24-word mnemonic for the trusted entity. Auto-saved to `.env` when absent. |
| `USER_MNEMONIC` | no | auto-generated | 24-word mnemonic for the end user. Auto-saved to `.env` when absent. |
| `FAUCET_MNEMONIC` | no | – | Funds other wallets on devnet. Omit for public networks. |
| `TE_BOOT_TXHASH` | auto | – | Tx hash of the UTxO consumed in TELInit (oneshot guarantee). Written by `InitTrustedEntityList` on first run. |
| `TE_BOOT_INDEX` | auto | – | Output index of the TE bootstrap UTxO. |
| `WL_BOOT_TXHASH` | auto | – | Tx hash of the UTxO consumed in WLInit. Written by `AddUserToWhitelist` on first run. |
| `WL_BOOT_INDEX` | auto | – | Output index of the WL bootstrap UTxO. |

The four `*_BOOT_*` variables are written automatically; **do not change them
after the contracts have been deployed** — they are baked into the on-chain
policy ids.

---

## Scripts

### Step 1 — `InitTrustedEntityList.java`

**Initialises the Trusted Entity List and registers the first trusted entity.**

```bash
jbang scripts/InitTrustedEntityList.java
```

1. (Devnet only) Funds the issuer and entity wallets from the faucet.
2. Loads the `trusted_entity` Plutus validator from `aiken/plutus.json` and
   applies the issuer's public-key hash as its compile-time parameter.
3. **TEL Init** — mints the TEL head sentinel node at the script address.  The
   datum records `{ pkh: issuerPkh, next: Empty }`.
4. **TEL Add** — issuer adds the trusted entity to the list.  A new node
   `{ pkh: entityPkh, next: Empty }` is minted and the head node's `next`
   pointer is updated.
5. Prints every node currently at the TEL script address.

Both operations are **idempotent**: re-running the script skips steps that have
already been executed.

---

### Step 2 — `AddUserToWhitelist.java`

**Entity endorses a user off-chain; user self-registers in the KYC Whitelist.**

```bash
jbang scripts/AddUserToWhitelist.java
```

Requires Step 1 to be complete first.

1. (Devnet only) Funds the user and entity wallets from the faucet.
2. Loads both the TEL and WL validators; parameterises WL with the current TEL
   policy ID.
3. **WL Init** — entity mints the WL head sentinel node (idempotent).
4. **Off-chain endorsement** — the entity signs the user's 28-byte
   public-key hash with their Ed25519 private key, producing a 64-byte
   signature.  This signature and the entity's raw 32-byte verification key are
   what the on-chain validator uses to approve the user.
5. **WL Add** — the *user* submits the transaction carrying their own signature
   plus the entity's verification key and endorsement.  The validator checks:
   - `blake2b_224(entity_vkey)` matches a TEL node in the reference inputs,
   - the endorsement is a valid Ed25519 signature of the user's PKH, and
   - the transaction is signed by the user (self-registration).
6. Prints the user's whitelist node (`active: true`, `expiry: <+1 year>`).

---

### Step 3 — `ToggleActiveFlag.java`

**A trusted entity enables or disables a user in the KYC Whitelist.**

```bash
jbang scripts/ToggleActiveFlag.java            # toggles current value
jbang scripts/ToggleActiveFlag.java --active=false   # explicitly deactivate
jbang scripts/ToggleActiveFlag.java --active=true    # explicitly reactivate
```

Requires Step 2 to be complete first.

1. Loads the WL validator; locates the user's current whitelist node.
2. **WL SetActive** — entity submits a spend transaction that updates only
   the `active` boolean in the node's datum.  The entity must be present in
   the TEL reference inputs; all other node fields remain unchanged.
3. Prints the node before and after the update.

---

### `KycE2ETest.java` — full end-to-end test

**Runs the complete lifecycle on a fresh devnet using randomly generated
accounts.**

```bash
jbang scripts/KycE2ETest.java
```

Executes all eight steps in sequence against a clean state:

| Step | Operation |
|------|-----------|
| 1 | Fund issuer / entity / user from the yaci-devkit faucet |
| 2 | TEL Init — create the Trusted Entity List |
| 3 | TEL Add — issuer adds entity |
| 4 | WL Init — entity creates the KYC Whitelist |
| 5 | WL Add — user self-registers with entity endorsement (`active=true`) |
| 6 | WL SetActive=false — entity deactivates the user |
| 7 | WL SetActive=true — entity reactivates the user |
| 8 | TEL Remove — issuer removes entity |
| 9 | TEL Deinit — issuer tears down the now-empty TEL |

Exits with `ALL TESTS PASSED` on success or a Java exception on the first
failure.

---

## On-chain contracts

The Plutus V3 validators live in [`aiken/`](../aiken/) and are compiled with
[Aiken](https://aiken-lang.org):

```bash
cd aiken && aiken build && make copy
```

`make copy` writes the compiled `plutus.json` blueprint into
`src/main/resources/` so the Spring Boot backend and these scripts both pick up
the same binary.
