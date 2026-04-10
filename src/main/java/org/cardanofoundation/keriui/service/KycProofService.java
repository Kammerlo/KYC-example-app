package org.cardanofoundation.keriui.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.keriui.domain.Role;
import org.cardanofoundation.keriui.domain.dto.KycProofResponse;
import org.cardanofoundation.keriui.domain.entity.TeNodeEntity;
import org.cardanofoundation.keriui.domain.repository.TeNodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class KycProofService {

    private final TeNodeRepository teNodeRepository;
    private final TelService telService;
    private final String signingMnemonic;

    public KycProofService(
            TeNodeRepository teNodeRepository,
            TelService telService,
            @Value("${keri.signing-mnemonic}") String signingMnemonic) {
        this.teNodeRepository = teNodeRepository;
        this.telService = telService;
        this.signingMnemonic = signingMnemonic;
    }

    /**
     * Generates a signed KYC proof for the given user address and role.
     * <p>
     * The payload is 29 bytes: user_pkh(28) || role(1).
     * Signed with the configured entity's Ed25519 private key.
     * Validity is enforced on-chain via the transaction TTL.
     */
    public KycProofResponse generateProof(String userAddress, int roleValue) {
        // Derive entity account from mnemonic
        Account entityAccount = Account.createFromMnemonic(Networks.preview(), signingMnemonic);
        byte[] entityVkeyRaw = entityAccount.publicKeyBytes();
        String entityVkeyHex = HexUtil.encodeHexString(entityVkeyRaw);
        String entityPkh = HexUtil.encodeHexString(
                entityAccount.hdKeyPair().getPublicKey().getKeyHash());

        // Verify entity is in TEL
        TeNodeEntity teNode = teNodeRepository.findAll().stream()
                .filter(n -> n.getVkey().equals(entityVkeyHex))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Signing entity (pkh=" + entityPkh + ") is not in the Trusted Entity List"));

        // Extract user PKH from Cardano address
        Address addr = new Address(userAddress);
        byte[] userPkhBytes = addr.getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalStateException("Cannot extract PKH from address: " + userAddress));

        // Build 29-byte payload: user_pkh(28) || role(1)
        byte[] payload = new byte[29];
        System.arraycopy(userPkhBytes, 0, payload, 0, 28);
        payload[28] = (byte) roleValue;

        // Sign with entity's Ed25519 private key
        byte[] signature = new EdDSASigningProvider()
                .signExtended(payload, entityAccount.hdKeyPair().getPrivateKey().getKeyData());

        String telUtxoRef = teNode.getTxHash() + "#" + teNode.getOutputIndex();
        Role role = Role.fromValue(roleValue);

        log.info("Generated KYC proof: entityPkh={} userPkh={} role={}",
                entityPkh, HexUtil.encodeHexString(userPkhBytes), role.name());

        return new KycProofResponse(
                HexUtil.encodeHexString(payload),
                HexUtil.encodeHexString(signature),
                entityVkeyHex,
                telUtxoRef,
                telService.getTePolicyId(),
                roleValue,
                role.name()
        );
    }
}
