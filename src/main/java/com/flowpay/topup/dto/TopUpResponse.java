package com.flowpay.topup.dto;

import java.time.Instant;
import java.util.UUID;

import com.flowpay.transaction.WalletTransaction;

public record TopUpResponse(
        UUID transactionId,
        String reference,
        UUID destinationWalletId,
        long amount,
        String currency,
        String status,
        String description,
        Instant createdAt,
        Instant completedAt,
        String failureCode
) {

    public static TopUpResponse from(WalletTransaction transaction) {
        return new TopUpResponse(
                transaction.getId(),
                transaction.getReference(),
                transaction.getDestinationWalletId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                transaction.getDescription(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt(),
                transaction.getFailureCode());
    }
}
