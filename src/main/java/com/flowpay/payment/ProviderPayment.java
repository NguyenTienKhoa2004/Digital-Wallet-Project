package com.flowpay.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "provider_payments", schema = "flowpay")
public class ProviderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "payment_id")
    private UUID id;

    @Column(name = "transaction_id", nullable = false,
            unique = true, updatable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 64, updatable = false)
    private String provider;

    @Column(name = "provider_payment_id", length = 255)
    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_status", nullable = false, length = 16)
    private ProviderPaymentStatus providerStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderPayment() {
    }

    /**
     * Returns the payment ID generated on persistence, used as the provider request reference and idempotency key.
     */
    public UUID getId() {
        return id;
    }

    public static ProviderPayment forMock(UUID transactionId, Instant now) {
        Objects.requireNonNull(transactionId, "transactionId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        ProviderPayment payment = new ProviderPayment();
        payment.transactionId = transactionId;
        payment.provider = "MOCK";
        payment.providerStatus = ProviderPaymentStatus.UNKNOWN;
        payment.attemptCount = 0;
        payment.nextCheckAt = now;
        payment.createdAt = now;
        payment.updatedAt = now;

        return payment;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public ProviderPaymentStatus getProviderStatus() {
        return providerStatus;
    }

    public void observe(String externalId, ProviderPaymentStatus status, Instant now) {
        providerPaymentId = externalId;
        providerStatus = status;
        lastCheckedAt = now;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void scheduleRetry(String errorCode, Instant nextCheck, Instant now) {
        lastErrorCode = errorCode;
        nextCheckAt = nextCheck;
        updatedAt = now;
    }

    public void finish(Instant now) {
        nextCheckAt = null;
        updatedAt = now;
    }
}
