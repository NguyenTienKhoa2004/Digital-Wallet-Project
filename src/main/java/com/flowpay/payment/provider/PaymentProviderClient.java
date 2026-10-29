package com.flowpay.payment.provider;

import java.util.UUID;

public interface PaymentProviderClient {

    ProviderPaymentResult createPayment(
            CreateProviderPaymentRequest request);

    ProviderPaymentResult findByPaymentId(UUID paymentId);
}
