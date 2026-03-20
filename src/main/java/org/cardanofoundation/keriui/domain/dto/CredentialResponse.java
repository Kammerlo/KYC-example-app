package org.cardanofoundation.keriui.domain.dto;

import java.util.Map;

/**
 * Response returned by GET /api/keri/credential/present.
 * Contains the role the credential was issued for and all dynamic ACDC attributes.
 */
public record CredentialResponse(
        String role,
        int roleValue,
        String label,
        Map<String, Object> attributes) {}
