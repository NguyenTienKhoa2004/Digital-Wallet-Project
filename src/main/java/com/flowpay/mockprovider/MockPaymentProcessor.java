package com.flowpay.mockprovider;

import com.flowpay.payment.provider.PaymentProviderStatus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("mock-provider")
public class MockPaymentProcessor {

    private final MockPaymentRepository repository;
    private final PaymentProviderStatus finalStatus;
    private final long delayMs;
    private final boolean enabled;

    public MockPaymentProcessor(
            MockPaymentRepository repository,
            @Value("${mock-provider.final-status:SUCCESS}")
            PaymentProviderStatus finalStatus,
            @Value("${mock-provider.processing-delay-ms:5000}")
            long delayMs,
            @Value("${flowpay.background-jobs-enabled:true}") boolean enabled) {

        if (finalStatus != PaymentProviderStatus.SUCCESS
                && finalStatus != PaymentProviderStatus.FAILED) {
            throw new IllegalArgumentException(
                    "Final status must be SUCCESS or FAILED.");
        }

        if (delayMs <= 0) {
            throw new IllegalArgumentException(
                    "Processing delay must be positive.");
        }

        this.repository = repository;
        this.finalStatus = finalStatus;
        this.delayMs = delayMs;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString =
                    "${mock-provider.processing-interval-ms:1000}")
    public void scheduledProcessing() {
        if (enabled) {
            processPendingPayments();
        }
    }

    public void processPendingPayments() {
        repository.completeDuePayments(finalStatus, delayMs);
    }
}
