///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DESCRIPTION Step 3 – A trusted entity toggles the active flag on a KYC Whitelist user node.
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
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
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
 * Step 3: A trusted entity toggles the active flag on a KYC Whitelist user node.
 *
 * Reads accounts from scripts/.env (see .env.example for the format).
 *
 * What this script does:
 *   1. Loads both TEL and WL validators from aiken/plutus.json.
 *   2. Looks up the user's current whitelist node.
 *   3. Entity submits a WLSetActive transaction via the spend endpoint, flipping
 *      the active flag:  true → false  or  false → true.
 *   4. Prints the user's node before and after.
 *
 * Only a trusted entity present in the TEL reference inputs is allowed to change
 * the active flag. All other node fields (pkh, next) remain untouched.
 *
 * Prerequisites:
 *   - Step 2 (AddUserToWhitelist) must have completed successfully.
 *   - .env file present in scripts/ directory.
 *
 * Usage (from project root):
 *   jbang scripts/ToggleActiveFlag.java
 *
 *   # Explicitly set the target flag:
 *   jbang scripts/ToggleActiveFlag.java --active=true
 *   jbang scripts/ToggleActiveFlag.java --active=false
 */
public class ToggleActiveFlag {

    static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    static final String WL_PREFIX_HEX = HexUtil.encodeHexString("WHL".getBytes());
    static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    static BackendService backend;
    static QuickTxBuilder builder;
    static Account issuer, entity, user;
    static String issuerPkh, entityPkh, userPkh;

    static PlutusV3Script teScript, wlScript;
    static String tePolicyId, wlPolicyId;
    static String teScriptAddress, wlScriptAddress;
    // Oneshot bootstrap UTxO references – must be present in .env.
    static String teBootTxHash;
    static int    teBootIndex;
    static String wlBootTxHash;
    static int    wlBootIndex;
    static Path envFilePath;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        banner("KYC – Step 3: Toggle Active Flag");

        // Optional CLI override: --active=true / --active=false
        Boolean forceActive = null;
        for (String arg : args) {
            if (arg.startsWith("--active=")) {
                forceActive = Boolean.parseBoolean(arg.substring("--active=".length()));
            }
        }

        Map<String, String> env = loadEnv();

        String url = env.getOrDefault("BLOCKFROST_URL", "http://localhost:8080/api/v1/");
        String key = env.getOrDefault("BLOCKFROST_PROJECT_ID", "localnet");
        backend = new BFBackendService(url, key);
        builder = new QuickTxBuilder(backend);
        log("Connected to " + url);

        // ── Accounts ──────────────────────────────────────────────────────────
        issuer = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "ISSUER_MNEMONIC"));
        entity = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "ENTITY_MNEMONIC"));
        user   = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "USER_MNEMONIC"));

        issuerPkh = HexUtil.encodeHexString(issuer.hdKeyPair().getPublicKey().getKeyHash());
        entityPkh = HexUtil.encodeHexString(entity.hdKeyPair().getPublicKey().getKeyHash());
        userPkh   = HexUtil.encodeHexString(user.hdKeyPair().getPublicKey().getKeyHash());

        log("Issuer  PKH  : " + issuerPkh);
        log("Entity  PKH  : " + entityPkh);
        log("User    PKH  : " + userPkh);

        // ── Bootstrap UTxO refs (oneshot policies) – required in .env ──────────
        teBootTxHash = env.get("TE_BOOT_TXHASH");
        String teBootIdxStr = env.get("TE_BOOT_INDEX");
        teBootIndex = (teBootIdxStr != null && !teBootIdxStr.isBlank()) ? Integer.parseInt(teBootIdxStr) : 0;
        wlBootTxHash = env.get("WL_BOOT_TXHASH");
        String wlBootIdxStr = env.get("WL_BOOT_INDEX");
        wlBootIndex = (wlBootIdxStr != null && !wlBootIdxStr.isBlank()) ? Integer.parseInt(wlBootIdxStr) : 0;

        // ── Build scripts ─────────────────────────────────────────────────────
        step("Load & parameterise validators");
        buildScripts();
        log("WL script addr : " + wlScriptAddress);

        // ── Find user's node ──────────────────────────────────────────────────
        step("Locate user's WL node");
        String userTn   = WL_PREFIX_HEX + userPkh;
        Utxo   userUtxo = findUtxoByToken(wlScriptAddress, wlPolicyId, userTn);
        if (userUtxo == null)
            throw new RuntimeException(
                "User node not found in whitelist. Run AddUserToWhitelist first.");

        WLNodeInfo currentNode = decodeWlNodeDatum(userUtxo);
        log("Current state:");
        log("  pkh    : " + currentNode.keyHex);
        log("  active : " + currentNode.active);
        log("  next   : " + (currentNode.nextHex == null ? "Empty" : currentNode.nextHex.substring(0, 12) + "..."));

        // ── Determine target active value ─────────────────────────────────────
        boolean newActive = forceActive != null ? forceActive : !currentNode.active;
        log("Target active : " + newActive + (forceActive != null ? " (forced)" : " (toggled)"));

        if (newActive == currentNode.active) {
            log("Active flag is already " + newActive + " – nothing to do.");
            return;
        }

        // ── Ensure entity is in TEL ───────────────────────────────────────────
        step("Verify entity in TEL");
        String entityTelTn = TE_PREFIX_HEX + entityPkh;
        Utxo teEntityRef = findUtxoByToken(teScriptAddress, tePolicyId, entityTelTn);
        if (teEntityRef == null)
            throw new RuntimeException(
                "Entity is NOT in the Trusted Entity List. Cannot toggle active flag.");
        log("Entity TEL ref: " + teEntityRef.getTxHash().substring(0, 12) + "#" + teEntityRef.getOutputIndex());

        // ── WLSetActive via the spend endpoint ────────────────────────────────
        step("WLSetActive: active " + currentNode.active + " → " + newActive);
        wlSetActive(userUtxo, currentNode, teEntityRef, newActive);

        // ── Print new state ───────────────────────────────────────────────────
        step("New state");
        Utxo updatedUtxo = findUtxoByToken(wlScriptAddress, wlPolicyId, userTn);
        if (updatedUtxo != null) {
            WLNodeInfo updated = decodeWlNodeDatum(updatedUtxo);
            log("Updated node:");
            log("  pkh    : " + updated.keyHex);
            log("  active : " + updated.active);
        } else {
            log("(node not yet visible; wait a moment and check manually)");
        }

        banner("Done – active flag set to " + newActive + " for user " + userPkh.substring(0, 12) + "...");
    }

    // ── WLSetActive ───────────────────────────────────────────────────────────

    static void wlSetActive(Utxo userUtxo, WLNodeInfo currentNode,
                             Utxo teEntityRef, boolean newActive) throws Exception {
        // The WLSetActive redeemer is handled by the SPEND endpoint (not mint).
        // Redeemer: constr(3, [target_pkh, Bool])
        //   Bool: true = constr(1,[])   false = constr(0,[])
        //
        // The contract checks:
        //   1. Spend datum.pkh == target_pkh
        //   2. Tx signed by a TEL member (reference input)
        //   3. Output has same datum except active = new_active

        PlutusData currentDatum  = wlNodeDatumFromInfo(currentNode);
        PlutusData updatedDatum  = wlNodeDatum(currentNode.keyHex, newActive,
                                               currentNode.nextHex);
        // WLSetActive = constr(3, [pkh, Bool])
        PlutusData redeemer = constr(3,
            bytes(userPkh),
            constr(newActive ? 1 : 0)); // true = constr(1), false = constr(0)

        ScriptTx scriptTx = new ScriptTx()
            .collectFrom(userUtxo, redeemer)                              // spend user node
            .payToContract(wlScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                updatedDatum)                                             // return token + updated datum
            .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex()) // TEL ref input
            .attachSpendingValidator(wlScript);

        Tx tx = new Tx().from(entity.baseAddress());
        var result = builder.compose(scriptTx, tx)
            .feePayer(entity.baseAddress())
            .withSigner(SignerProviders.signerFrom(entity))
            .withRequiredSigners(HexUtil.decodeHexString(entityPkh))
            .completeAndWait(s -> log("  [WLSetActive] " + s));
        assertOk(result, "WLSetActive active=" + newActive);
        waitChain(5, "WLSetActive");
        log("Active flag updated to: " + newActive);
    }

    // ── Script loading ────────────────────────────────────────────────────────

    static void buildScripts() throws Exception {
        Path plutusJson = Path.of(System.getProperty("user.dir"), "aiken", "plutus.json");

        // Both bootstrap UTxO references must be set; they were stored in .env by
        // InitTrustedEntityList and AddUserToWhitelist on their first runs.
        if (teBootTxHash == null || teBootTxHash.isBlank())
            throw new RuntimeException(
                "TE_BOOT_TXHASH not set in .env. Run InitTrustedEntityList first.");
        if (wlBootTxHash == null || wlBootTxHash.isBlank())
            throw new RuntimeException(
                "WL_BOOT_TXHASH not set in .env. Run AddUserToWhitelist first.");

        // TEL: parameterised by issuer_pkh (ByteArray) AND oref (OutputReference)
        String teCode    = loadCompiledCode(plutusJson, "trusted_entity.trusted_entity.mint");
        String teApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(bytes(issuerPkh), outputRef(teBootTxHash, teBootIndex)), teCode);
        teScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(teApplied, PlutusVersion.v3);
        tePolicyId      = teScript.getPolicyId();
        teScriptAddress = AddressProvider.getEntAddress(teScript, Networks.testnet()).toBech32();

        // WL: parameterised by WLConfig { te_policy_id, oref } = Constr(0, [bytes, OutputReference])
        String wlCode    = loadCompiledCode(plutusJson, "whitelist_mutation.whitelist.mint");
        String wlApplied = AikenScriptUtil.applyParamToScript(
            ListPlutusData.of(constr(0, bytes(tePolicyId), outputRef(wlBootTxHash, wlBootIndex))), wlCode);
        wlScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(wlApplied, PlutusVersion.v3);
        wlPolicyId      = wlScript.getPolicyId();
        wlScriptAddress = AddressProvider.getEntAddress(wlScript, Networks.testnet()).toBech32();
    }

    // ── Datum builders ────────────────────────────────────────────────────────

    static PlutusData wlNodeDatum(String pkhHex, boolean active, String nextPkhHex) {
        return constr(0,
            bytes(pkhHex),
            constr(active ? 1 : 0),
            nodeKey(nextPkhHex));
    }

    static PlutusData wlNodeDatumFromInfo(WLNodeInfo i) {
        return wlNodeDatum(i.keyHex, i.active, i.nextHex);
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
            var c = (ConstrPlutusData) PlutusData.deserialize(HexUtil.decodeHexString(hex));
            var f = c.getData().getPlutusDataList();
            byte[] keyRaw  = ((BytesPlutusData) f.get(0)).getValue();
            String keyHex  = keyRaw.length == 0 ? null : HexUtil.encodeHexString(keyRaw);
            boolean active = ((ConstrPlutusData) f.get(1)).getAlternative() == 1;
            var nk = (ConstrPlutusData) f.get(2);
            String nextHex = nk.getAlternative() == 1 ? null
                : HexUtil.encodeHexString(
                    ((BytesPlutusData) nk.getData().getPlutusDataList().get(0)).getValue());
            return new WLNodeInfo(keyHex, active, nextHex);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode WLNode for " + utxo.getTxHash(), e);
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
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    /**
     * OutputReference = Constr(0, [tx_hash_bytes, output_index])
     * Aiken stdlib v3: TransactionId is a plain ByteArray (no extra Constr wrapper).
     */
    static PlutusData outputRef(String txHashHex, int outputIndex) {
        return constr(0, bytes(txHashHex), BigIntPlutusData.of(outputIndex));
    }

    // ── Chain helpers ─────────────────────────────────────────────────────────

    static Utxo findUtxoByToken(String addr, String policyId, String tokenNameHex)
            throws Exception {
        String unit = policyId + tokenNameHex;
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null) return null;
        return page.getValue().stream()
            .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
            .findFirst().orElse(null);
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
        if (envFilePath == null) { log("WARNING: no .env file; using environment variables."); System.getenv().forEach(map::put); envFilePath = Path.of(System.getProperty("user.dir"), "scripts", ".env"); return map; }
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

    // ── Misc ──────────────────────────────────────────────────────────────────

    static void step(String label) {
        System.out.println("\n── " + label + " " + "─".repeat(Math.max(0, 60 - label.length())));
    }
    static void banner(String msg) {
        System.out.println("\n" + "═".repeat(60) + "\n  " + msg + "\n" + "═".repeat(60) + "\n");
    }
    static void log(String msg) { System.out.println("  " + msg); }
}
