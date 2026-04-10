package org.cardanofoundation.keriui.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.Role;
import org.cardanofoundation.keriui.domain.dto.RoleInfo;
import org.cardanofoundation.keriui.domain.dto.TelInitResponse;
import org.cardanofoundation.keriui.domain.entity.TeNodeEntity;
import org.cardanofoundation.keriui.domain.entity.TelConfigEntity;
import org.cardanofoundation.keriui.domain.repository.TelConfigRepository;
import org.cardanofoundation.keriui.domain.repository.TeNodeRepository;
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
public class TelService {

    private static final String TE_PREFIX_HEX = HexUtil.encodeHexString("TEL".getBytes());
    private static final BigInteger NODE_MIN_LOVELACE = BigInteger.valueOf(2_000_000);

    private final TelConfigRepository telConfigRepository;
    private final TeNodeRepository teNodeRepository;
    private final CustomUtxoStorage customUtxoStorage;

    @Value("${cardano.blockfrost.url}")
    private String blockfrostUrl;

    @Value("${cardano.blockfrost.key}")
    private String blockfrostKey;

    /** 28-byte payment key hash (PKH) — used for script params, required signers, role detection */
    @Value("${cardano.issuer.pkh:}")
    private String issuerPkhHex;

    /** 32-byte raw Ed25519 public key — stored in TEL datum vkey field */
    @Value("${cardano.issuer.vkey:}")
    private String issuerVkeyHex;

    // In-memory script state (rebuilt from DB on startup)
    private PlutusV3Script teScript;
    private String tePolicyId;
    private String teScriptAddress;

    public TelService(TelConfigRepository telConfigRepository,
                      TeNodeRepository teNodeRepository,
                      CustomUtxoStorage customUtxoStorage) {
        this.telConfigRepository = telConfigRepository;
        this.teNodeRepository = teNodeRepository;
        this.customUtxoStorage = customUtxoStorage;
    }

    @PostConstruct
    public void restoreFromDb() {
        Optional<TelConfigEntity> cfg = telConfigRepository.findById(1);
        if (cfg.isEmpty()) {
            log.info("TEL not yet initialised");
            return;
        }
        TelConfigEntity c = cfg.get();
        try {
            buildTeScript(c.getIssuerVkey(), c.getBootTxHash(), c.getBootIndex());
            customUtxoStorage.setTelPolicyId(c.getPolicyId());
            customUtxoStorage.setTelScriptAddress(c.getScriptAddress());
            log.info("TEL config restored: {}", c.getPolicyId());
        } catch (Exception e) {
            log.error("Failed to restore TEL config from DB", e);
        }
    }

    // ── Role detection ────────────────────────────────────────────────────────

    public String getIssuerPkh() {
        requireVkey();
        return issuerPkhHex;
    }

    public String getTeScriptAddress() {
        requireInitialised();
        return teScriptAddress;
    }

    public String getTePolicyId() {
        requireInitialised();
        return tePolicyId;
    }

    /** Returns the role name for a given wallet payment-key-hash (28-byte hex). */
    public String roleForPkh(String walletPkh) {
        return roleInfoForPkh(walletPkh).roleName();
    }

    /** Returns full role info for a given wallet payment-key-hash (28-byte hex). */
    public RoleInfo roleInfoForPkh(String walletPkh) {
        log.debug("roleInfoForPkh: wallet={} issuerPkh={}", walletPkh, issuerPkhHex);
        if (!issuerPkhHex.isBlank() && walletPkh.equalsIgnoreCase(issuerPkhHex)) {
            return new RoleInfo("issuer", Role.VLEI);
        }
        Optional<TeNodeEntity> teNode = teNodeRepository.findAll().stream()
                .filter(n -> walletPkh.equalsIgnoreCase(pkhFrom(n.getVkey())))
                .findFirst();
        if (teNode.isPresent()) {
            Role teRole = teNode.get().getRole() != null
                    ? Role.fromValue(teNode.get().getRole())
                    : Role.USER;
            return new RoleInfo("entity", teRole);
        }
        return new RoleInfo("user", null);
    }

    // ── Transaction building (unsigned – frontend signs via CIP-30) ───────────

    /**
     * Build an unsigned TEL Init transaction.
     * The backend picks the boot UTxO from the issuer's address via the Blockfrost API,
     * builds the Plutus transaction body, and returns the unsigned CBOR hex.
     * The frontend must sign it with the CIP-30 wallet and submit.
     */
    public TelInitResponse buildInitTx(String issuerAddress) throws Exception {
        requireVkey();
        requireNotInitialised();

        String issuerPkh = issuerPkhHex;
        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        Utxo bootUtxo = pickBootUtxo(backend, issuerAddress);
        String bootTxHash = bootUtxo.getTxHash();
        int bootIndex = bootUtxo.getOutputIndex();

        buildTeScript(issuerPkh, bootTxHash, bootIndex);

        // Datum vkey field must be the raw 32-byte Ed25519 public key (script hashes it to verify PKH)
        // Head/issuer node always gets vLEI role (2)
        ConstrPlutusData headDatum = teNodeDatum(issuerVkeyHex, 2, null);
        PlutusData redeemer = constr(0);

        ScriptTx scriptTx = new ScriptTx()
                .mintAsset(teScript, mintAsset(TE_PREFIX_HEX, 1), redeemer)
                .payToContract(teScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(tePolicyId + TE_PREFIX_HEX, 1L)),
                        headDatum);

        Tx tx = new Tx()
                .collectFrom(List.of(bootUtxo))
                .from(issuerAddress);

        Transaction unsignedTx = builder.compose(scriptTx, tx)
                .feePayer(issuerAddress)
                .withRequiredSigners(HexUtil.decodeHexString(issuerPkh))
                .build();

        return new TelInitResponse(
                unsignedTx.serializeToHex(),
                teScriptAddress,
                tePolicyId,
                bootTxHash,
                bootIndex);
    }

    /**
     * Called by the frontend after it has signed and submitted the TEL Init tx.
     * Stores the TEL config so the chain watcher starts indexing members.
     */
    public TelConfigEntity registerConfig(String bootTxHash, int bootIndex) throws Exception {
        requireVkey();
        buildTeScript(issuerPkhHex, bootTxHash, bootIndex);

        TelConfigEntity config = TelConfigEntity.builder()
                .id(1)
                .scriptAddress(teScriptAddress)
                .policyId(tePolicyId)
                .bootTxHash(bootTxHash)
                .bootIndex(bootIndex)
                .issuerVkey(issuerPkhHex)
                .build();
        telConfigRepository.save(config);
        customUtxoStorage.setTelPolicyId(tePolicyId);
        customUtxoStorage.setTelScriptAddress(teScriptAddress);
        log.info("TEL config registered at {}", teScriptAddress);
        return config;
    }

    /**
     * Build an unsigned TEL Add transaction.
     * Frontend signs and submits; members appear once the chain watcher indexes the block.
     */
    public String buildAddTx(String entityVkeyHex, int role, String issuerAddress) throws Exception {
        requireInitialised();
        requireVkey();

        String entityPkh = pkhFrom(entityVkeyHex);
        String issuerPkh = issuerPkhHex;

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        Utxo coveringUtxo = findCoveringNode(backend, entityPkh);
        TeDatumInfo coveringInfo = decodeTeDatum(coveringUtxo.getInlineDatum());

        ConstrPlutusData coveringDatum   = teNodeDatum(coveringInfo.vkeyHex(), coveringInfo.role(), coveringInfo.nextPkh());
        ConstrPlutusData updatedCovering = teNodeDatum(coveringInfo.vkeyHex(), coveringInfo.role(), entityPkh);
        ConstrPlutusData entityNode      = teNodeDatum(entityVkeyHex, role, coveringInfo.nextPkh());

        // The covering node may be the head ("TEL") or any entity node ("TEL"+pkh) —
        // extract its token name dynamically so the output preserves the right token.
        String coveringTokenName = getCoveringTokenName(coveringUtxo);

        // TEAdd redeemer: Constr 2 [new_vkey, new_role_enum, covering_node]
        PlutusData redeemer = constr(2, bytes(entityVkeyHex), constr(role), coveringDatum);

        ScriptTx spendTx = new ScriptTx()
                .collectFrom(coveringUtxo, redeemer)
                .payToContract(teScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(tePolicyId + coveringTokenName, 1L)),
                        updatedCovering)
                .attachSpendingValidator(teScript);

        ScriptTx mintTx = new ScriptTx()
                .mintAsset(teScript, mintAsset(TE_PREFIX_HEX + entityPkh, 1), redeemer)
                .payToContract(teScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(tePolicyId + TE_PREFIX_HEX + entityPkh, 1L)),
                        entityNode);

        Tx tx = new Tx().from(issuerAddress);

        Transaction unsignedTx = builder.compose(spendTx, mintTx, tx)
                .feePayer(issuerAddress)
                .withRequiredSigners(HexUtil.decodeHexString(issuerPkh))
                .build();

        return unsignedTx.serializeToHex();
    }

    /**
     * Merges the wallet's witness set into the unsigned transaction and submits it.
     * CIP-30 signTx returns only a transaction_witness_set, not a full transaction.
     */
    /**
     * Merges the wallet witness set into the unsigned transaction at the raw CBOR level
     * and submits. This avoids bloxbean deserialization issues with vkey witnesses.
     *
     * A Cardano transaction is a CBOR array: [body, witness_set, is_valid, metadata].
     * The witness_set is a map where key 0 = vkey witnesses. We add the wallet's entries
     * to the map already present in the unsigned tx (which holds script/redeemer witnesses).
     */
    public String assembleAndSubmit(String unsignedTxCbor, String witnessCbor) throws Exception {
        // Decode the unsigned transaction (array of 4)
        byte[] txBytes = HexUtil.decodeHexString(unsignedTxCbor);
        java.util.List<co.nstant.in.cbor.model.DataItem> txItems =
                co.nstant.in.cbor.CborDecoder.decode(txBytes);
        co.nstant.in.cbor.model.Array txArray =
                (co.nstant.in.cbor.model.Array) txItems.getFirst();
        co.nstant.in.cbor.model.Map txWitnessMap =
                (co.nstant.in.cbor.model.Map) txArray.getDataItems().get(1);

        // Decode the wallet witness set map
        byte[] wsBytes = HexUtil.decodeHexString(witnessCbor);
        java.util.List<co.nstant.in.cbor.model.DataItem> wsItems =
                co.nstant.in.cbor.CborDecoder.decode(wsBytes);
        co.nstant.in.cbor.model.Map walletWitnessMap =
                (co.nstant.in.cbor.model.Map) wsItems.getFirst();

        // Merge all witness map entries from wallet into the tx witness map
        for (co.nstant.in.cbor.model.DataItem key : walletWitnessMap.getKeys()) {
            txWitnessMap.put(key, walletWitnessMap.get(key));
        }

        // Re-encode the assembled transaction
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

    /**
     * Build an unsigned TEL Remove transaction.
     * Removes the entity identified by entityVkeyHex from the linked list.
     * The covering node (whose next pointer currently points to the entity) is updated
     * to skip the removed entity, and the entity's token is burned.
     */
    public String buildRemoveTx(String entityVkeyHex, String issuerAddress) throws Exception {
        requireInitialised();
        requireVkey();

        String removePkh = pkhFrom(entityVkeyHex);
        String issuerPkh = issuerPkhHex;

        BackendService backend = new BFBackendService(blockfrostUrl, blockfrostKey);
        QuickTxBuilder builder = new QuickTxBuilder(backend);

        // Find covering node (the one whose next == removePkh)
        Utxo coveringUtxo = findCoveringNodeForRemoval(backend, removePkh);
        TeDatumInfo coveringInfo = decodeTeDatum(coveringUtxo.getInlineDatum());

        // Find the node being removed
        Utxo removedUtxo = findUtxoByToken(backend, TE_PREFIX_HEX + removePkh);
        TeDatumInfo removedInfo = decodeTeDatum(removedUtxo.getInlineDatum());

        // Redeemer: TERemove { remove_vkey, covering_node }
        ConstrPlutusData coveringDatum = teNodeDatum(coveringInfo.vkeyHex(), coveringInfo.role(), coveringInfo.nextPkh());
        PlutusData redeemer = constr(3, bytes(entityVkeyHex), coveringDatum);

        // Updated covering: next restored to what the removed node was pointing to
        ConstrPlutusData updatedCovering = teNodeDatum(coveringInfo.vkeyHex(), coveringInfo.role(), removedInfo.nextPkh());

        // Determine the covering node's token name
        String coveringTokenName = getCoveringTokenName(coveringUtxo);

        // Debug: log all values to help diagnose script evaluation failures
        log.info("buildRemoveTx: removePkh={} coveringUtxo={}#{} removedUtxo={}#{}",
                removePkh,
                coveringUtxo.getTxHash(), coveringUtxo.getOutputIndex(),
                removedUtxo.getTxHash(), removedUtxo.getOutputIndex());
        log.info("buildRemoveTx: coveringInfo vkey={} next={}", coveringInfo.vkeyHex(), coveringInfo.nextPkh());
        log.info("buildRemoveTx: removedInfo  vkey={} next={}", removedInfo.vkeyHex(), removedInfo.nextPkh());
        log.info("buildRemoveTx: redeemer={}", redeemer);
        log.info("buildRemoveTx: updatedCovering={}", updatedCovering);
        log.info("buildRemoveTx: coveringTokenName={}", coveringTokenName);
        log.info("buildRemoveTx: burnTokenName={}", TE_PREFIX_HEX + removePkh);

        // Single ScriptTx with both collectFrom calls — matches KycE2ETest.telRemove() exactly.
        ScriptTx scriptTx = new ScriptTx()
                .collectFrom(coveringUtxo, redeemer)
                .collectFrom(removedUtxo, redeemer)
                .mintAsset(teScript, mintAsset(TE_PREFIX_HEX + removePkh, -1), redeemer)
                .payToContract(teScriptAddress,
                        List.of(Amount.lovelace(NODE_MIN_LOVELACE),
                                Amount.asset(tePolicyId + coveringTokenName, 1L)),
                        updatedCovering)
                .attachSpendingValidator(teScript);

        Tx tx = new Tx().from(issuerAddress);

        Transaction unsignedTx = builder.compose(scriptTx, tx)
                .feePayer(issuerAddress)
                .withRequiredSigners(HexUtil.decodeHexString(issuerPkh))
                .build();

        return unsignedTx.serializeToHex();
    }

    /** Returns entity nodes  */
    public List<TeNodeEntity> getMembers() {
        return teNodeRepository.findAll().stream()
                .toList();
    }

    public boolean isInitialised() {
        return telConfigRepository.existsById(1);
    }

    public Optional<TelConfigEntity> getConfig() {
        return telConfigRepository.findById(1);
    }

    // ── Script building ───────────────────────────────────────────────────────

    private void buildTeScript(String issuerPkh, String bootTxHash, int bootIndex) throws Exception {
        String code = loadCompiledCode("trusted_entity.trusted_entity.mint");
        String applied = AikenScriptUtil.applyParamToScript(
                ListPlutusData.of(bytes(issuerPkh), outputRef(bootTxHash, bootIndex)), code);
        this.teScript = (PlutusV3Script) PlutusBlueprintUtil
                .getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3);
        this.tePolicyId = teScript.getPolicyId();
        this.teScriptAddress = AddressProvider.getEntAddress(teScript, Networks.preview()).toBech32();
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

    /** Find the node whose next pointer equals removePkh (for TERemove). */
    private Utxo findCoveringNodeForRemoval(BackendService backend, String removePkh) throws Exception {
        Utxo current = findUtxoByToken(backend, TE_PREFIX_HEX);
        while (true) {
            TeDatumInfo info = decodeTeDatum(current.getInlineDatum());
            if (removePkh.equalsIgnoreCase(info.nextPkh())) {
                return current;
            }
            if (info.nextPkh() == null) {
                throw new RuntimeException("Entity not found in TEL linked list: " + removePkh);
            }
            current = findUtxoByToken(backend, TE_PREFIX_HEX + info.nextPkh());
        }
    }

    /** Extract the TEL token name (without policy ID prefix) from a UTxO's amounts. */
    private String getCoveringTokenName(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(a -> a.getUnit().startsWith(tePolicyId) && !a.getUnit().equals("lovelace"))
                .findFirst()
                .map(a -> a.getUnit().substring(tePolicyId.length()))
                .orElseThrow(() -> new RuntimeException("No TEL token found on covering UTxO"));
    }

    private Utxo findCoveringNode(BackendService backend, String entityPkh) throws Exception {
        Utxo current = findUtxoByToken(backend, TE_PREFIX_HEX);
        while (true) {
            TeDatumInfo info = decodeTeDatum(current.getInlineDatum());
            if (info.nextPkh() == null || entityPkh.compareTo(info.nextPkh()) <= 0) {
                return current;
            }
            current = findUtxoByToken(backend, TE_PREFIX_HEX + info.nextPkh());
        }
    }

    private Utxo findUtxoByToken(BackendService backend, String tokenNameHex) throws Exception {
        String unit = tePolicyId + tokenNameHex;
        var page = backend.getUtxoService().getUtxos(teScriptAddress, 100, 1);
        if (!page.isSuccessful() || page.getValue() == null)
            throw new RuntimeException("Cannot fetch UTxOs at " + teScriptAddress);
        return page.getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("UTxO with token " + tokenNameHex + " not found"));
    }

    private Utxo pickBootUtxo(BackendService backend, String address) throws Exception {
        log.info("pickBootUtxo: looking for UTxOs at {}", address);
        var page = backend.getUtxoService().getUtxos(address, 100, 1);
        if (page.isSuccessful() && page.getValue() != null && !page.getValue().isEmpty()) {
            log.info("pickBootUtxo: found {} UTxOs at {}", page.getValue().size(), address);
            return page.getValue().get(0);
        }
        log.warn("pickBootUtxo: Blockfrost returned code={} for address={} — " +
                        "this often means the address has never appeared in a transaction. " +
                        "Ensure the frontend sends a used address (not a fresh change address).",
                page.code(), address);
        throw new RuntimeException("No UTxOs found at " + address
                + " (HTTP " + page.code() + "). The wallet may be sending a fresh change address "
                + "that Blockfrost doesn't know about. Try using a different address with funds.");
    }

    // ── Datum helpers ─────────────────────────────────────────────────────────

    private TeDatumInfo decodeTeDatum(String inlineDatumHex) {
        try {
            PlutusData raw = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatumHex));
            ConstrPlutusData c = (ConstrPlutusData) raw;
            List<PlutusData> fields = c.getData().getPlutusDataList();
            // TENode datum: [vkey(0), role(1), next(2)]
            // role is now a Plutus enum: Constr 0 [] = User, Constr 1 [] = Institutional, Constr 2 [] = VLei
            String vkeyHex = HexUtil.encodeHexString(((BytesPlutusData) fields.get(0)).getValue());
            int role = (int) ((ConstrPlutusData) fields.get(1)).getAlternative();
            ConstrPlutusData nextConstr = (ConstrPlutusData) fields.get(2);
            String nextPkh = nextConstr.getAlternative() == 1 ? null
                    : HexUtil.encodeHexString(
                            ((BytesPlutusData) nextConstr.getData().getPlutusDataList().get(0)).getValue());
            return new TeDatumInfo(vkeyHex, role, nextPkh);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode TENode datum", e);
        }
    }

    private record TeDatumInfo(String vkeyHex, int role, String nextPkh) {}

    // ── PlutusData helpers ────────────────────────────────────────────────────

    /**
     * Builds a TENode datum: Constr 0 [vkey_bytes, role_constr, next_key]
     * The role is serialised as a Plutus enum: Constr &lt;alt&gt; [] where alt = role value (0/1/2).
     */
    private ConstrPlutusData teNodeDatum(String vkeyHex, int role, String nextPkh) {
        return (ConstrPlutusData) constr(0, bytes(vkeyHex), constr(role), nodeKey(nextPkh));
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

    // ── Crypto helpers ────────────────────────────────────────────────────────

    /** blake2b_224(vkeyHex) → pkh hex */
    public String pkhFrom(String vkeyHex) {
        return HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(vkeyHex)));
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    private void requireInitialised() {
        if (teScript == null) throw new IllegalStateException("TEL not initialised");
    }

    private void requireNotInitialised() {
        if (isInitialised()) throw new IllegalStateException("TEL already initialised");
    }

    private void requireVkey() {
        if (issuerPkhHex == null || issuerPkhHex.isBlank())
            throw new IllegalStateException("cardano.issuer.pkh is not configured");
        if (issuerVkeyHex == null || issuerVkeyHex.isBlank())
            throw new IllegalStateException("cardano.issuer.vkey is not configured");
    }

}
