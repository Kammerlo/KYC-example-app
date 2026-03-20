package org.cardanofoundation.keriui.domain.dto;

/**
 * Response returned after a TEL Init transaction is built.
 * The frontend uses {@code txCbor} to sign and submit the transaction,
 * then calls the register endpoint with {@code bootTxHash} and {@code bootIndex}.
 */
public record TelInitResponse(
        String txCbor,
        String scriptAddress,
        String policyId,
        String bootTxHash,
        int bootIndex) {}
