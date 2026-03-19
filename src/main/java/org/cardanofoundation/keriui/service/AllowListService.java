package org.cardanofoundation.keriui.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.entity.AllowListConfigEntity;
import org.cardanofoundation.keriui.domain.entity.AllowListNodeEntity;
import org.cardanofoundation.keriui.domain.repository.AllowListConfigRepository;
import org.cardanofoundation.keriui.domain.repository.AllowListNodeRepository;
import org.cardanofoundation.keriui.yaci.CustomUtxoStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AllowListService {

    private static final String WL_PREFIX_HEX = HexUtil.encodeHexString("WHL".getBytes());
    private static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    private static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    private final AllowListConfigRepository allowListConfigRepository;
    private final AllowListNodeRepository allowListNodeRepository;
    private final TelService telService;
    private final CustomUtxoStorage customUtxoStorage;

    @Value("${cardano.blockfrost.url}")
    private String blockfrostUrl;

    @Value("${cardano.blockfrost.key}")
    private String blockfrostKey;

    private PlutusV3Script wlScript;
    private String wlPolicyId;
    private String wlScriptAddress;

    public AllowListService(AllowListConfigRepository allowListConfigRepository,
                            AllowListNodeRepository allowListNodeRepository,
                            TelService telService,
                            CustomUtxoStorage customUtxoStorage) {
        this.allowListConfigRepository = allowListConfigRepository;
        this.allowListNodeRepository = allowListNodeRepository;
        this.telService = telService;
        this.customUtxoStorage = customUtxoStorage;
    }

    @PostConstruct
    public void restoreFromDb() {
        Optional<AllowListConfigEntity> cfg = allowListConfigRepository.findById(1);
        if (cfg.isEmpty()) {
            log.info("AllowList not yet initialised");
            return;
        }
        AllowListConfigEntity c = cfg.get();
        try {
            buildWlScript(c.getTePolicyId(), c.getBootTxHash(), c.getBootIndex());
            customUtxoStorage.setAllowListPolicyId(c.getPolicyId());
            log.info("AllowList config restored: {}", c.getPolicyId());
        } catch (Exception e) {
            log.error("Failed to restore AllowList config from DB", e);
        }
    }

    // ── Transaction building ──────────────────────────────────────────────────

    public AllowListBuildResult buildInitTx(String entityAddress) throws Exception {
        requireTelInitialised();
        requireNotInitialised();

        String tePolicyId = telService.getTePolicyId();
        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        String entityPkh = extractPkh(entityAddress);
        Utxo bootUtxo = pickBootUtxo(backend, entityAddress);
        String bootTxHash = bootUtxo.getTxHash();
        int bootIndex = bootUtxo.getOutputIndex();

        buildWlScript(tePolicyId, bootTxHash, bootIndex);

        // Head datum: empty-PKH sentinel, active=true, next=Empty
        ConstrPlutusData headDatum = wlNodeDatum("", true, null);
        PlutusData redeemer = constr(0);

        Utxo teEntityUtxo = findEntityTelUtxo(backend, entityPkh);

        ScriptTx scriptTx = new ScriptTx()
                .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX, 1), redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + WL_PREFIX_HEX, 1L)),
                        headDatum)
                .readFrom(teEntityUtxo.getTxHash(), teEntityUtxo.getOutputIndex());

        Utxo bootUtxoRef = findUtxoByRef(backend, entityAddress, bootTxHash, bootIndex);
        Tx tx = new Tx()
                .collectFrom(List.of(bootUtxoRef))
                .from(entityAddress);

        Transaction unsignedTx = builder.compose(scriptTx, tx)
                .feePayer(entityAddress)
                .withRequiredSigners(HexUtil.decodeHexString(entityPkh))
                .build();

        return new AllowListBuildResult(unsignedTx.serializeToHex(), wlScriptAddress, wlPolicyId, bootTxHash, bootIndex);
    }

    public AllowListConfigEntity registerConfig(String bootTxHash, int bootIndex) throws Exception {
        requireTelInitialised();
        String tePolicyId = telService.getTePolicyId();
        buildWlScript(tePolicyId, bootTxHash, bootIndex);

        AllowListConfigEntity config = AllowListConfigEntity.builder()
                .id(1)
                .scriptAddress(wlScriptAddress)
                .policyId(wlPolicyId)
                .bootTxHash(bootTxHash)
                .bootIndex(bootIndex)
                .tePolicyId(tePolicyId)
                .build();
        allowListConfigRepository.save(config);
        customUtxoStorage.setAllowListPolicyId(wlPolicyId);
        log.info("AllowList config registered at {}", wlScriptAddress);
        return config;
    }

    /** Build an unsigned AllowList Add transaction (no endorsement — entity signature suffices). */
    public String buildAddTx(String userPkh, String entityAddress) throws Exception {
        requireInitialised();

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        String entityPkh = extractPkh(entityAddress);
        Utxo teEntityUtxo = findEntityTelUtxo(backend, entityPkh);

        Utxo coveringUtxo = findWlCoveringNode(backend, userPkh);
        WlDatumInfo coveringInfo = decodeWlDatum(coveringUtxo.getInlineDatum());
        String coveringTokenName = getCoveringWlTokenName(coveringUtxo);

        ConstrPlutusData coveringDatum = wlNodeDatum(coveringInfo.pkhHex(), coveringInfo.active(), coveringInfo.nextPkh());
        ConstrPlutusData updatedCovering = wlNodeDatum(coveringInfo.pkhHex(), coveringInfo.active(), userPkh);
        ConstrPlutusData userNode = wlNodeDatum(userPkh, true, coveringInfo.nextPkh());

        // Redeemer: constr(2, [userPkh, coveringNode])
        PlutusData redeemer = constr(2, bytes(userPkh), coveringDatum);

        log.info("buildAddTx: userPkh={} coveringToken={} entityPkh={}", userPkh, coveringTokenName, entityPkh);

        ScriptTx spendTx = new ScriptTx()
                .collectFrom(coveringUtxo, redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + coveringTokenName, 1L)),
                        updatedCovering)
                .readFrom(teEntityUtxo.getTxHash(), teEntityUtxo.getOutputIndex())
                .attachSpendingValidator(wlScript);

        ScriptTx mintTx = new ScriptTx()
                .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX + userPkh, 1), redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                        userNode);

        Tx tx = new Tx().from(entityAddress);

        Transaction unsignedTx = builder.compose(spendTx, mintTx, tx)
                .feePayer(entityAddress)
                .withRequiredSigners(HexUtil.decodeHexString(entityPkh))
                .build();

        return unsignedTx.serializeToHex();
    }

    public String buildSetActiveTx(String userPkh, boolean newActive, String entityAddress) throws Exception {
        requireInitialised();

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        String entityPkh = extractPkh(entityAddress);
        Utxo teEntityUtxo = findEntityTelUtxo(backend, entityPkh);

        Utxo userUtxo = findWlUtxoByToken(backend, WL_PREFIX_HEX + userPkh);
        WlDatumInfo currentInfo = decodeWlDatum(userUtxo.getInlineDatum());

        ConstrPlutusData updatedNode = wlNodeDatum(userPkh, newActive, currentInfo.nextPkh());
        PlutusData redeemer = constr(3, bytes(userPkh), constr(newActive ? 1 : 0));

        log.info("buildSetActiveTx: userPkh={} newActive={} entityPkh={}", userPkh, newActive, entityPkh);

        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(userUtxo, redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                        updatedNode)
                .readFrom(teEntityUtxo.getTxHash(), teEntityUtxo.getOutputIndex())
                .attachSpendingValidator(wlScript);

        Tx tx = new Tx().from(entityAddress);

        Transaction unsignedTx = builder.compose(scriptTx, tx)
                .feePayer(entityAddress)
                .withRequiredSigners(HexUtil.decodeHexString(entityPkh))
                .build();

        return unsignedTx.serializeToHex();
    }

    /**
     * Build an unsigned AllowList Add transaction with off-chain entity endorsement.
     *
     * Flow (matches KycE2ETest.wlAdd):
     *  1. Derive entity account from mnemonic; verify it exists in the TEL.
     *  2. Entity signs the user's PKH bytes off-chain (Ed25519).
     *  3. Build the WL Add transaction with the endorsement embedded in the redeemer.
     *  4. Return the unsigned CBOR — the user signs it with their CIP-30 wallet.
     */
    public String buildAddTxWithEndorsement(String userAddress, String signingMnemonic) throws Exception {
        requireInitialised();

        Account entityAccount = Account.createFromMnemonic(Networks.preview(), signingMnemonic);
        byte[] entityVkeyRaw = entityAccount.publicKeyBytes(); // 32 bytes
        String entityPkh = HexUtil.encodeHexString(
                entityAccount.hdKeyPair().getPublicKey().getKeyHash()); // 28-byte payment key hash

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        Utxo teEntityRef;
        try {
            teEntityRef = findEntityTelUtxo(backend, entityPkh);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Signing key (pkh=" + entityPkh + ") is not part of the Trusted Entity List");
        }

        String userPkh = extractPkh(userAddress);

        Utxo coveringUtxo = findWlCoveringNode(backend, userPkh);
        WlDatumInfo coveringInfo = decodeWlDatum(coveringUtxo.getInlineDatum());
        String coveringTokenName = getCoveringWlTokenName(coveringUtxo);

        ConstrPlutusData coveringDatum = wlNodeDatum(
                coveringInfo.pkhHex(), coveringInfo.active(), coveringInfo.nextPkh());
        ConstrPlutusData updatedCovering = wlNodeDatum(
                coveringInfo.pkhHex(), coveringInfo.active(), userPkh);
        ConstrPlutusData userNode = wlNodeDatum(userPkh, true, coveringInfo.nextPkh());

        // Entity signs user's PKH bytes off-chain — 64-byte Ed25519 signature
        byte[] userPkhBytes = HexUtil.decodeHexString(userPkh);
        byte[] entityEndorsement = new EdDSASigningProvider()
                .signExtended(userPkhBytes, entityAccount.hdKeyPair().getPrivateKey().getKeyData());

        log.info("buildAddTxWithEndorsement: userPkh={} entityPkh={}", userPkh, entityPkh);

        // Redeemer: constr(2, [userPkh, coveringNode, entityVkey32, entityEndorsement64])
        PlutusData redeemer = constr(2,
                bytes(userPkh),
                coveringDatum,
                BytesPlutusData.of(entityVkeyRaw),
                BytesPlutusData.of(entityEndorsement));

        ScriptTx spendTx = new ScriptTx()
                .collectFrom(coveringUtxo, redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + coveringTokenName, 1L)),
                        updatedCovering)
                .readFrom(teEntityRef.getTxHash(), teEntityRef.getOutputIndex())
                .attachSpendingValidator(wlScript);

        ScriptTx mintTx = new ScriptTx()
                .mintAsset(wlScript, mintAsset(WL_PREFIX_HEX + userPkh, 1), redeemer)
                .payToContract(wlScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(wlPolicyId + WL_PREFIX_HEX + userPkh, 1L)),
                        userNode);

        // User is the fee payer and the only required signer
        Tx tx = new Tx().from(userAddress);

        Transaction unsignedTx = builder.compose(spendTx, mintTx, tx)
                .feePayer(userAddress)
                .withRequiredSigners(HexUtil.decodeHexString(userPkh))
                .build();

        return unsignedTx.serializeToHex();
    }

    public String assembleAndSubmit(String unsignedTxCbor, String witnessCbor) throws Exception {
        byte[] txBytes = HexUtil.decodeHexString(unsignedTxCbor);
        java.util.List<co.nstant.in.cbor.model.DataItem> txItems =
                co.nstant.in.cbor.CborDecoder.decode(txBytes);
        co.nstant.in.cbor.model.Array txArray =
                (co.nstant.in.cbor.model.Array) txItems.getFirst();
        co.nstant.in.cbor.model.Map txWitnessMap =
                (co.nstant.in.cbor.model.Map) txArray.getDataItems().get(1);

        byte[] wsBytes = HexUtil.decodeHexString(witnessCbor);
        java.util.List<co.nstant.in.cbor.model.DataItem> wsItems =
                co.nstant.in.cbor.CborDecoder.decode(wsBytes);
        co.nstant.in.cbor.model.Map walletWitnessMap =
                (co.nstant.in.cbor.model.Map) wsItems.getFirst();

        for (co.nstant.in.cbor.model.DataItem key : walletWitnessMap.getKeys()) {
            txWitnessMap.put(key, walletWitnessMap.get(key));
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        new co.nstant.in.cbor.CborEncoder(baos).encode(txArray);
        byte[] signedTxBytes = baos.toByteArray();

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        var result = backend.getTransactionService().submitTransaction(signedTxBytes);
        if (!result.isSuccessful()) {
            throw new RuntimeException("Submit failed: " + result.getResponse());
        }
        return result.getValue();
    }

    /** All AllowList members (excludes the head sentinel with empty PKH). */
    public List<AllowListNodeEntity> getMembers() {
        return allowListNodeRepository.findAll().stream()
                .filter(n -> n.getPkh() != null && !n.getPkh().isEmpty())
                .toList();
    }

    public boolean isInitialised() {
        return allowListConfigRepository.existsById(1);
    }

    public Optional<AllowListConfigEntity> getConfig() {
        return allowListConfigRepository.findById(1);
    }

    // ── Script building ───────────────────────────────────────────────────────

    private void buildWlScript(String tePolicyId, String bootTxHash, int bootIndex) throws Exception {
        String code = loadCompiledCode("whitelist_mutation.whitelist.mint");
        String applied = AikenScriptUtil.applyParamToScript(
                ListPlutusData.of(constr(0, bytes(tePolicyId), outputRef(bootTxHash, bootIndex))), code);
        this.wlScript = (PlutusV3Script) PlutusBlueprintUtil
                .getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3);
        this.wlPolicyId = wlScript.getPolicyId();
        this.wlScriptAddress = AddressProvider.getEntAddress(wlScript, Networks.preview()).toBech32();
    }

    private String loadCompiledCode(String title) throws Exception {
        InputStream is = getClass().getResourceAsStream("/plutus.json");
        if (is == null) throw new RuntimeException("plutus.json not found on classpath");
        String json = new String(is.readAllBytes());
        Matcher m = Pattern.compile(
                "\"title\"\\s*:\\s*\"" + Pattern.quote(title) + "\"" +
                "[\\s\\S]*?\"compiledCode\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(json);
        if (!m.find()) throw new RuntimeException("Validator not found: " + title);
        return m.group(1);
    }

    // ── Linked list traversal ─────────────────────────────────────────────────

    private Utxo findWlCoveringNode(BackendService backend, String userPkh) throws Exception {
        Utxo current = findWlUtxoByToken(backend, WL_PREFIX_HEX);
        while (true) {
            WlDatumInfo info = decodeWlDatum(current.getInlineDatum());
            if (info.nextPkh() == null || userPkh.compareTo(info.nextPkh()) <= 0) {
                return current;
            }
            current = findWlUtxoByToken(backend, WL_PREFIX_HEX + info.nextPkh());
        }
    }

    private Utxo findWlUtxoByToken(BackendService backend, String tokenNameHex) throws Exception {
        String unit = wlPolicyId + tokenNameHex;
        var page = backend.getUtxoService().getUtxos(wlScriptAddress, 100, 1);
        // Blockfrost returns 404 (non-successful) when address has no UTxOs — treat as empty list
        List<Utxo> utxos = (page.isSuccessful() && page.getValue() != null) ? page.getValue() : List.of();
        return utxos.stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "AllowList UTxO with token " + tokenNameHex + " not found at " + wlScriptAddress
                        + (utxos.isEmpty() ? " (address has no UTxOs — transaction may not be confirmed yet)" : "")));
    }

    private String getCoveringWlTokenName(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(a -> a.getUnit().startsWith(wlPolicyId) && !a.getUnit().equals("lovelace"))
                .findFirst()
                .map(a -> a.getUnit().substring(wlPolicyId.length()))
                .orElseThrow(() -> new RuntimeException("No AllowList token found on covering UTxO"));
    }

    /** Find the entity's TEL node UTxO to use as a reference input. Falls back to head (issuer case). */
    private Utxo findEntityTelUtxo(BackendService backend, String entityPkh) throws Exception {
        String teScriptAddress = telService.getTeScriptAddress();
        String tePolicyId = telService.getTePolicyId();
        var page = backend.getUtxoService().getUtxos(teScriptAddress, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null)
            throw new RuntimeException("Cannot fetch TEL UTxOs at " + teScriptAddress);

        String entityUnit = tePolicyId + TE_PREFIX_HEX + entityPkh;
        var entityUtxo = page.getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> entityUnit.equals(a.getUnit())))
                .findFirst();
        if (entityUtxo.isPresent()) return entityUtxo.get();

        // Issuer case: head node token is just TE_PREFIX_HEX
        String headUnit = tePolicyId + TE_PREFIX_HEX;
        return page.getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> headUnit.equals(a.getUnit())))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("TEL node not found for entityPkh: " + entityPkh));
    }

    private Utxo pickBootUtxo(BackendService backend, String address) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 10, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty())
            return page.getValue().get(0);
        throw new RuntimeException("No UTxOs at " + address);
    }

    private Utxo findUtxoByRef(BackendService backend, String address, String txHash, int index) throws Exception {
        var page = backend.getUtxoService().getUtxos(address, 100, 1);
        if (page.isSuccessful() && page.getValue() != null) {
            for (Utxo u : page.getValue()) {
                if (u.getTxHash().equals(txHash) && u.getOutputIndex() == index) return u;
            }
        }
        throw new RuntimeException("Bootstrap UTxO not found: " + txHash.substring(0, 12) + "#" + index);
    }

    // ── Datum helpers ─────────────────────────────────────────────────────────

    private WlDatumInfo decodeWlDatum(String inlineDatumHex) {
        try {
            PlutusData raw = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatumHex));
            ConstrPlutusData c = (ConstrPlutusData) raw;
            List<PlutusData> fields = c.getData().getPlutusDataList();
            String pkhHex = HexUtil.encodeHexString(((BytesPlutusData) fields.get(0)).getValue());
            ConstrPlutusData activeConstr = (ConstrPlutusData) fields.get(1);
            boolean active = activeConstr.getAlternative() == 1;
            ConstrPlutusData nextConstr = (ConstrPlutusData) fields.get(2);
            String nextPkh = nextConstr.getAlternative() == 1 ? null
                    : HexUtil.encodeHexString(
                            ((BytesPlutusData) nextConstr.getData().getPlutusDataList().get(0)).getValue());
            return new WlDatumInfo(pkhHex, active, nextPkh);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode WLNode datum", e);
        }
    }

    private record WlDatumInfo(String pkhHex, boolean active, String nextPkh) {}

    // ── PlutusData helpers ────────────────────────────────────────────────────

    /** WLNode = constr(0, [pkh_bytes, Bool, NodeKey]) */
    private ConstrPlutusData wlNodeDatum(String pkhHex, boolean active, String nextPkh) {
        return (ConstrPlutusData) constr(0, bytes(pkhHex), constr(active ? 1 : 0), nodeKey(nextPkh));
    }

    private PlutusData nodeKey(String pkh) {
        return pkh == null ? constr(1) : constr(0, bytes(pkh));
    }

    private PlutusData constr(int alt) {
        return ConstrPlutusData.builder().alternative(alt).data(ListPlutusData.of()).build();
    }

    private PlutusData constr(int alt, PlutusData... fields) {
        return ConstrPlutusData.builder().alternative(alt).data(ListPlutusData.of(fields)).build();
    }

    private PlutusData bytes(String hex) {
        if (hex == null || hex.isEmpty()) return BytesPlutusData.of(new byte[0]);
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private PlutusData outputRef(String txHashHex, int outputIndex) {
        return constr(0, bytes(txHashHex), BigIntPlutusData.of(outputIndex));
    }

    private Asset mintAsset(String tokenNameHex, long qty) {
        return Asset.builder()
                .name("0x" + tokenNameHex)
                .value(BigInteger.valueOf(qty))
                .build();
    }

    private String extractPkh(String bech32Address) {
        byte[] pkhBytes = new Address(bech32Address).getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalArgumentException("Cannot extract PKH from address: " + bech32Address));
        return HexUtil.encodeHexString(pkhBytes);
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    private void requireTelInitialised() {
        if (!telService.isInitialised()) throw new IllegalStateException("TEL not initialised — initialize the TEL first");
    }

    private void requireInitialised() {
        if (wlScript == null) throw new IllegalStateException("AllowList not initialised");
    }

    private void requireNotInitialised() {
        if (isInitialised()) throw new IllegalStateException("AllowList already initialised");
    }

    // ── Result types ─────────────────────────────────────────────────────────

    public record AllowListBuildResult(String txCbor, String scriptAddress, String policyId, String bootTxHash, int bootIndex) {}
}