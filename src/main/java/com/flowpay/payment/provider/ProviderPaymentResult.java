package com.flowpay.payment.provider;

import java.util.UUID;

public record ProviderPaymentResult(
        UUID paymentId,
        String providerPaymentId,
        long amount,
        String currency,
        PaymentProviderStatus status
) {
}
