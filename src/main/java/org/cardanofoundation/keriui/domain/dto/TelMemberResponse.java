package org.cardanofoundation.keriui.domain.dto;

/**
 * A single Trusted Entity List member returned by GET /api/tel/members.
 */
public record TelMemberResponse(
        String vkey,
        String pkh,
        String txHash,
        int outputIndex,
        String nodePolicyId,
        int role,
        String roleName) {}
