package com.flowpay.transfer.dto;

import java.time.Instant;

import java.util.UUID;

import com.flowpay.transaction.WalletTransaction;

public record TransferResponse(
        UUID transactionId,
        String reference,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amount,
        String currency,
        String status,
        String description,
        Instant createdAt,
        Instant completedAt,
        String failureCode
) {
    public static TransferResponse from(WalletTransaction transaction) {
        return new TransferResponse(transaction.getId(), transaction.getReference(),
                transaction.getSourceWalletId(), transaction.getDestinationWalletId(),
                transaction.getAmount(), transaction.getCurrency(), transaction.getStatus().name(),
                transaction.getDescription(), transaction.getCreatedAt(), transaction.getCompletedAt(),
                transaction.getFailureCode());
    }
}
