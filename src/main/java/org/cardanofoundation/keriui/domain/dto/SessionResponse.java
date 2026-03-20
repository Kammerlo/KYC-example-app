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

    /** Transaction hash of the Allow List Add tx. Present only after the user has joined. */
    private final String allowListTxHash;
}
