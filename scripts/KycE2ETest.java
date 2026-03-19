///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-quicktx:0.7.1
//DEPS com.bloxbean.cardano:aiken-java-binding:0.1.0
//DEPS org.slf4j:slf4j-simple:2.0.9

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end integration test for the KYC Whitelist contracts on yaci-devkit.
 *
 * Run with:
 *   cd /path/to/project && jbang scripts/KycE2ETest.java
 *
 * Requires yaci-devkit running:  docker compose up -d
 *
 * Test flow:
 *   1. Fund issuer / entity / user from the pre-funded faucet mnemonic
 *   2. TEL Init   - issuer creates Trusted Entity List head node
 *   3. TEL Add    - issuer adds entity to the TEL
 *   4. WL  Init   - entity initialises KYC Whitelist
 *   5. WL  Add    - user self-signs + entity endorses -> user active=true
 *   6. WL  SetActive=false - entity disables user
 *   7. WL  SetActive=true  - entity re-enables user
 *   8. TEL Remove - issuer removes entity
 *   9. TEL Deinit - issuer tears down empty TEL
 */
public class KycE2ETest {

    /* ── yaci-devkit ──────────────────────────────────────────────────────── */
    static final String YACI_API = "http://localhost:8081/api/v1/";
    static final String YACI_KEY = "no-key";

    /* ── Faucet wallet (the only pre-funded wallet in yaci-devkit) ─────────── */
    static final String FAUCET_MNEMONIC =
        "test test test test test test test test test test test test " +
        "test test test test test test test test test test test sauce";

    /* ── Token-name prefixes: "TEL" = 54454c, "WHL" = 57484c ─────────────── */
    static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    static final String WL_PREFIX_HEX = HexUtil.encodeHexString("WHL".getBytes());

    /* ── Singletons (populated in setup methods) ──────────────────────────── */
    static BackendService backend;
    static QuickTxBuilder builder;
    static Account faucet, issuer, entity, user;

    static PlutusV3Script teScript, wlScript;
    static String tePolicyId, wlPolicyId;
    static String teScriptAddress, wlScriptAddress;
    static String issuerPkh, entityPkh, userPkh;
    static String issuerVkeyHex, entityVkeyHex;
    // Oneshot bootstrap UTxO references – queried after funding, used in telInit/wlInit.
    static String teBootTxHash;
    static int    teBootIndex;
    static String wlBootTxHash;
    static int    wlBootIndex;

    /* ════════════════════════════════════════════════════════════════════════ */
    public static void main(String[] args) throws Exception {
        banner("KYC E2E Test - yaci-devkit");
        setupBackend();
        setupWallets();
        fundWallets();
        buildScripts();

        step("1", "TEL Init  - issuer creates Trusted Entity List");
        telInit();

        step("2", "TEL Add   - issuer adds entity to TEL");
        telAdd();

        step("3", "WL Init   - entity initialises KYC Whitelist");
        wlInit();

        step("4", "WL Add    - user + entity co-sign => user active=true");
        wlAdd();

        step("5", "WL SetActive=false - entity disables user");
        wlSetActive(false);

        step("6", "WL SetActive=true  - entity re-enables user");
        wlSetActive(true);

        step("7", "TEL Remove - issuer removes entity");
        telRemove();

        step("8", "TEL Deinit - issuer tears down TEL");
        telDeinit();

        banner("ALL TESTS PASSED");
    }

    /* ── Setup ──────────────────────────────────────────────────────────────── */

    static void setupBackend() {
        log("Connecting to yaci-devkit at " + YACI_API);
        backend = new BFBackendService(YACI_API, YACI_KEY);
        builder = new QuickTxBuilder(backend);
        log("Backend ready");
    }

    static void setupWallets() {
        faucet = Account.createFromMnemonic(Networks.testnet(), FAUCET_MNEMONIC);

        // Generate fresh random accounts each run; funded from faucet below.
        issuer = new Account(Networks.testnet());
        entity = new Account(Networks.testnet());
        user   = new Account(Networks.testnet());

        issuerPkh = HexUtil.encodeHexString(issuer.hdKeyPair().getPublicKey().getKeyHash());
        entityPkh = HexUtil.encodeHexString(entity.hdKeyPair().getPublicKey().getKeyHash());
        userPkh   = HexUtil.encodeHexString(user.hdKeyPair().getPublicKey().getKeyHash());
        issuerVkeyHex = HexUtil.encodeHexString(issuer.publicKeyBytes());
        entityVkeyHex = HexUtil.encodeHexString(entity.publicKeyBytes());

        log("Faucet  addr : " + faucet.baseAddress());
        log("Issuer  addr : " + issuer.baseAddress());
        log("Issuer  pkh  : " + issuerPkh);
        log("Issuer  vkey : " + issuerVkeyHex);
        log("Issuer  words: " + issuer.mnemonic());
        log("Entity  addr : " + entity.baseAddress());
        log("Entity  pkh  : " + entityPkh);
        log("Entity  vkey : " + entityVkeyHex);
        log("Entity  words: " + entity.mnemonic());
        log("User    addr : " + user.baseAddress());
        log("User    pkh  : " + userPkh);
        log("User    words: " + user.mnemonic());
    }

    static void fundWallets() throws Exception {
        log("Funding issuer / entity / user (10 ADA each) from faucet...");
        BigInteger ten = ada(10);

        // payToAddress(String, Amount) is available in AbstractTx 0.7.1
        Tx tx = new Tx()
            .payToAddress(issuer.baseAddress(), Amount.lovelace(ten))
            .payToAddress(entity.baseAddress(), Amount.lovelace(ten))
            .payToAddress(user.baseAddress(),   Amount.lovelace(ten))
            .from(faucet.baseAddress());

        var result = builder.compose(tx)
            .withSigner(SignerProviders.signerFrom(faucet))
            .completeAndWait(s -> log("  [fund] " + s));
        assertOk(result, "fund wallets");
        waitForTx(5);
        log("Wallets funded");
    }

    static void buildScripts() throws Exception {
        log("Loading plutus.json and applying parameters...");

        /* ── Resolve oneshot bootstrap UTxOs ─────────────────────────────── */
        // After fundWallets() each account has exactly one UTxO from the faucet.
        // These UTxOs are consumed in telInit / wlInit respectively.
        Utxo teBootUtxo = pickBootUtxo(issuer.baseAddress());
        teBootTxHash = teBootUtxo.getTxHash();
        teBootIndex  = teBootUtxo.getOutputIndex();
        Utxo wlBootUtxo = pickBootUtxo(entity.baseAddress());
        wlBootTxHash = wlBootUtxo.getTxHash();
        wlBootIndex  = wlBootUtxo.getOutputIndex();
        log("  TE bootstrap UTxO : " + teBootTxHash.substring(0, 12) + "#" + teBootIndex);
        log("  WL bootstrap UTxO : " + wlBootTxHash.substring(0, 12) + "#" + wlBootIndex);

        /* ── Apply issuer_pkh + oref parameters to trusted_entity script ─── */
        String teCode    = loadCompiledCode("trusted_entity.trusted_entity.mint");
        String teApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(bytes(issuerPkh), outputRef(teBootTxHash, teBootIndex)), teCode);

        teScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(teApplied, PlutusVersion.v3);
        tePolicyId      = teScript.getPolicyId();
        teScriptAddress = AddressProvider.getEntAddress(teScript, Networks.testnet()).toBech32();
        log("  TE policy id  : " + tePolicyId);
        log("  TE script addr: " + teScriptAddress);

        /* ── Apply WLConfig { te_policy_id, oref } to whitelist script ────── */
        // WLConfig = Constr(0, [te_policy_id_bytes, OutputReference])
        String wlCode    = loadCompiledCode("whitelist_mutation.whitelist.mint");
        String wlApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(constr(0, bytes(tePolicyId), outputRef(wlBootTxHash, wlBootIndex))), wlCode);

        wlScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(wlApplied, PlutusVersion.v3);
        wlPolicyId      = wlScript.getPolicyId();
        wlScriptAddress = AddressProvider.getEntAddress(wlScript, Networks.testnet()).toBech32();
        log("  WL policy id  : " + wlPolicyId);
        log("  WL script addr: " + wlScriptAddress);
    }

    /* ── TEL Transactions ───────────────────────────────────────────────────── */

    static void telInit() throws Exception {
        // Datum:    TENode { pkh: issuerPkh, next: Empty }
        // Redeemer: TEInit = constr(0,[])
        // Mint:     1 x (tePolicyId, "TEL")
        // The bootstrap UTxO (teBootTxHash/teBootIndex) is force-consumed here
        // to satisfy the on-chain oref check (oneshot guarantee).
        ConstrPlutusData headDatum = teNodeDatum(issuerVkeyHex, null);
        PlutusData       redeemer  = constr(0);
        Asset            token     = mintAsset(TE_PREFIX_HEX, 1);

        ScriptTx scriptTx = new ScriptTx()
            .mintAsset(teScript, token, redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                headDatum);

        // Force-consume the boot UTxO so the on-chain oref check passes.
        Utxo bootUtxo = findUtxoByRef(issuer.baseAddress(), teBootTxHash, teBootIndex);
        Tx tx = new Tx()
            .collectFrom(List.of(bootUtxo))
            .from(issuer.baseAddress());

        submitScript(scriptTx, tx, issuer, "TEL Init");
        waitForTx(4);
        log("  OK head node minted at " + teScriptAddress);
    }

    static void telAdd() throws Exception {
        // Current head: { pkh: issuerPkh, next: Empty }
        // After:        { pkh: issuerPkh, next: Key{entityPkh} }
        // New node:     { pkh: entityPkh, next: Empty }
        // Redeemer:     TEAdd = constr(2, [entityPkh, covering_node])
        Utxo             headUtxo    = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX);
        ConstrPlutusData headNode    = teNodeDatum(issuerVkeyHex, null);
        ConstrPlutusData updatedHead = teNodeDatum(issuerVkeyHex, entityPkh);
        ConstrPlutusData entityNode  = teNodeDatum(entityVkeyHex, null);

        // TEAdd redeemer = constr(2, [new_vkey, covering_node])
        PlutusData redeemer = constr(2, bytes(entityVkeyHex), headNode);

        // Two ScriptTx: (1) spend head + update head output; (2) mint entity token + create entity node.
        // This ensures CCL correctly places the minted token in the entity node output.
        ScriptTx spendTx = new ScriptTx()
            .collectFrom(headUtxo, redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                updatedHead)
            .attachSpendingValidator(teScript);

        ScriptTx mintTx = new ScriptTx()
            .mintAsset(teScript, mintAsset(TE_PREFIX_HEX + entityPkh, 1), redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX + entityPkh, 1L)),
                entityNode);

        Tx tx = new Tx().from(issuer.baseAddress());
        var result0 = builder.compose(spendTx, mintTx, tx)
            .feePayer(issuer.baseAddress())
            .withSigner(SignerProviders.signerFrom(issuer))
            .withRequiredSigners(issuer.hdKeyPair().getPublicKey().getKeyHash())
            .completeAndWait(s -> log("  [TEL Add entity] " + s));
        assertOk(result0, "TEL Add entity");
        waitForTx(4);
        log("  OK entity added to TEL: " + entityPkh.substring(0, 12) + "...");
    }

    static void telRemove() throws Exception {
        // Redeemer: TERemove = constr(3, [entityPkh, covering_node])
        // covering_node currently points to entityPkh
        String           entityTn    = TE_PREFIX_HEX + entityPkh;
        Utxo             headUtxo    = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX);
        Utxo             entityUtxo  = findUtxoByToken(teScriptAddress, tePolicyId, entityTn);
        ConstrPlutusData headNode    = teNodeDatum(issuerVkeyHex, entityPkh);
        ConstrPlutusData entityNode  = teNodeDatum(entityVkeyHex, null);
        ConstrPlutusData restoredHead = teNodeDatum(issuerVkeyHex, null);

        // TERemove redeemer = constr(3, [remove_vkey, covering_node])
        PlutusData redeemer = constr(3, bytes(entityVkeyHex), headNode);

        ScriptTx scriptTx = new ScriptTx()
            .collectFrom(headUtxo, redeemer)
            .collectFrom(entityUtxo, redeemer)
            .mintAsset(teScript, mintAsset(entityTn, -1), redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                restoredHead)
            .attachSpendingValidator(teScript);

        Tx tx = new Tx().from(issuer.baseAddress());
        submitScript(scriptTx, tx, issuer, "TEL Remove entity");
        waitForTx(4);
        log("  OK entity removed from TEL");
    }

    static void telDeinit() throws Exception {
        // Redeemer: TEDeinit = constr(1,[])
        Utxo             headUtxo = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX);
        PlutusData       redeemer = constr(1);

        ScriptTx scriptTx = new ScriptTx()
            .collectFrom(headUtxo, redeemer)
            .mintAsset(teScript, mintAsset(TE_PREFIX_HEX, -1), redeemer)
            .attachSpendingValidator(teScript);

        Tx tx = new Tx().from(issuer.baseAddress());
        submitScript(scriptTx, tx, issuer, "TEL Deinit");
        waitForTx(4);
        log("  OK TEL torn down");
    }

    /* ── WL Transactions ────────────────────────────────────────────────────── */

    static void wlInit() throws Exception {
        // Signed by entity (a TEL member). TEL node provided as reference input.
        // Redeemer: WLInit = constr(0,[])
        // The WL bootstrap UTxO (wlBootTxHash/wlBootIndex) is force-consumed.
        Utxo             teEntityRef = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX + entityPkh);
        // Use empty-byte PKH for the WL head sentinel so covers_key treats it
        // as Empty — allowing any user insertion regardless of byte ordering.
        ConstrPlutusData headDatum   = wlNodeDatum("", true, null);
        PlutusData       redeemer    = constr(0);

        ScriptTx scriptTx = new ScriptTx()
            .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX, 1), redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX, 1L)),
                headDatum)
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex());

        // Force-consume the WL boot UTxO to satisfy on-chain oref check.
        Utxo wlBootUtxo = findUtxoByRef(entity.baseAddress(), wlBootTxHash, wlBootIndex);
        Tx tx = new Tx()
            .collectFrom(List.of(wlBootUtxo))
            .from(entity.baseAddress());

        submitScript(scriptTx, tx, entity, "WL Init");
        waitForTx(4);
        log("  OK WL head node minted at " + wlScriptAddress);
    }

    static void wlAdd() throws Exception {
        // WLAdd redeemer now uses off-chain Ed25519 endorsement:
        //   Redeemer: constr(2, [new_pkh, keri_id, expiry, covering_node, entity_vkey_32, entity_endorsement_64])
        //
        // Flow: entity signs user's PKH bytes off-chain → user submits tx with endorsement.
        // Only the user needs to co-sign the transaction.

        Utxo             headUtxo    = findUtxoByToken(wlScriptAddress, wlPolicyId, WL_PREFIX_HEX);
        Utxo             teEntityRef = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX + entityPkh);
        ConstrPlutusData headNode    = wlNodeDatum("", true, null);
        ConstrPlutusData updatedHead = wlNodeDatum("", true, userPkh);
        ConstrPlutusData userNode    = wlNodeDatum(userPkh, true, null);

        // Entity signs user's PKH bytes off-chain
        byte[] userPkhBytes      = HexUtil.decodeHexString(userPkh);
        byte[] entityVkeyRaw     = extractRawVkey(entity);      // 32 bytes
        byte[] entityEndorsement = signBytes(entity, userPkhBytes); // 64 bytes
        log("  Entity vkey (32 bytes) : " + HexUtil.encodeHexString(entityVkeyRaw));
        log("  Endorsement (64 bytes) : " + HexUtil.encodeHexString(entityEndorsement).substring(0, 16) + "...");

        PlutusData redeemer = constr(2,
            bytes(userPkh),
            headNode,
            BytesPlutusData.of(entityVkeyRaw),
            BytesPlutusData.of(entityEndorsement));

        // Two ScriptTx: (1) spend covering node + update it; (2) mint user token + create user node.
        ScriptTx spendTx2 = new ScriptTx()
            .collectFrom(headUtxo, redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX, 1L)),
                updatedHead)
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex())
            .attachSpendingValidator(wlScript);

        ScriptTx mintTx2 = new ScriptTx()
            .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX + userPkh, 1), redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                userNode);

        // Only user self-signs — entity endorsed off-chain
        Tx tx = new Tx().from(user.baseAddress());
        var result = builder.compose(spendTx2, mintTx2, tx)
            .feePayer(user.baseAddress())
            .withSigner(SignerProviders.signerFrom(user))
            .withRequiredSigners(HexUtil.decodeHexString(userPkh))
            .completeAndWait(s -> log("  [WL Add] " + s));
        assertOk(result, "WL Add user");
        waitForTx(4);
        log("  OK user " + userPkh.substring(0, 12) + "... added with active=true");
    }

    static void wlSetActive(boolean newActive) throws Exception {
        // Redeemer: WLSetActive = constr(3, [target_pkh, Bool])
        // Bool: true=constr(1,[]), false=constr(0,[])
        String           userTn      = WL_PREFIX_HEX + userPkh;
        Utxo             userUtxo    = findUtxoByToken(wlScriptAddress, wlPolicyId, userTn);
        Utxo             teEntityRef = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX + entityPkh);

        ConstrPlutusData currentNode = wlNodeDatum(userPkh, !newActive, null);
        ConstrPlutusData updatedNode = wlNodeDatum(userPkh, newActive, null);

        PlutusData redeemer = constr(3, bytes(userPkh), constr(newActive ? 1 : 0));

        ScriptTx scriptTx = new ScriptTx()
            .collectFrom(userUtxo, redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(ada(2)),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                updatedNode)
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex())
            .attachSpendingValidator(wlScript);

        Tx tx = new Tx().from(entity.baseAddress());
        submitScript(scriptTx, tx, entity, "WL SetActive=" + newActive);
        waitForTx(4);
        log("  OK user active=" + newActive);
    }

    /* ── Datum constructors ─────────────────────────────────────────────────── */

    /** TENode = constr(0, [vkey_bytes, NodeKey]) */
    static ConstrPlutusData teNodeDatum(String vkeyHex, String nextPkhHex) {
        return (ConstrPlutusData) constr(0, bytes(vkeyHex), nodeKey(nextPkhHex));
    }

    /** WLNode = constr(0, [pkh, active, next]) */
    static ConstrPlutusData wlNodeDatum(
            String pkhHex, boolean active, String nextPkhHex) {
        return (ConstrPlutusData) constr(0,
            bytes(pkhHex),
            constr(active ? 1 : 0),         // Bool: true=constr(1), false=constr(0)
            nodeKey(nextPkhHex));
    }

    /** NodeKey.Empty=constr(1,[])  NodeKey.Key{k}=constr(0,[bytes]) */
    static PlutusData nodeKey(String pkhHex) {
        return pkhHex == null ? constr(1) : constr(0, bytes(pkhHex));
    }

    /* ── PlutusData helpers ─────────────────────────────────────────────────── */

    static PlutusData constr(int alt) {
        return ConstrPlutusData.builder().alternative(alt).data(ListPlutusData.of()).build();
    }

    static PlutusData constr(int alt, PlutusData... fields) {
        return ConstrPlutusData.builder().alternative(alt).data(ListPlutusData.of(fields)).build();
    }

    static PlutusData bytes(String hex) {
        if (hex == null || hex.isEmpty()) return BytesPlutusData.of(new byte[0]);
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    /**
     * OutputReference = Constr(0, [tx_hash_bytes, output_index])
     * Aiken stdlib v3: TransactionId is a plain ByteArray (no extra Constr wrapper).
     */
    static PlutusData outputRef(String txHashHex, int outputIndex) {
        return constr(0, bytes(txHashHex), BigIntPlutusData.of(outputIndex));
    }

    /* ── Script loading & parameter application ─────────────────────────────── */

    /**
     * Read compiledCode hex for a given validator title from aiken/plutus.json.
     * Expects the script to be run from the project root (user.dir).
     */
    static String loadCompiledCode(String title) throws Exception {
        Path p = Path.of(System.getProperty("user.dir"), "aiken", "plutus.json");
        String json = Files.readString(p);
        Matcher m = Pattern.compile(
            "\"title\"\\s*:\\s*\"" + Pattern.quote(title) + "\"" +
            "[\\s\\S]*?\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        if (!m.find()) throw new RuntimeException("Validator not found: " + title);
        return m.group(1);
    }

    /* ── Ed25519 off-chain signing helpers ──────────────────────────────────── */

    /**
     * Extract the raw 32-byte Ed25519 public key from a CCL Account.
     * Account.publicKeyBytes() returns exactly 32 bytes (no chain code).
     */
    static byte[] extractRawVkey(Account account) {
        return account.publicKeyBytes();
    }

    /**
     * Sign a raw message with a CCL Account's Ed25519 extended private key.
     * Uses EdDSASigningProvider.signExtended(message, key64) — message first,
     * 64-byte extended key second — producing a 64-byte Ed25519 signature
     * verifiable by Plutus verifyEd25519Signature with the 32-byte public key.
     */
    static byte[] signBytes(Account account, byte[] message) {
        return new EdDSASigningProvider()
            .signExtended(message, account.hdKeyPair().getPrivateKey().getKeyData());
    }

    /* ── Helpers ────────────────────────────────────────────────────────────── */

    static BigInteger ada(long n) { return BigInteger.valueOf(n * 1_000_000L); }

    static Asset mintAsset(String tokenNameHex, long qty) {
        return Asset.builder()
            .name("0x" + tokenNameHex)
            .value(BigInteger.valueOf(qty))
            .build();
    }

    static void submitScript(ScriptTx scriptTx, Tx tx, Account signer, String label)
            throws Exception {
        var result = builder.compose(scriptTx, tx)
            .feePayer(signer.baseAddress())
            .withSigner(SignerProviders.signerFrom(signer))
            .withRequiredSigners(signer.hdKeyPair().getPublicKey().getKeyHash())
            .completeAndWait(s -> log("  [" + label + "] " + s));
        assertOk(result, label);
    }

    static void assertOk(Result<?> result, String label) {
        if (!result.isSuccessful())
            throw new RuntimeException("FAILED [" + label + "]: " + result.getResponse());
        log("  OK " + label + " => tx: " + result.getValue());
    }

    static Utxo findUtxoByToken(String addr, String policyId, String tokenNameHex)
            throws Exception {
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null)
            throw new RuntimeException("Cannot fetch UTxOs at " + addr + ": " + page.getResponse());
        String unit = policyId + tokenNameHex;
        return page.getValue().stream()
            .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
            .findFirst()
            .orElseThrow(() -> new RuntimeException(
                "UTxO with token " + tokenNameHex + " not found at " + addr));
    }

    /** Find a UTxO by txHash+outputIndex (for force-consuming the bootstrap UTxO). */
    static Utxo findUtxoByRef(String addr, String txHash, int index) throws Exception {
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (page.isSuccessful() && page.getValue() != null) {
            for (Utxo u : page.getValue()) {
                if (u.getTxHash().equals(txHash) && u.getOutputIndex() == index) return u;
            }
        }
        throw new RuntimeException(
            "Bootstrap UTxO not found: " + txHash.substring(0, 12) + "#" + index);
    }

    /** Pick the first available UTxO from an address (for oneshot bootstrap). */
    static Utxo pickBootUtxo(String address) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 10, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty())
            return page.getValue().get(0);
        throw new RuntimeException("No UTxOs at " + address + " to use as bootstrap");
    }

    static void waitForTx(int seconds) throws InterruptedException {
        log("  waiting " + seconds + "s for chain...");
        Thread.sleep(seconds * 1000L);
    }

    static void step(String n, String desc) {
        System.out.println();
        System.out.println("======================================================");
        System.out.println("  STEP " + n + ": " + desc);
        System.out.println("======================================================");
    }

    static void banner(String msg) {
        System.out.println();
        System.out.println("*****************************************************");
        System.out.println("  " + msg);
        System.out.println("*****************************************************");
        System.out.println();
    }

    static void log(String msg) { System.out.println("  " + msg); }
}

