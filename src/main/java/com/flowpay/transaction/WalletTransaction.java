package com.flowpay.transaction;

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
@Table(name = "transactions", schema = "flowpay")
public class WalletTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "transaction_id")
	private UUID id;

	@Column(nullable = false, unique = true, length = 128, updatable = false)
	private String reference;

	@Column(name = "initiated_by", nullable = false, updatable = false)
	private UUID initiatedByUserId;

	@Column(name = "source_wallet_id", updatable = false)
	private UUID sourceWalletId;

	@Column(name = "destination_wallet_id", nullable = false, updatable = false)
	private UUID destinationWalletId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16, updatable = false)
	private TransactionType type;

	@Column(nullable = false, updatable = false)
	private long amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TransactionStatus status;

	@Column(updatable = false)
	private String description;

	@Column(name = "failure_code", length = 128)
	private String failureCode;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WalletTransaction() {
	}

	public static WalletTransaction pendingTransfer(String reference, UUID initiatedByUserId,
			UUID sourceWalletId, UUID destinationWalletId, long amount, String description, Instant now) {
		Objects.requireNonNull(reference, "reference must not be null");
		Objects.requireNonNull(initiatedByUserId, "initiatedByUserId must not be null");
		Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
		Objects.requireNonNull(destinationWalletId, "destinationWalletId must not be null");
		Objects.requireNonNull(now, "now must not be null");
		if (amount <= 0) {
			throw new IllegalArgumentException("amount must be greater than zero");
		}
		if (sourceWalletId.equals(destinationWalletId)) {
			throw new IllegalArgumentException("source and destination wallets must be different");
		}

		WalletTransaction transaction = new WalletTransaction();
		transaction.reference = reference;
		transaction.initiatedByUserId = initiatedByUserId;
		transaction.sourceWalletId = sourceWalletId;
		transaction.destinationWalletId = destinationWalletId;
		transaction.type = TransactionType.TRANSFER;
		transaction.amount = amount;
		transaction.currency = "USD";
		transaction.status = TransactionStatus.PENDING;
		transaction.description = description;
		transaction.createdAt = now;
		transaction.updatedAt = now;
		return transaction;
	}

	public void markSuccess(Instant now) {
		requirePending();
		Objects.requireNonNull(now, "now must not be null");
		status = TransactionStatus.SUCCESS;
		completedAt = now;
		updatedAt = now;
	}

	public void markFailed(String failureCode, Instant now) {
		requirePending();
		Objects.requireNonNull(failureCode, "failureCode must not be null");
		Objects.requireNonNull(now, "now must not be null");
		if (failureCode.isBlank() || failureCode.length() > 128) {
			throw new IllegalArgumentException("failureCode must contain 1 to 128 characters.");
		}
		this.failureCode = failureCode;
		status = TransactionStatus.FAILED;
		completedAt = now;
		updatedAt = now;
	}

	private void requirePending() {
		if (status != TransactionStatus.PENDING) {
			throw new IllegalStateException("Only a pending transaction can change status.");
		}
	}

	public UUID getId() {
		return id;
	}

	public String getReference() {
		return reference;
	}

	public UUID getInitiatedByUserId() {
		return initiatedByUserId;
	}

	public UUID getSourceWalletId() {
		return sourceWalletId;
	}

	public UUID getDestinationWalletId() {
		return destinationWalletId;
	}

	public TransactionType getType() {
		return type;
	}

	public long getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public String getDescription() {
		return description;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
