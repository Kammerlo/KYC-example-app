package org.cardanofoundation.keriui.domain.dto;

/**
 * A single AllowList member returned by GET /api/allowlist/members.
 */
public record WlMemberResponse(
        String pkh,
        boolean active,
        String nodePolicyId,
        String txHash,
        int outputIndex,
        int role,
        String roleName) {}
