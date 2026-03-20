package org.cardanofoundation.keriui.yaci;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoStorageImpl;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.entity.AllowListNodeEntity;
import org.cardanofoundation.keriui.domain.entity.TeNodeEntity;
import org.cardanofoundation.keriui.domain.repository.AllowListNodeRepository;
import org.cardanofoundation.keriui.domain.repository.TeNodeRepository;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class CustomUtxoStorage extends UtxoStorageImpl {

    private final TeNodeRepository teNodeRepository;
    private final AllowListNodeRepository allowListNodeRepository;
    private volatile String telPolicyId = "";
    private volatile String telScriptAddress = "";
    private volatile String allowListPolicyId = "";
    private volatile String allowListScriptAddress = "";

    public CustomUtxoStorage(UtxoRepository utxoRepository, TxInputRepository spentOutputRepository, DSLContext dsl, UtxoCache utxoCache, PlatformTransactionManager transactionManager, TeNodeRepository teNodeRepository, AllowListNodeRepository allowListNodeRepository) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, transactionManager);
        this.teNodeRepository = teNodeRepository;
        this.allowListNodeRepository = allowListNodeRepository;
    }

    @Override
    public void saveSpent(List<TxInput> txInputs) {
        super.saveSpent(txInputs);
        List<String> spentTxHashes = txInputs.stream().map(TxInput::getTxHash).toList();
        teNodeRepository.deleteAllByTxHashIn(spentTxHashes);
        allowListNodeRepository.deleteAllByTxHashIn(spentTxHashes);
    }

    public void setTelPolicyId(String telPolicyId) {
        this.telPolicyId = telPolicyId;
        log.info("TEL policy id set to {}", telPolicyId);
    }

    public void setTelScriptAddress(String telScriptAddress) {
        this.telScriptAddress = telScriptAddress;
        log.info("TEL script address set to {}", telScriptAddress);
    }

    public void setAllowListPolicyId(String allowListPolicyId) {
        this.allowListPolicyId = allowListPolicyId;
        log.info("Allow list policy id set to {}", allowListPolicyId);
    }

    public void setAllowListScriptAddress(String allowListScriptAddress) {
        this.allowListScriptAddress = allowListScriptAddress;
        log.info("Allow list script address set to {}", allowListScriptAddress);
    }

    @Override
    public void saveUnspent(List<AddressUtxo> addressUtxos) {
        super.saveUnspent(addressUtxos);
        parseTrustedEntityList(addressUtxos);
        parseAllowList(addressUtxos);
    }

    private void parseTrustedEntityList(List<AddressUtxo> addressUtxos) {
        if (telPolicyId.isEmpty()) return;

        String scriptAddr = this.telScriptAddress;
        List<TeNodeEntity> toSave = new ArrayList<>();

        addressUtxos.stream()
                .filter(utxo -> scriptAddr.isEmpty() || scriptAddr.equals(utxo.getOwnerAddr()))
                .filter(utxo -> utxo.getInlineDatum() != null)
                .filter(utxo -> utxo.getAmounts().stream().anyMatch(amt -> telPolicyId.equals(amt.getPolicyId())))
                .forEach(utxo -> {
                    Optional<Amt> notLovelaceAmt = utxo.getAmounts().stream()
                            .filter(amt -> !amt.getAssetName().equals("lovelace"))
                            .findFirst();
                    if (notLovelaceAmt.isEmpty()) return;

                    DataItem di = CborSerializationUtil.deserialize(HexUtil.decodeHexString(utxo.getInlineDatum()));
                    try {
                        ConstrPlutusData constr = ConstrPlutusData.deserialize(di);
                        ListPlutusData data = constr.getData();
                        // TENode datum: [vkey(0), role(1), next(2)]
                        // role is a Plutus enum: Constr 0 [] = User, Constr 1 [] = Institutional, Constr 2 [] = VLei
                        BytesPlutusData vKey = (BytesPlutusData) data.getPlutusDataList().get(0);
                        ConstrPlutusData rolePd = (ConstrPlutusData) data.getPlutusDataList().get(1);
                        int role = (int) rolePd.getAlternative();
                        TeNodeEntity entity = TeNodeEntity.builder()
                                .vkey(HexUtil.encodeHexString(vKey.getValue()))
                                .role(role)
                                .txHash(utxo.getTxHash())
                                .outputIndex(utxo.getOutputIndex())
                                .nodePolicyId(notLovelaceAmt.get().getPolicyId())
                                .build();
                        log.info("Saving TE Node: {} role={}", entity.getNodePolicyId(), role);
                        toSave.add(entity);
                    } catch (CborDeserializationException e) {
                        log.error("Failed to parse TE Node datum for tx={}", utxo.getTxHash(), e);
                    }
                });

        if (!toSave.isEmpty()) {
            teNodeRepository.saveAll(toSave);
        }
    }

    private void parseAllowList(List<AddressUtxo> addressUtxos) {
        if (allowListPolicyId.isEmpty()) return;

        String scriptAddr = this.allowListScriptAddress;
        List<AllowListNodeEntity> toSave = new ArrayList<>();

        addressUtxos.stream()
                .filter(utxo -> scriptAddr.isEmpty() || scriptAddr.equals(utxo.getOwnerAddr()))
                .filter(utxo -> utxo.getInlineDatum() != null)
                .filter(utxo -> utxo.getAmounts().stream().anyMatch(amt -> allowListPolicyId.equals(amt.getPolicyId())))
                .forEach(utxo -> {
                    Optional<Amt> notLovelaceAmt = utxo.getAmounts().stream()
                            .filter(amt -> !amt.getAssetName().equals("lovelace"))
                            .findFirst();
                    if (notLovelaceAmt.isEmpty()) return;

                    DataItem di = CborSerializationUtil.deserialize(HexUtil.decodeHexString(utxo.getInlineDatum()));
                    try {
                        ConstrPlutusData constr = ConstrPlutusData.deserialize(di);
                        ListPlutusData data = constr.getData();
                        // WLNode datum: [pkh(0), active(1), role(2), next(3)]
                        // role is a Plutus enum: Constr 0 [] = User, Constr 1 [] = Institutional, Constr 2 [] = VLei
                        BytesPlutusData pkhPlutusData = (BytesPlutusData) data.getPlutusDataList().get(0);
                        ConstrPlutusData activeConstr = (ConstrPlutusData) data.getPlutusDataList().get(1);
                        boolean active = activeConstr.getAlternative() != 0;
                        ConstrPlutusData rolePd = (ConstrPlutusData) data.getPlutusDataList().get(2);
                        int role = (int) rolePd.getAlternative();
                        // Use the full unit (policyId + assetName) as PK so multiple nodes in the same WL are unique
                        AllowListNodeEntity allowListNodeEntity = AllowListNodeEntity.builder()
                                .pkh(HexUtil.encodeHexString(pkhPlutusData.getValue()))
                                .active(active)
                                .role(role)
                                .nodePolicyId(notLovelaceAmt.get().getPolicyId())
                                .txHash(utxo.getTxHash())
                                .outputIndex(utxo.getOutputIndex())
                                .build();
                        log.info("Saving allow list entry for pkh: {} role={}", HexUtil.encodeHexString(pkhPlutusData.getValue()), role);
                        toSave.add(allowListNodeEntity);
                    } catch (CborDeserializationException e) {
                        log.error("Failed to parse AllowList datum for tx={}", utxo.getTxHash(), e);
                    }
                });

        if (!toSave.isEmpty()) {
            allowListNodeRepository.saveAll(toSave);
        }
    }
}