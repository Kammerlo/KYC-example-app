package org.cardanofoundation.keriui.domain.dto;

/**
 * A credential role that the signing entity is authorised to endorse.
 */
public record RoleOption(String role, int roleValue, String label) {}
