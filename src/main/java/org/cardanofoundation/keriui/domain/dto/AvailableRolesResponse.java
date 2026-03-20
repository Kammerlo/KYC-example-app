package org.cardanofoundation.keriui.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response returned by GET /api/keri/available-roles.
 * {@code maxRoleName} is absent when the signing entity is not in the TEL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailableRolesResponse(
        List<RoleOption> availableRoles,
        int maxRole,
        String maxRoleName) {}
