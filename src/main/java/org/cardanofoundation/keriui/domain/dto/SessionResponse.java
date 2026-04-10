package org.cardanofoundation.keriui.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Response returned by GET /api/keri/session.
 * Optional fields are omitted from JSON when null.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResponse {

    private final boolean exists;
    private final Boolean hasCredential;
    private final Boolean hasCardanoAddress;

    /** Dynamic ACDC credential attributes. Present only when a credential has been received. */
    private final Map<String, Object> attributes;

    /** Numeric role value (0=User, 1=Institutional, 2=vLEI). Present only when credential exists. */
    private final Integer credentialRole;

    /** Human-readable role name. Present only when credential exists. */
    private final String credentialRoleName;

    /** The user's Cardano address. Present only when the wallet has been connected. */
    private final String cardanoAddress;

    /** Hex-encoded 37-byte KYC proof payload. Present only after proof generation. */
    private final String kycProofPayload;

    /** Hex-encoded 64-byte Ed25519 signature. Present only after proof generation. */
    private final String kycProofSignature;

    /** Hex-encoded 32-byte entity verification key. Present only after proof generation. */
    private final String kycProofEntityVkey;

    /** TEL UTxO reference (txHash#index). Present only after proof generation. */
    private final String kycProofTelUtxoRef;

    /** Valid-until in POSIX milliseconds. Present only after proof generation. */
    private final Long kycProofValidUntil;

    /** ISO-8601 human-readable valid-until. Present only after proof generation. */
    private final String kycProofValidUntilHuman;
}
