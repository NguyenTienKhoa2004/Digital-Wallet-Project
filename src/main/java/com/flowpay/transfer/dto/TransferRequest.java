package com.flowpay.transfer.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Positive;

import jakarta.validation.constraints.Size;

public record TransferRequest(
    @NotNull UUID destinationWalletId,
    @Positive long amount,
    @Size(max = 255) String description

) {
}

