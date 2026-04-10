package org.cardanofoundation.keriui.domain.dto;

/**
 * Response containing the signed KYC proof data.
 * The payload is: user_pkh(28) || role(1) || valid_until(8) = 37 bytes.
 * valid_until is a POSIX timestamp in milliseconds (big-endian).
 * Signed by the trusted entity's Ed25519 key (which is registered in the TEL).
 */
public record KycProofResponse(
        String payloadHex,
        String signatureHex,
        String entityVkeyHex,
        String entityTelUtxoRef,
        String telPolicyId,
        long validUntilPosixMs,
        int role,
        String roleName
) {}
