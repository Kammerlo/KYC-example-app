///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DESCRIPTION Step 2 – Entity endorses a user off-chain; user adds themselves to the KYC Whitelist.
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
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 2: Entity signs user's public key off-chain; user self-registers in the KYC Whitelist.
 *
 * Reads accounts from scripts/.env (see .env.example for the format).
 *
 * What this script does:
 *   1. Loads both the TEL and WL validators from aiken/plutus.json.
 *   2. Initialises the KYC Whitelist (idempotent) – entity co-signs.
 *   3. Entity signs the user's PKH bytes off-chain with their Ed25519 private key,
 *      producing a 64-byte endorsement that is passed to the user.
 *   4. User submits the WLAdd transaction carrying:
 *        • Their own transaction signature (self-registration)
 *        • Entity's raw 32-byte verification key (for on-chain hash check against TEL)
 *        • The 64-byte off-chain endorsement (on-chain Ed25519 sig verification)
 *      The entity does NOT need to co-sign the submission transaction.
 *   5. Prints the user's whitelist node with active=true.
 *
 * Prerequisites:
 *   - Step 1 (InitTrustedEntityList) must have completed successfully.
 *   - .env file present in scripts/ directory.
 *   - Local yaci-devkit running.
 *
 * Usage (from project root):
 *   jbang scripts/AddUserToWhitelist.java
 */
public class AddUserToWhitelist {

    static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    static final String WL_PREFIX_HEX = HexUtil.encodeHexString("WHL".getBytes());
    static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    static BackendService backend;
    static QuickTxBuilder builder;
    static Account faucet, issuer, entity, user;
    static String issuerPkh, entityPkh, userPkh;

    static PlutusV3Script teScript, wlScript;
    static String tePolicyId, wlPolicyId;
    static String teScriptAddress, wlScriptAddress;
    // Oneshot bootstrap UTxO references – loaded from .env or queried on first run.
    static String teBootTxHash;
    static int    teBootIndex;
    static String wlBootTxHash;
    static int    wlBootIndex;
    static Path envFilePath;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        banner("KYC – Step 2: Endorse User & Add to Whitelist");

        Map<String, String> env = loadEnv();

        String url = env.getOrDefault("BLOCKFROST_URL", "http://localhost:8080/api/v1/");
        String key = env.getOrDefault("BLOCKFROST_PROJECT_ID", "localnet");
        backend = new BFBackendService(url, key);
        builder = new QuickTxBuilder(backend);
        log("Connected to " + url);

        // ── Accounts ──────────────────────────────────────────────────────────
        issuer = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "ISSUER_MNEMONIC"));
        entity = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "ENTITY_MNEMONIC"));
        String userMnemonic = getOrCreateMnemonic(env, "USER_MNEMONIC");
        user   = Account.createFromMnemonic(Networks.testnet(), userMnemonic);

        issuerPkh = HexUtil.encodeHexString(issuer.hdKeyPair().getPublicKey().getKeyHash());
        entityPkh = HexUtil.encodeHexString(entity.hdKeyPair().getPublicKey().getKeyHash());
        userPkh   = HexUtil.encodeHexString(user.hdKeyPair().getPublicKey().getKeyHash());

        log("Issuer  PKH : " + issuerPkh);
        log("Entity  PKH : " + entityPkh);
        log("User    PKH : " + userPkh);
        log("User    addr: " + user.baseAddress());

        // ── Bootstrap UTxO refs (oneshot policies) ───────────────────────────
        teBootTxHash = env.get("TE_BOOT_TXHASH");
        String teBootIdxStr = env.get("TE_BOOT_INDEX");
        teBootIndex = (teBootIdxStr != null && !teBootIdxStr.isBlank()) ? Integer.parseInt(teBootIdxStr) : 0;
        wlBootTxHash = env.get("WL_BOOT_TXHASH");
        String wlBootIdxStr = env.get("WL_BOOT_INDEX");
        wlBootIndex = (wlBootIdxStr != null && !wlBootIdxStr.isBlank()) ? Integer.parseInt(wlBootIdxStr) : 0;

        // ── Optional funding ──────────────────────────────────────────────────
        String faucetMnemonic = env.get("FAUCET_MNEMONIC");
        if (faucetMnemonic != null && !faucetMnemonic.isBlank()) {
            faucet = Account.createFromMnemonic(Networks.testnet(), faucetMnemonic);
            fundAccount(faucet, user, "User");
            fundAccount(faucet, entity, "Entity"); // entity pays for WL Init if needed
        }

        // ── Build scripts ─────────────────────────────────────────────────────
        step("Load & parameterise validators");
        buildScripts();
        log("TE policy id   : " + tePolicyId);
        log("WL policy id   : " + wlPolicyId);
        log("WL script addr : " + wlScriptAddress);

        // ── Ensure entity is in TEL ───────────────────────────────────────────
        step("Verify entity in TEL");
        String entityTelTn = TE_PREFIX_HEX + entityPkh;
        Utxo teEntityRef = findUtxoByToken(teScriptAddress, tePolicyId, entityTelTn);
        if (teEntityRef == null)
            throw new RuntimeException(
                "Entity is NOT in the Trusted Entity List. Run InitTrustedEntityList first.");
        log("Entity TEL node found: " + teEntityRef.getTxHash().substring(0, 12) + "#" + teEntityRef.getOutputIndex());

        // ── WL Init (idempotent) ───────────────────────────────────────────────
        step("WL Init (idempotent)");
        Utxo wlHead = findUtxoByToken(wlScriptAddress, wlPolicyId, WL_PREFIX_HEX);
        if (wlHead != null) {
            log("WL head node already exists – skipping Init");
        } else {
            wlInit(teEntityRef);
            wlHead = requireUtxo(wlScriptAddress, wlPolicyId, WL_PREFIX_HEX, "WL head after init");
        }

        // ── Check if user already in WL ───────────────────────────────────────
        String userWlTn = WL_PREFIX_HEX + userPkh;
        if (findUtxoByToken(wlScriptAddress, wlPolicyId, userWlTn) != null) {
            log("User already in the whitelist – nothing to do.");
            printUserNode();
            return;
        }

        // ── Off-chain endorsement: entity signs user's PKH ───────────────────
        step("Entity endorses user off-chain");

        // Extract entity's raw 32-byte Ed25519 public key.
        // Account.publicKeyBytes() returns exactly 32 bytes; no manual trimming needed.
        byte[] entityVkey32 = entity.publicKeyBytes();

        // Sign the user's PKH bytes (28 bytes) with the entity's Ed25519 extended private key.
        // signExtended(message, key64): message first, 64-byte key second.
        byte[] userPkhBytes      = HexUtil.decodeHexString(userPkh);
        byte[] entityEndorsement = signBytes(entity, userPkhBytes);

        log("Entity vkey (32 bytes)  : " + HexUtil.encodeHexString(entityVkey32));
        log("Endorsement (64 bytes)  : " + HexUtil.encodeHexString(entityEndorsement));
        log(">>> The entity hands this endorsement to the user. <<<");
        log(">>> The user can now submit the WLAdd tx independently. <<<");

        // ── WL Add user ───────────────────────────────────────────────────────
        step("WL Add user (user submits, only user signs the tx)");

        // Decode head node datum to get the covering node info
        // The head node is the covering node when the list has no other entries,
        // or we need to find the correct covering node when list is non-empty.
        WLNodeInfo coveringInfo = decodeWlNodeDatum(wlHead);
        log("Covering node PKH  : " + (coveringInfo.keyHex == null || coveringInfo.keyHex.isEmpty() ? "(empty sentinel)" : coveringInfo.keyHex.substring(0, 12) + "..."));
        log("Covering node next : " + (coveringInfo.nextHex == null || coveringInfo.nextHex.isEmpty() ? "Empty" : coveringInfo.nextHex.substring(0, 12) + "..."));

        wlAdd(wlHead, teEntityRef, coveringInfo,
              entityVkey32, entityEndorsement);

        // ── Print result ──────────────────────────────────────────────────────
        step("Result");
        printUserNode();

        banner("Done – User added to the KYC Whitelist with active=true");
    }

    // ── WL transactions ───────────────────────────────────────────────────────

    static void wlInit(Utxo teEntityRef) throws Exception {
        // WLInit – entity (a TEL member) mints the WL head sentinel node.
        // The bootstrap UTxO (wlBootTxHash/wlBootIndex) MUST be consumed here
        // to satisfy the on-chain oref check.
        // Use empty-byte PKH for the WL head sentinel so covers_key treats it
        // as Empty — allowing any user insertion regardless of byte ordering.
        ConstrPlutusData headDatum = wlNodeDatum("", true, null);
        PlutusData       redeemer  = constr(0);

        // Find the bootstrap UTxO to force-consume it.
        Utxo bootUtxo = requireUtxoByRef(entity.baseAddress(), wlBootTxHash, wlBootIndex, "WL boot UTxO");

        ScriptTx scriptTx = new ScriptTx()
            .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX, 1), redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX, 1L)),
                headDatum)
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex());

        // Force-consume boot UTxO; coin selection covers any remainder.
        Tx tx = new Tx()
            .collectFrom(List.of(bootUtxo))
            .from(entity.baseAddress());

        submit(scriptTx, tx, entity, "WL Init");
        waitChain(5, "WL Init");
        log("WL head node minted at " + wlScriptAddress);
    }

    static void wlAdd(Utxo coveringUtxo,
                      Utxo teEntityRef,
                      WLNodeInfo coveringInfo,
                      byte[] entityVkey32,
                      byte[] entityEndorsement) throws Exception {
        // WLAdd redeemer:
        //   constr(2, [new_pkh, covering_node, entity_vkey_32, entity_endorsement_64])
        //
        // On-chain checks:
        //   1. blake2b_224(entity_vkey_32) must be in TEL reference inputs
        //   2. verify_ed25519_signature(entity_vkey_32, new_pkh, entity_endorsement)
        //   3. Transaction signed by new_pkh (user self-registration)

        ConstrPlutusData coveringDatum  = wlNodeDatumFromInfo(coveringInfo);
        ConstrPlutusData updatedCovering = wlNodeDatumFromInfoWithNext(coveringInfo, userPkh);
        ConstrPlutusData newUserDatum    = wlNodeDatum(userPkh, true,
                                                        coveringInfo.nextHex);

        PlutusData redeemer = constr(2,
            bytes(userPkh),
            coveringDatum,
            BytesPlutusData.of(entityVkey32),
            BytesPlutusData.of(entityEndorsement));

        String userTokenName = WL_PREFIX_HEX + userPkh;

        // Two ScriptTx: (1) spend covering node + update it; (2) mint user token + create user node.
        ScriptTx spendTx = new ScriptTx()
            .collectFrom(coveringUtxo, redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX, 1L)),
                updatedCovering)
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex())
            .attachSpendingValidator(wlScript);

        ScriptTx mintTx = new ScriptTx()
            .mintAsset(wlScript, mintAsset(userTokenName, 1), redeemer)
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(wlPolicyId + userTokenName, 1L)),
                newUserDatum);

        // Only the USER signs — entity endorsed off-chain
        Tx tx = new Tx().from(user.baseAddress());
        var result = builder.compose(spendTx, mintTx, tx)
            .feePayer(user.baseAddress())
            .withSigner(SignerProviders.signerFrom(user))
            .withRequiredSigners(HexUtil.decodeHexString(userPkh))
            .completeAndWait(s -> log("  [WL Add] " + s));
        assertOk(result, "WL Add user");
        waitChain(5, "WL Add");
        log("User added with active=true: " + userPkh.substring(0, 12) + "...");
    }

    static void printUserNode() throws Exception {
        String userTn = WL_PREFIX_HEX + userPkh;
        Utxo u = findUtxoByToken(wlScriptAddress, wlPolicyId, userTn);
        if (u == null) {
            log("(user node not found in WL)");
            return;
        }
        WLNodeInfo info = decodeWlNodeDatum(u);
        log("User WL node:");
        log("  UTxO   : " + u.getTxHash().substring(0, 12) + "#" + u.getOutputIndex());
        log("  pkh    : " + info.keyHex);
        log("  active : " + info.active);
        log("  next   : " + (info.nextHex == null ? "Empty" : info.nextHex.substring(0, 12) + "..."));
    }

    // ── Script loading ────────────────────────────────────────────────────────

    static void buildScripts() throws Exception {
        Path plutusJson = Path.of(System.getProperty("user.dir"), "aiken", "plutus.json");

        // ─ TEL script ────────────────────────────────────────────────────────────
        // TE_BOOT_TXHASH/INDEX must already be in .env (set by InitTrustedEntityList).
        if (teBootTxHash == null || teBootTxHash.isBlank())
            throw new RuntimeException(
                "TE_BOOT_TXHASH not set in .env. Run InitTrustedEntityList first.");
        log("TE bootstrap UTxO : " + teBootTxHash.substring(0, 12) + "#" + teBootIndex);

        String teCode    = loadCompiledCode(plutusJson, "trusted_entity.trusted_entity.mint");
        String teApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(bytes(issuerPkh), outputRef(teBootTxHash, teBootIndex)), teCode);
        teScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(teApplied, PlutusVersion.v3);
        tePolicyId      = teScript.getPolicyId();
        teScriptAddress = AddressProvider.getEntAddress(teScript, Networks.testnet()).toBech32();

        // ─ WL script ────────────────────────────────────────────────────────────
        // WL_BOOT_TXHASH/INDEX: resolve from entity wallet on first run; persist.
        if (wlBootTxHash == null || wlBootTxHash.isBlank()) {
            Utxo boot = pickBootUtxo(entity.baseAddress());
            wlBootTxHash = boot.getTxHash();
            wlBootIndex  = boot.getOutputIndex();
            persistToEnv(envFilePath, "WL_BOOT_TXHASH", wlBootTxHash);
            persistToEnv(envFilePath, "WL_BOOT_INDEX",  String.valueOf(wlBootIndex));
            log("WL bootstrap UTxO : " + wlBootTxHash.substring(0, 12) + "#" + wlBootIndex + " (saved to .env)");
        } else {
            log("WL bootstrap UTxO : " + wlBootTxHash.substring(0, 12) + "#" + wlBootIndex + " (from .env)");
        }

        // WLConfig = Constr(0, [te_policy_id_bytes, OutputReference])
        String wlCode    = loadCompiledCode(plutusJson, "whitelist_mutation.whitelist.mint");
        String wlApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(constr(0, bytes(tePolicyId), outputRef(wlBootTxHash, wlBootIndex))), wlCode);
        wlScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(wlApplied, PlutusVersion.v3);
        wlPolicyId      = wlScript.getPolicyId();
        wlScriptAddress = AddressProvider.getEntAddress(wlScript, Networks.testnet()).toBech32();
    }

    // ── Datum builders ────────────────────────────────────────────────────────

    /** WLNode = constr(0, [pkh, active, next]) */
    static ConstrPlutusData wlNodeDatum(String pkhHex, boolean active, String nextPkhHex) {
        return (ConstrPlutusData) constr(0,
            bytes(pkhHex),
            constr(active ? 1 : 0),
            nodeKey(nextPkhHex));
    }

    static ConstrPlutusData wlNodeDatumFromInfo(WLNodeInfo i) {
        return wlNodeDatum(i.keyHex, i.active, i.nextHex);
    }

    static ConstrPlutusData wlNodeDatumFromInfoWithNext(WLNodeInfo i, String newNextPkh) {
        return wlNodeDatum(i.keyHex, i.active, newNextPkh);
    }

    static PlutusData nodeKey(String pkhHex) {
        return pkhHex == null ? constr(1) : constr(0, bytes(pkhHex));
    }

    // ── Datum decoding ────────────────────────────────────────────────────────

    static WLNodeInfo decodeWlNodeDatum(Utxo utxo) {
        String hex = utxo.getInlineDatum();
        if (hex == null || hex.isEmpty())
            throw new RuntimeException("UTxO has no inline datum: " + utxo.getTxHash());
        try {
            PlutusData raw = PlutusData.deserialize(HexUtil.decodeHexString(hex));
            var c = (ConstrPlutusData) raw;
            var f = c.getData().getPlutusDataList();
            String keyHex  = ((BytesPlutusData) f.get(0)).getValue().length == 0 ? ""
                           : HexUtil.encodeHexString(((BytesPlutusData) f.get(0)).getValue());
            boolean active = ((ConstrPlutusData) f.get(1)).getAlternative() == 1;
            var nextConstr = (ConstrPlutusData) f.get(2);
            String nextHex = nextConstr.getAlternative() == 1 ? null
                           : HexUtil.encodeHexString(
                               ((BytesPlutusData) nextConstr.getData().getPlutusDataList().get(0)).getValue());
            return new WLNodeInfo(keyHex, active, nextHex);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode WLNode datum for " + utxo.getTxHash(), e);
        }
    }

    record WLNodeInfo(String keyHex, boolean active, String nextHex) {}

    // ── PlutusData helpers ────────────────────────────────────────────────────

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

    // ── Asset helpers ─────────────────────────────────────────────────────────

    static Asset mintAsset(String tokenNameHex, long qty) {
        return Asset.builder().name("0x" + tokenNameHex).value(BigInteger.valueOf(qty)).build();
    }

    // ── Chain helpers ─────────────────────────────────────────────────────────

    static void submit(ScriptTx scriptTx, Tx tx, Account signer, String label) throws Exception {
        var r = builder.compose(scriptTx, tx)
            .feePayer(signer.baseAddress())
            .withSigner(SignerProviders.signerFrom(signer))
            .withRequiredSigners(signer.hdKeyPair().getPublicKey().getKeyHash())
            .completeAndWait(s -> log("  [" + label + "] " + s));
        assertOk(r, label);
    }

    static Utxo findUtxoByToken(String addr, String policyId, String tokenNameHex) throws Exception {
        String unit = policyId + tokenNameHex;
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null) return null;
        return page.getValue().stream()
            .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
            .findFirst().orElse(null);
    }

    /** Find a UTxO by txHash+outputIndex at an address (for force-consuming the bootstrap UTxO). */
    static Utxo requireUtxoByRef(String addr, String txHash, int index, String name) throws Exception {
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (page.isSuccessful() && page.getValue() != null) {
            for (Utxo u : page.getValue()) {
                if (u.getTxHash().equals(txHash) && u.getOutputIndex() == index) return u;
            }
        }
        throw new RuntimeException("Cannot find UTxO for: " + name
            + " (" + txHash.substring(0, 12) + "#" + index + ")");
    }

    /** Pick the first available UTxO from an address (for oneshot bootstrap). */
    static Utxo pickBootUtxo(String address) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 10, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty())
            return page.getValue().get(0);
        throw new RuntimeException("No UTxOs at " + address + " to use as bootstrap");
    }

    static Utxo requireUtxo(String addr, String policyId, String tn, String name) throws Exception {
        Utxo u = findUtxoByToken(addr, policyId, tn);
        if (u == null) throw new RuntimeException("Cannot find UTxO for: " + name);
        return u;
    }

    static void waitChain(int s, String ctx) throws InterruptedException {
        log("  waiting " + s + "s for '" + ctx + "'...");
        Thread.sleep(s * 1_000L);
    }

    static void assertOk(Result<?> r, String label) {
        if (!r.isSuccessful())
            throw new RuntimeException("FAILED [" + label + "]: " + r.getResponse());
        log("  OK " + label + " => " + r.getValue());
    }

    static void fundAccount(Account faucetAcc, Account target, String name) throws Exception {
        log("Funding " + name + " from faucet...");
        Tx tx = new Tx()
            .payToAddress(target.baseAddress(), Amount.lovelace(ada(10)))
            .from(faucetAcc.baseAddress());
        var r = builder.compose(tx)
            .withSigner(SignerProviders.signerFrom(faucetAcc))
            .completeAndWait(s -> log("  [fund " + name + "] " + s));
        assertOk(r, "fund " + name);
        waitChain(5, "fund " + name);
    }

    // ── Script blueprint helpers ──────────────────────────────────────────────

    static String loadCompiledCode(Path p, String title) throws Exception {
        String json = Files.readString(p);
        Matcher m = Pattern.compile(
            "\"title\"\\s*:\\s*\"" + Pattern.quote(title) + "\"" +
            "[\\s\\S]*?\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        if (!m.find()) throw new RuntimeException("Validator not found: " + title);
        return m.group(1);
    }

    // ── .env loader ───────────────────────────────────────────────────────────

    static Map<String, String> loadEnv() throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        Path[] candidates = {
            Path.of(System.getProperty("user.dir"), "scripts", ".env"),
            Path.of(System.getProperty("user.dir"), ".env"),
        };
        envFilePath = null;
        for (Path p : candidates) { if (Files.exists(p)) { envFilePath = p; break; } }
        if (envFilePath == null) { log("WARNING: no .env file found; using environment variables."); System.getenv().forEach(map::put); envFilePath = Path.of(System.getProperty("user.dir"), "scripts", ".env"); return map; }
        log("Loading env from: " + envFilePath.toAbsolutePath());
        for (String line : Files.readAllLines(envFilePath)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            map.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return map;
    }

    static String requireEnv(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank() || v.startsWith("<"))
            throw new RuntimeException("Missing required env variable: " + key);
        return v;
    }

    /** Returns the mnemonic for key; generates, persists and logs a fresh one if absent/placeholder. */
    static String getOrCreateMnemonic(Map<String, String> env, String key) throws Exception {
        String v = env.get(key);
        if (v != null && !v.isBlank() && !v.startsWith("<")) return v;
        String mnemonic = new Account(Networks.testnet()).mnemonic();
        log("No " + key + " set – generated random account for showcasing:");
        log("  mnemonic: " + mnemonic);
        log("  Save as " + key + "=<words> in scripts/.env to reuse this account.");
        env.put(key, mnemonic);
        persistToEnv(envFilePath, key, mnemonic);
        return mnemonic;
    }

    /** Updates (or appends) key=value in the .env file. */
    static void persistToEnv(Path envFile, String key, String value) throws Exception {
        List<String> lines = Files.exists(envFile)
            ? new ArrayList<>(Files.readAllLines(envFile))
            : new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            if (!stripped.startsWith("#") && stripped.startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                found = true;
                break;
            }
        }
        if (!found) lines.add(key + "=" + value);
        Files.writeString(envFile, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        log("  Persisted " + key + " to " + envFile.toAbsolutePath());
    }

    /** Signs message with the account's Ed25519 extended key (non-deprecated). */
    static byte[] signBytes(Account account, byte[] message) {
        return new EdDSASigningProvider()
            .signExtended(message, account.hdKeyPair().getPrivateKey().getKeyData());
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    static BigInteger ada(long n) { return BigInteger.valueOf(n * 1_000_000L); }

    static void step(String label) {
        System.out.println("\n── " + label + " " + "─".repeat(Math.max(0, 60 - label.length())));
    }
    static void banner(String msg) {
        System.out.println("\n" + "═".repeat(60) + "\n  " + msg + "\n" + "═".repeat(60) + "\n");
    }
    static void log(String msg) { System.out.println("  " + msg); }
}
