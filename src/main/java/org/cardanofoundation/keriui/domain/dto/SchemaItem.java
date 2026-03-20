package org.cardanofoundation.keriui.domain.dto;

/**
 * A single credential schema entry returned by GET /api/keri/schemas.
 */
public record SchemaItem(String role, int roleValue, String label, String said) {}
