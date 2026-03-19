///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DESCRIPTION Step 1 – Initialise the Trusted Entity List and add a trusted entity account.
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
 * Step 1: Initialise the Trusted Entity List (TEL) and add a trusted entity.
 *
 * Reads accounts from scripts/.env (see .env.example for the format).
 *
 * What this script does:
 *   1. Funds ISSUER and ENTITY wallets from the FAUCET (devnet only).
 *   2. Loads the `trusted_entity` validator from aiken/plutus.json and applies
 *      the issuer's public-key hash as the sole admin parameter.
 *   3. Calls TELInit  – mints the head sentinel node (issuer is the first entry).
 *   4. Calls TELAdd   – issuer adds the trusted ENTITY to the TEL.
 *   5. Prints the final TEL state (head + entity node).
 *
 * Prerequisites:
 *   - Local yaci-devkit running:  docker compose up -d
 *   - .env file present in the scripts/ directory (copy .env.example and fill in mnemonics).
 *
 * Usage (from project root):
 *   jbang scripts/InitTrustedEntityList.java
 */
public class InitTrustedEntityList {

    static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    static BackendService backend;
    static QuickTxBuilder builder;
    static Account faucet, issuer, entity;
    static String issuerPkh, entityPkh;
    static String issuerVkeyHex, entityVkeyHex;
    static PlutusV3Script teScript;
    static String tePolicyId, teScriptAddress;
    // Bootstrap UTxO for oneshot TEL policy (stored in .env so the same policy id
    // can be reconstructed on subsequent runs without repeating TELInit).
    static String teBootTxHash;
    static int    teBootIndex;
    static Path envFilePath;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        banner("KYC – Step 1: Init Trusted Entity List");

        Map<String, String> env = loadEnv();

        String blockfrostUrl = env.getOrDefault("BLOCKFROST_URL", "http://localhost:8080/api/v1/");
        String blockfrostKey = env.getOrDefault("BLOCKFROST_PROJECT_ID", "localnet");

        backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        builder = new QuickTxBuilder(backend);
        log("Connected to " + blockfrostUrl);

        // ── Accounts ──────────────────────────────────────────────────────
        issuer = Account.createFromMnemonic(Networks.testnet(), requireEnv(env, "ISSUER_MNEMONIC"));
        String entityMnemonic = getOrCreateMnemonic(env, "ENTITY_MNEMONIC");
        entity = Account.createFromMnemonic(Networks.testnet(), entityMnemonic);

        issuerPkh = HexUtil.encodeHexString(issuer.hdKeyPair().getPublicKey().getKeyHash());
        entityPkh = HexUtil.encodeHexString(entity.hdKeyPair().getPublicKey().getKeyHash());
        issuerVkeyHex = HexUtil.encodeHexString(issuer.publicKeyBytes());
        entityVkeyHex = HexUtil.encodeHexString(entity.publicKeyBytes());

        log("Issuer  address : " + issuer.baseAddress());
        log("Issuer  PKH     : " + issuerPkh);
        log("Issuer  vkey    : " + issuerVkeyHex.substring(0, 12) + "...");
        log("Entity  address : " + entity.baseAddress());
        log("Entity  PKH     : " + entityPkh);
        log("Entity  vkey    : " + entityVkeyHex.substring(0, 12) + "...");

        // ── Bootstrap UTxO (oneshot policy) ──────────────────────────────────
        // Read from .env if already stored (idempotent runs), otherwise resolved
        // later in buildTeScript() after the issuer wallet has been funded.
        teBootTxHash = env.get("TE_BOOT_TXHASH");
        String teBootIndexStr = env.get("TE_BOOT_INDEX");
        teBootIndex = (teBootIndexStr != null && !teBootIndexStr.isBlank())
            ? Integer.parseInt(teBootIndexStr) : 0;

        // ── Optional funding (devnet only) ────────────────────────────────────
        String faucetMnemonic = env.get("FAUCET_MNEMONIC");
        if (faucetMnemonic != null && !faucetMnemonic.isBlank()) {
            faucet = Account.createFromMnemonic(Networks.testnet(), faucetMnemonic);
            fundAccounts(faucetMnemonic.equals(issuer.mnemonic()) ? null : faucet,
                         issuer, entity);
        }

        // ── Load & parameterise the TEL validator ─────────────────────────────
        step("Load TEL validator");
        buildTeScript();
        log("TE policy id    : " + tePolicyId);
        log("TE script addr  : " + teScriptAddress);

        // ── TEL Init (idempotent) ─────────────────────────────────────────────
        step("TEL Init");
        Utxo existing = findUtxoByToken(teScriptAddress, tePolicyId, TE_PREFIX_HEX);
        if (existing != null) {
            log("TEL head node already exists – skipping Init");
        } else {
            telInit();
        }

        // ── TEL Add entity (idempotent) ────────────────────────────────────────
        step("TEL Add entity");
        String entityTn = TE_PREFIX_HEX + entityPkh;
        Utxo entityNode = findUtxoByToken(teScriptAddress, tePolicyId, entityTn);
        if (entityNode != null) {
            log("Entity already in TEL – skipping Add");
        } else {
            telAdd();
        }

        // ── Print final state ─────────────────────────────────────────────────
        step("Final TEL state");
        printTelState();

        banner("Done – TEL initialised with issuer + entity");
    }

    // ── TEL transactions ──────────────────────────────────────────────────────

    static void telInit() throws Exception {
        // Head datum: TENode { vkey: issuerVkeyHex, next: Empty }
        ConstrPlutusData headDatum = teNodeDatum(issuerVkeyHex, null);
        PlutusData       redeemer  = constr(0);

        // Find the bootstrap UTxO so we can force-consume it explicitly.
        Utxo bootUtxo = requireUtxo(issuer.baseAddress(), null, teBootTxHash, teBootIndex, "TE boot UTxO");

        ScriptTx scriptTx = new ScriptTx()
            .mintAsset(teScript, mintAsset(TE_PREFIX_HEX, 1), redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                headDatum);

        // Force-consume the bootstrap UTxO; coin selection covers any remainder.
        Tx tx = new Tx()
            .collectFrom(List.of(bootUtxo))
            .from(issuer.baseAddress());

        submit(scriptTx, tx, issuer, "TEL Init");
        waitChain(5, "Init");
        log("Head node minted at " + teScriptAddress);
    }

    static void telAdd() throws Exception {
        // Before:  head { pkh: issuerPkh, next: Empty }
        // After:   head { pkh: issuerPkh, next: Key{entityPkh} }
        //          new  { pkh: entityPkh, next: Empty }
        // Redeemer: TEAdd = constr(2, [entityPkh, covering_node])
        Utxo             headUtxo    = requireUtxo(teScriptAddress, tePolicyId, TE_PREFIX_HEX, "TEL head");
        ConstrPlutusData headNode    = teNodeDatum(issuerVkeyHex, null);
        ConstrPlutusData updatedHead = teNodeDatum(issuerVkeyHex, entityPkh);
        ConstrPlutusData entityNode  = teNodeDatum(entityVkeyHex, null);

        // covering_node in the redeemer is the CURRENT state (before update)
        // TEAdd redeemer = constr(2, [new_vkey, covering_node])
        PlutusData redeemer = constr(2, bytes(entityVkeyHex), headNode);

        // ScriptTx 1: spend the head UTxO (spend handler) + update the head output.
        // The head token flows from input to output here.
        ScriptTx spendTx = new ScriptTx()
            .collectFrom(headUtxo, redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                updatedHead)
            .attachSpendingValidator(teScript);

        // ScriptTx 2: mint the entity token (mint handler) + create the entity node output.
        // Mirrors the TELInit pattern so CCL correctly places the minted token in the output.
        ScriptTx mintTx = new ScriptTx()
            .mintAsset(teScript, mintAsset(TE_PREFIX_HEX + entityPkh, 1), redeemer)
            .payToContract(teScriptAddress,
                List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                        Amount.asset(tePolicyId + TE_PREFIX_HEX + entityPkh, 1L)),
                entityNode);

        Tx tx = new Tx().from(issuer.baseAddress());
        var result = builder.compose(spendTx, mintTx, tx)
            .feePayer(issuer.baseAddress())
            .withSigner(SignerProviders.signerFrom(issuer))
            .withRequiredSigners(issuer.hdKeyPair().getPublicKey().getKeyHash())
            .completeAndWait(s -> log("  [TEL Add entity] " + s));
        assertOk(result, "TEL Add entity");
        waitChain(5, "Add entity");
        log("Entity added to TEL: " + entityPkh.substring(0, 12) + "...");
    }

    static void printTelState() throws Exception {
        var utxos = backend.getUtxoService().getUtxos(teScriptAddress, 100, 1);
        if (!utxos.isSuccessful() || utxos.getValue() == null || utxos.getValue().isEmpty()) {
            log("(no UTxOs found at TEL script address)");
            return;
        }
        log("TEL nodes at " + teScriptAddress + ":");
        for (Utxo u : utxos.getValue()) {
            String tokens = u.getAmount().stream()
                .filter(a -> !a.getUnit().equalsIgnoreCase("lovelace"))
                .map(a -> a.getUnit().substring(56)) // strip policy prefix
                .reduce((x, y) -> x + ", " + y).orElse("-");
            log("  " + u.getTxHash().substring(0, 12) + "#" + u.getOutputIndex()
                + "  tokens=" + tokens
                + "  datum=" + (u.getInlineDatum() != null ? u.getInlineDatum().substring(0, Math.min(40, u.getInlineDatum().length())) + "..." : "none"));
        }
    }

    // ── Script loading ────────────────────────────────────────────────────────

    static void buildTeScript() throws Exception {
        Path plutusJson = Path.of(System.getProperty("user.dir"), "aiken", "plutus.json");
        String compiledCode = loadCompiledCode(plutusJson, "trusted_entity.trusted_entity.mint");

        // Resolve the oneshot bootstrap UTxO.
        // On the first run it is queried from the issuer's wallet and persisted;
        // on subsequent runs it is loaded from .env so the policy id stays stable.
        if (teBootTxHash == null || teBootTxHash.isBlank()) {
            Utxo boot = pickBootUtxo(issuer.baseAddress());
            teBootTxHash = boot.getTxHash();
            teBootIndex  = boot.getOutputIndex();
            persistToEnv(envFilePath, "TE_BOOT_TXHASH", teBootTxHash);
            persistToEnv(envFilePath, "TE_BOOT_INDEX",  String.valueOf(teBootIndex));
            log("Bootstrap UTxO : " + teBootTxHash.substring(0, 12) + "#" + teBootIndex + " (saved to .env)");
        } else {
            log("Bootstrap UTxO : " + teBootTxHash.substring(0, 12) + "#" + teBootIndex + " (from .env)");
        }

        // Two parameters: issuer_pkh (ByteArray) and oref (OutputReference)
        // OutputReference = Constr(0, [tx_hash_bytes, output_index_int])
        ListPlutusData params = ListPlutusData.of(
            bytes(issuerPkh),
            outputRef(teBootTxHash, teBootIndex));
        String appliedCode = AikenScriptUtil.applyParamToScript(params, compiledCode);

        teScript        = (PlutusV3Script) PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(appliedCode, PlutusVersion.v3);
        tePolicyId      = teScript.getPolicyId();
        teScriptAddress = AddressProvider.getEntAddress(teScript, Networks.testnet()).toBech32();
    }

    // ── Datum builders ────────────────────────────────────────────────────────

    /** TENode = constr(0, [vkey_bytes, NodeKey]) */
    static ConstrPlutusData teNodeDatum(String vkeyHex, String nextPkhHex) {
        return (ConstrPlutusData) constr(0, bytes(vkeyHex), nodeKey(nextPkhHex));
    }

    /** NodeKey: Empty = constr(1,[])  Key{k} = constr(0,[bytes]) */
    static PlutusData nodeKey(String pkhHex) {
        return pkhHex == null ? constr(1) : constr(0, bytes(pkhHex));
    }

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
     * Note: In Aiken stdlib v3, TransactionId is a plain ByteArray alias,
     * so there is no extra Constr wrapper around the hash.
     */
    static PlutusData outputRef(String txHashHex, int outputIndex) {
        return constr(0, bytes(txHashHex), BigIntPlutusData.of(outputIndex));
    }

    // ── Asset helpers ─────────────────────────────────────────────────────────

    static Asset mintAsset(String tokenNameHex, long qty) {
        return Asset.builder()
            .name("0x" + tokenNameHex)
            .value(BigInteger.valueOf(qty))
            .build();
    }

    // ── Chain helpers ─────────────────────────────────────────────────────────

    static void submit(ScriptTx scriptTx, Tx tx, Account signer, String label) throws Exception {
        var result = builder.compose(scriptTx, tx)
            .feePayer(signer.baseAddress())
            .withSigner(SignerProviders.signerFrom(signer))
            .withRequiredSigners(signer.hdKeyPair().getPublicKey().getKeyHash())
            .completeAndWait(s -> log("  [" + label + "] " + s));
        assertOk(result, label);
    }

    static Utxo findUtxoByToken(String addr, String policyId, String tokenNameHex) throws Exception {
        String unit = policyId + tokenNameHex;
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null) return null;
        return page.getValue().stream()
            .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
            .findFirst().orElse(null);
    }

    /** Find a specific UTxO by txHash+index (for forcing the bootstrap UTxO as a transaction input). */
    static Utxo requireUtxo(String addr, String policyId, String txHash, int index, String name) throws Exception {
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (page.isSuccessful() && page.getValue() != null) {
            for (Utxo u : page.getValue()) {
                if (u.getTxHash().equals(txHash) && u.getOutputIndex() == index) return u;
            }
        }
        throw new RuntimeException("Cannot find UTxO for: " + name + " (" + txHash.substring(0, 12) + "#" + index + ")");
    }

    static Utxo requireUtxo(String addr, String policyId, String tokenNameHex, String name) throws Exception {
        Utxo u = findUtxoByToken(addr, policyId, tokenNameHex);
        if (u == null) throw new RuntimeException("Cannot find UTxO for: " + name);
        return u;
    }

    /** Pick the first available UTxO from an address (for use as oneshot bootstrap parameter). */
    static Utxo pickBootUtxo(String address) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 10, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty())
            return page.getValue().get(0);
        throw new RuntimeException("No UTxOs at " + address + " to use as bootstrap");
    }

    static void waitChain(int seconds, String ctx) throws InterruptedException {
        log("  waiting " + seconds + "s for '" + ctx + "' to be confirmed...");
        Thread.sleep(seconds * 1_000L);
    }

    static void assertOk(Result<?> r, String label) {
        if (!r.isSuccessful())
            throw new RuntimeException("FAILED [" + label + "]: " + r.getResponse());
        log("  OK " + label + " => " + r.getValue());
    }

    // ── Funding (devnet only) ─────────────────────────────────────────────────

    static void fundAccounts(Account faucetAccount, Account... targets) throws Exception {
        if (faucetAccount == null) {
            log("Skipping fund – issuer IS the faucet");
            return;
        }
        log("Funding accounts from faucet...");
        Tx tx = new Tx().from(faucetAccount.baseAddress());
        for (Account t : targets) tx = tx.payToAddress(t.baseAddress(), Amount.lovelace(ada(10)));
        var r = builder.compose(tx)
            .withSigner(SignerProviders.signerFrom(faucetAccount))
            .completeAndWait(s -> log("  [fund] " + s));
        assertOk(r, "fund accounts");
        waitChain(5, "fund");
    }

    // ── Script blueprint helpers ──────────────────────────────────────────────

    static String loadCompiledCode(Path blueprintPath, String title) throws Exception {
        String json = Files.readString(blueprintPath);
        Matcher m = Pattern.compile(
            "\"title\"\\s*:\\s*\"" + Pattern.quote(title) + "\"" +
            "[\\s\\S]*?\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        if (!m.find()) throw new RuntimeException("Validator not found: " + title);
        return m.group(1);
    }

    // ── .env loader ───────────────────────────────────────────────────────────

    static Map<String, String> loadEnv() throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        // Check both the scripts/ directory and project root
        Path[] candidates = {
            Path.of(System.getProperty("user.dir"), "scripts", ".env"),
            Path.of(System.getProperty("user.dir"), ".env"),
        };
        envFilePath = null;
        for (Path p : candidates) { if (Files.exists(p)) { envFilePath = p; break; } }
        if (envFilePath == null) {
            log("WARNING: .env file not found; using environment variables only.");
            System.getenv().forEach(map::put);
            // Default write target for generated accounts
            envFilePath = Path.of(System.getProperty("user.dir"), "scripts", ".env");
            return map;
        }
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

    /** Updates (or appends) key=value in the .env file atomically. */
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

    static BigInteger ada(long n) { return BigInteger.valueOf(n * 1_000_000L); }

    static void step(String label) {
        System.out.println();
        System.out.println("── " + label + " " + "─".repeat(Math.max(0, 60 - label.length())));
    }

    static void banner(String msg) {
        System.out.println();
        System.out.println("═".repeat(60));
        System.out.println("  " + msg);
        System.out.println("═".repeat(60));
        System.out.println();
    }

    static void log(String msg) { System.out.println("  " + msg); }
}
