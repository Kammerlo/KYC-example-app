package org.cardanofoundation.keriui.domain.dto;

/**
 * Response containing the signed KYC proof data.
 * The payload is: user_pkh(28) || role(1) || valid_until(8) = 37 bytes.
 * Signed by the trusted entity's Ed25519 key (which is registered in the TEL).
 */
public record KycProofResponse(
        String payloadHex,
        String signatureHex,
        String entityVkeyHex,
        String entityTelUtxoRef,
        long validUntilPosixMs,
        String validUntilHuman,
        int role,
        String roleName
) {}
