package org.cardanofoundation.keriui.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response returned by POST /api/auth/role.
 * {@code teRole} and {@code teRoleName} are only present for "entity" and "issuer" wallets.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleDetectionResponse(
        String role,
        String pkh,
        Integer teRole,
        String teRoleName) {}
