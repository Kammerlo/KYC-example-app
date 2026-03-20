package org.cardanofoundation.keriui.domain.dto;

/**
 * Response returned by GET /api/keri/oobi — the KERI OOBI URL of this agent's identifier.
 */
public record OobiResponse(String oobi) {}
