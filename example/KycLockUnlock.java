///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DESCRIPTION Example: lock ADA to the kyc_lock validator, then unlock it with a KYC proof.
//DEPS com.bloxbean.cardano:cardano-client-lib:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-backend-blockfrost:0.7.1
//DEPS com.bloxbean.cardano:cardano-client-quicktx:0.7.1
//DEPS com.bloxbean.cardano:aiken-java-binding:0.1.0
//DEPS org.slf4j:slf4j-simple:2.0.9

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lock ADA to the kyc_lock validator, then unlock it with a KYC proof.
 *
 * Environment variables:
 *
 *   BLOCKFROST_URL        – Blockfrost API endpoint
 *   BLOCKFROST_PROJECT_ID – Blockfrost project key
 *   MNEMONIC              – 24-word BIP39 mnemonic for the wallet
 *   TEL_POLICY_ID         – Policy id (hex) of the Trusted Entity List
 *   TEL_UTXO_REF          – TEL node UTxO reference, e.g. "aabb...ff#0"
 *   KYC_PAYLOAD           – 29-byte KYC proof payload (hex): user_pkh(28) || role(1)
 *   KYC_SIGNATURE         – 64-byte Ed25519 signature (hex)
 *
 * Usage:
 *   export BLOCKFROST_URL=https://cardano-preview.blockfrost.io/api/v0/
 *   export BLOCKFROST_PROJECT_ID=preview...
 *   export MNEMONIC="word1 word2 ... word24"
 *   export TEL_POLICY_ID=abcd1234...
 *   export TEL_UTXO_REF="txhash#0"
 *   export KYC_PAYLOAD=...
 *   export KYC_SIGNATURE=...
 *   jbang example/KycLockUnlock.java
 */
public class KycLockUnlock {

    static final BigInteger LOCK_AMOUNT = BigInteger.valueOf(5_000_000);

    static BackendService backend;
    static QuickTxBuilder builder;
    static Account wallet;
    static PlutusV3Script kycLockScript;
    static String kycLockScriptAddress;

    public static void main(String[] args) throws Exception {
        banner("KYC Lock/Unlock Example");

        String blockfrostUrl = requireEnv("BLOCKFROST_URL");
        String blockfrostKey = requireEnv("BLOCKFROST_PROJECT_ID");
        String mnemonic      = requireEnv("MNEMONIC");
        String telPolicyId   = requireEnv("TEL_POLICY_ID");
        String telUtxoRef    = requireEnv("TEL_UTXO_REF");
        String kycPayload    = requireEnv("KYC_PAYLOAD");
        String kycSignature  = requireEnv("KYC_SIGNATURE");

        String[] refParts = telUtxoRef.split("#");
        if (refParts.length != 2)
            throw new RuntimeException("TEL_UTXO_REF must be 'txhash#index', got: " + telUtxoRef);
        String telTxHash = refParts[0];
        int telTxIndex = Integer.parseInt(refParts[1]);

        backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        builder = new QuickTxBuilder(backend);
        wallet = Account.createFromMnemonic(Networks.testnet(), mnemonic);
        log("Wallet : " + wallet.baseAddress());

        // ── Load kyc_lock validator ───────────────────────────────────────────
        step("Load kyc_lock validator");
        buildKycLockScript(telPolicyId);
        log("kyc_lock address : " + kycLockScriptAddress);

        // ── Lock ADA (skip if script already has a UTxO) ──────────────────────
        step("Lock 5 ADA to kyc_lock");
        Utxo existing = findFirstUtxo(kycLockScriptAddress);
        if (existing != null) {
            log("Script already has a UTxO (" + existing.getTxHash().substring(0, 12) + "#" + existing.getOutputIndex() + ") — skipping lock");
        } else {
            lockFunds();
        }

        // ── Unlock ADA ────────────────────────────────────────────────────────
        step("Unlock ADA with KYC proof");
        log("Payload    : " + kycPayload);
        log("Signature  : " + kycSignature.substring(0, 24) + "...");
        log("TEL ref    : " + telTxHash.substring(0, Math.min(12, telTxHash.length())) + "#" + telTxIndex);
        unlockFunds(kycPayload, kycSignature, telTxHash, telTxIndex);

        banner("Done — locked and unlocked successfully!");
    }

    static void lockFunds() throws Exception {
        PlutusData unitDatum = ConstrPlutusData.builder()
            .alternative(0).data(ListPlutusData.of()).build();

        Tx tx = new Tx()
            .payToContract(kycLockScriptAddress,
                List.of(Amount.lovelace(LOCK_AMOUNT)), unitDatum)
            .from(wallet.baseAddress());

        var result = builder.compose(tx)
            .withSigner(SignerProviders.signerFrom(wallet))
            .completeAndWait(s -> log("  [lock] " + s));
        assertOk(result, "lock");
        waitChain(10, "lock utxo to appear");
        log("Locked 5 ADA at " + kycLockScriptAddress);
    }

    static void unlockFunds(String payloadHex, String signatureHex,
                            String telTxHash, int telTxIndex) throws Exception {
        Utxo lockedUtxo = findFirstUtxo(kycLockScriptAddress);
        if (lockedUtxo == null)
            throw new RuntimeException("No UTxO found at kyc_lock address");
        log("Locked UTxO  : " + lockedUtxo.getTxHash().substring(0, 12) + "#" + lockedUtxo.getOutputIndex());

        Utxo telNodeUtxo = findUtxoByRef(telTxHash, telTxIndex);
        if (telNodeUtxo == null)
            throw new RuntimeException("TEL node UTxO not found: " + telTxHash.substring(0, 12) + "#" + telTxIndex);
        log("TEL node     : " + telNodeUtxo.getTxHash().substring(0, 12) + "#" + telNodeUtxo.getOutputIndex());

        // ── Off-chain diagnosis: decode TEL datum & verify signature ──────
        String inlineDatum = telNodeUtxo.getInlineDatum();
        if (inlineDatum != null) {
            log("TEL inline datum (hex): " + inlineDatum.substring(0, Math.min(80, inlineDatum.length())) + "...");
            try {
                var di = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                        .deserialize(HexUtil.decodeHexString(inlineDatum));
                ConstrPlutusData constr = ConstrPlutusData.deserialize(di);
                var fields = constr.getData().getPlutusDataList();
                String telVkey = HexUtil.encodeHexString(((BytesPlutusData) fields.get(0)).getValue());
                int telRole = (int) ((ConstrPlutusData) fields.get(1)).getAlternative();
                log("TEL vkey     : " + telVkey);
                log("TEL role     : " + telRole);

                // Verify Ed25519 signature off-chain (JDK 15+ EdDSA)
                byte[] vkeyBytes = HexUtil.decodeHexString(telVkey);
                byte[] payloadBytes = HexUtil.decodeHexString(payloadHex);
                byte[] sigBytes = HexUtil.decodeHexString(signatureHex);
                boolean offChainValid = verifyEd25519(vkeyBytes, payloadBytes, sigBytes);
                log("Off-chain Ed25519 verify: " + offChainValid);

                // Check user PKH matches wallet
                String payloadUserPkh = payloadHex.substring(0, 56); // first 28 bytes = 56 hex chars
                String walletPkh = HexUtil.encodeHexString(wallet.hdKeyPair().getPublicKey().getKeyHash());
                log("Payload PKH  : " + payloadUserPkh);
                log("Wallet  PKH  : " + walletPkh);
                log("PKH match    : " + payloadUserPkh.equals(walletPkh));
            } catch (Exception e) {
                log("WARN: Could not decode TEL datum: " + e.getMessage());
            }
        } else {
            log("WARN: TEL UTxO has no inline datum!");
        }

        // Redeemer: KycProofRedeemer { payload, signature }
        PlutusData redeemer = ConstrPlutusData.builder()
            .alternative(0)
            .data(ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(payloadHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(signatureHex))
            ))
            .build();

        ScriptTx scriptTx = new ScriptTx()
            .collectFrom(lockedUtxo, redeemer)
            .readFrom(telNodeUtxo)
            .payToAddress(wallet.baseAddress(), List.of(Amount.lovelace(LOCK_AMOUNT)))
            .withChangeAddress(wallet.baseAddress())
            .attachSpendingValidator(kycLockScript);

        // validTo expects a slot number, not POSIX ms — query the chain tip
        long currentSlot = backend.getBlockService().getLatestBlock().getValue().getSlot();
        long txUpperSlot = currentSlot + 300; // ~5 minutes on preview (1 slot/sec)
        log("Current slot : " + currentSlot);
        log("Tx valid to  : slot " + txUpperSlot);

        // Blockfrost's evaluator can't handle PlutusV3 scripts with reference inputs,
        // so we provide a custom evaluator with generous exUnits.
        var result = builder.compose(scriptTx)
            .feePayer(wallet.baseAddress())
            .withSigner(SignerProviders.signerFrom(wallet))
            .withRequiredSigners(wallet.hdKeyPair().getPublicKey().getKeyHash())
            .validTo(txUpperSlot)
            .withTxEvaluator((cbor, inputUtxos) -> {
                var evalResult = EvaluationResult.builder()
                    .redeemerTag(RedeemerTag.Spend)
                    .index(0)
                    .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(2_000_000))
                        .steps(BigInteger.valueOf(800_000_000))
                        .build())
                    .build();
                return Result.success("OK").withValue(List.of(evalResult));
            })
            .completeAndWait(s -> log("  [unlock] " + s));
        assertOk(result, "unlock");
        log("Unlocked! Funds returned to " + wallet.baseAddress());
    }

    // ── Script loading ────────────────────────────────────────────────────────

    static void buildKycLockScript(String telPolicyId) throws Exception {
        Path plutusJson = Path.of(System.getProperty("user.dir"), "..", "aiken", "plutus.json");
        String compiledCode = loadCompiledCode(plutusJson, "kyc_lock.kyc_lock.spend");

        ListPlutusData params = ListPlutusData.of(
            BytesPlutusData.of(HexUtil.decodeHexString(telPolicyId)));
        String appliedCode = AikenScriptUtil.applyParamToScript(params, compiledCode);

        kycLockScript = (PlutusV3Script) PlutusBlueprintUtil
            .getPlutusScriptFromCompiledCode(appliedCode, PlutusVersion.v3);
        kycLockScriptAddress = AddressProvider.getEntAddress(kycLockScript, Networks.testnet()).toBech32();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static Utxo findFirstUtxo(String addr) throws Exception {
        var page = backend.getUtxoService().getUtxos(addr, 100, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty())
            return page.getValue().get(0);
        return null;
    }

    static Utxo findUtxoByRef(String txHash, int index) throws Exception {
        var result = backend.getUtxoService().getTxOutput(txHash, index);
        if (result.isSuccessful() && result.getValue() != null)
            return result.getValue();
        return null;
    }

    static String loadCompiledCode(Path blueprintPath, String title) throws Exception {
        String json = Files.readString(blueprintPath);
        Matcher m = Pattern.compile(
            "\"title\"\\s*:\\s*\"" + Pattern.quote(title) + "\"" +
            "[\\s\\S]*?\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        if (!m.find()) throw new RuntimeException("Validator not found in blueprint: " + title);
        return m.group(1);
    }

    static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new RuntimeException("Missing environment variable: " + key);
        return v;
    }

    static void waitChain(int sec, String ctx) throws InterruptedException {
        log("  waiting " + sec + "s for '" + ctx + "'..."); Thread.sleep(sec * 1_000L);
    }

    static void assertOk(Result<?> r, String label) {
        if (!r.isSuccessful()) throw new RuntimeException("FAILED [" + label + "]: " + r.getResponse());
        log("  OK " + label + " => " + r.getValue());
    }

    /** Off-chain Ed25519 signature verification using JDK EdDSA (Java 15+). */
    static boolean verifyEd25519(byte[] publicKey32, byte[] message, byte[] signature64) {
        try {
            // Convert raw 32-byte public key to JDK EdECPublicKey
            // The raw key is little-endian y-coordinate with high bit = x parity
            byte[] reversed = new byte[32];
            for (int i = 0; i < 32; i++) reversed[i] = publicKey32[31 - i];
            boolean xOdd = (reversed[0] & 0x80) != 0;
            reversed[0] &= 0x7F;
            BigInteger y = new BigInteger(1, reversed);
            EdECPoint point = new EdECPoint(xOdd, y);
            EdECPublicKeySpec spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, point);
            PublicKey pub = KeyFactory.getInstance("EdDSA").generatePublic(spec);
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature64);
        } catch (Exception e) {
            log("Ed25519 verify error: " + e.getMessage());
            return false;
        }
    }

    static void step(String s) { System.out.println("\n── " + s + " " + "─".repeat(Math.max(0, 60 - s.length()))); }
    static void banner(String s) { System.out.println("\n" + "═".repeat(60) + "\n  " + s + "\n" + "═".repeat(60) + "\n"); }
    static void log(String s) { System.out.println("  " + s); }
}
