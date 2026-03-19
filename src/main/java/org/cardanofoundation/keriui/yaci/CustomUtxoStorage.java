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

import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class CustomUtxoStorage extends UtxoStorageImpl {

    private final TeNodeRepository teNodeRepository;
    private final AllowListNodeRepository allowListNodeRepository;
    private volatile String telPolicyId = ""; // set by TelService after TEL init
    private volatile String allowListPolicyId = ""; // set by TelService after TEL init

    public CustomUtxoStorage(UtxoRepository utxoRepository, TxInputRepository spentOutputRepository, DSLContext dsl, UtxoCache utxoCache, PlatformTransactionManager transactionManager, TeNodeRepository teNodeRepository, AllowListNodeRepository allowListNodeRepository) {
        super(utxoRepository, spentOutputRepository, dsl, utxoCache, transactionManager);
        this.teNodeRepository = teNodeRepository;
        this.allowListNodeRepository = allowListNodeRepository;
    }

    @Override
    public void saveSpent(List<TxInput> txInputs) {
        List<String> spentTxHashes = txInputs.stream().map(TxInput::getTxHash).toList();
        teNodeRepository.deleteAllByTxHashIn(spentTxHashes);
        allowListNodeRepository.deleteAllByTxHashIn(spentTxHashes);
    }

    public void setTelPolicyId(String telPolicyId) {
        this.telPolicyId = telPolicyId;
        log.info("TEL script address set to {}", telPolicyId);
    }

    public void setAllowListPolicyId(String allowListPolicyId) {
        log.info("Allow list policy id set to {}", allowListPolicyId);
        this.allowListPolicyId = allowListPolicyId;
    }

    @Override
    public void saveUnspent(List<AddressUtxo> addressUtxos) {
        parseTrustedEntityList(addressUtxos);
        parseAllowList(addressUtxos);
    }

    private void parseTrustedEntityList(List<AddressUtxo> addressUtxos) {
        addressUtxos.stream().filter(utxo -> utxo.getAmounts().stream().map(Amt::getPolicyId).toList().contains(telPolicyId)).forEach(utxo -> {
            String inlineDatum = utxo.getInlineDatum();
            DataItem di = CborSerializationUtil.deserialize(HexUtil.decodeHexString(inlineDatum));
            Optional<Amt> notLovelaceAmt = utxo.getAmounts().stream().filter(amt -> !amt.getAssetName().equals("lovelace")).findFirst();
            if(notLovelaceAmt.isEmpty()) {
                return;
            }
            try {
                ConstrPlutusData constr = ConstrPlutusData.deserialize(di);
                ListPlutusData data = constr.getData();
                BytesPlutusData vKey = (BytesPlutusData) data.getPlutusDataList().getFirst();
                TeNodeEntity entity = TeNodeEntity.builder()
                        .vkey(HexUtil.encodeHexString(vKey.getValue()))
                        .txHash(utxo.getTxHash())
                        .outputIndex(utxo.getOutputIndex())
                        .nodePolicyId(notLovelaceAmt.get().getPolicyId())
                        .build();
                log.info("Saving TE Node: {}", entity.getNodePolicyId());
                teNodeRepository.save(entity);
            } catch (CborDeserializationException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void parseAllowList(List<AddressUtxo> addressUtxos) {
        addressUtxos.stream().filter(utxo -> utxo.getAmounts().stream().map(Amt::getPolicyId).toList().contains(allowListPolicyId)).forEach(utxo -> {
            String inlineDatum = utxo.getInlineDatum();
            DataItem di = CborSerializationUtil.deserialize(HexUtil.decodeHexString(inlineDatum));
            Optional<Amt> notLovelaceAmt = utxo.getAmounts().stream().filter(amt -> !amt.getAssetName().equals("lovelace")).findFirst();
            if(notLovelaceAmt.isEmpty()) {
                return;
            }
            try {
                ConstrPlutusData constr = ConstrPlutusData.deserialize(di);
                ListPlutusData data = constr.getData();
                BytesPlutusData pkhPlutusData = (BytesPlutusData) data.getPlutusDataList().getFirst();
                ConstrPlutusData activeConstr = (ConstrPlutusData) data.getPlutusDataList().get(1);
                boolean active = activeConstr.getAlternative() == 0 ? false : true;
                // Use the full unit (policyId + assetName) as PK so multiple nodes in the same WL are unique
                AllowListNodeEntity allowListNodeEntity = AllowListNodeEntity.builder()
                        .pkh(HexUtil.encodeHexString(pkhPlutusData.getValue()))
                        .active(active)
                        .nodePolicyId(notLovelaceAmt.get().getPolicyId())
                        .txHash(utxo.getTxHash())
                        .outputIndex(utxo.getOutputIndex())
                        .build();
                allowListNodeRepository.save(allowListNodeEntity);
                log.info("Saving allow list entry for vKey: {}", HexUtil.encodeHexString(pkhPlutusData.getValue()));
            } catch (CborDeserializationException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
