package org.cardanofoundation.keriui.domain.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Holds the resolved KERI identifier details for this agent.
 * Created as a Spring bean on startup by {@code KeriConfig.createIdentifier()}.
 */
@Getter
@Builder
public class IdentifierConfig {

    /** KERI AID prefix (self-certifying identifier). */
    private String prefix;

    /** Human-readable alias used to look up this identifier in the KERI agent. */
    private String name;

    /** Role of this identifier (e.g. "issuer"). */
    private String role;
}
