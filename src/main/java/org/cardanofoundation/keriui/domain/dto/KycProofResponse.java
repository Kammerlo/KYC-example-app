package org.cardanofoundation.keriui.domain.dto;

/**
 * Response containing the signed KYC proof data.
 * The payload is: user_pkh(28) || role(1) = 29 bytes.
 * Signed by the trusted entity's Ed25519 key (which is registered in the TEL).
 * Validity is enforced on-chain via the transaction TTL.
 */
public record KycProofResponse(
        String payloadHex,
        String signatureHex,
        String entityVkeyHex,
        String entityTelUtxoRef,
        String telPolicyId,
        int role,
        String roleName
) {}
