package com.flowpay.idempotency;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_requests", schema = "flowpay")
public class IdempotencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "transaction_id", unique = true)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false,
            length = 16, updatable = false)
    private IdempotencyOperation operationType;

    @Column(name = "idempotency_key", nullable = false,
            length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false,
            length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Short responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRequest() {
    }

    public void complete(UUID transactionId, String responseBody, Instant now) {
        finish(IdempotencyStatus.COMPLETED, Objects.requireNonNull(transactionId), (short) 201, responseBody, now);
    }

    public void reject(UUID transactionId, short responseStatus, String responseBody, Instant now) {
        if (responseStatus < 400 || responseStatus > 499) {
            throw new IllegalArgumentException("Only business rejections may be cached.");
        }
        finish(IdempotencyStatus.REJECTED, transactionId, responseStatus, responseBody, now);
    }

    private void finish(IdempotencyStatus status, UUID transactionId, short responseStatus, String responseBody, Instant now) {
        if (this.status != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("Idempotency request is already final.");
        }
        Objects.requireNonNull(responseBody, "responseBody must not be null");
        Objects.requireNonNull(now, "now must not be null");
        this.status = status;
        this.transactionId = transactionId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public IdempotencyOperation getOperationType() {
        return operationType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Short getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

