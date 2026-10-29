package com.flowpay.payment.provider;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateProviderPaymentRequest(
        @NotNull UUID paymentId,
        @Positive long amount,
        @NotNull @Pattern(regexp = "USD") String currency
) {
}
