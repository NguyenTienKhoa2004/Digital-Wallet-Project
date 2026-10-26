package com.flowpay.wallet;

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
@Table(name = "wallets", schema = "flowpay")
public class Wallet {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "wallet_id")
	private UUID id;

	@Column(name = "user_id", nullable = false, unique = true, updatable = false)
	private UUID userId;

	@Column(nullable = false)
	private long balance;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private WalletStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Wallet() {
	}

	public static Wallet create(UUID userId, Instant now) {
		Wallet wallet = new Wallet();
		wallet.userId = userId;
		wallet.balance = 0;
		wallet.currency = "USD";
		wallet.status = WalletStatus.ACTIVE;
		wallet.createdAt = now;
		wallet.updatedAt = now;
		return wallet;
	}

	public void debit(long amount, Instant now) {
		validateAmount(amount);
		Objects.requireNonNull(now, "now must not be null");

		if (balance < amount) {
			throw new IllegalStateException("Insufficient balance.");
		}

		balance -= amount;
		updatedAt = now;
	}
	public void credit(long amount, Instant now) {
		validateAmount(amount);
		Objects.requireNonNull(now, "now must not be null");

		balance = Math.addExact(balance, amount);
		updatedAt = now;
	}

	private void validateAmount(long amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("Amount must be positive.");
		}
	}

	public UUID getId() {
		return id;
	}
	public UUID getUserId() {
		return userId;
	}

	public long getBalance() {
		return balance;
	}

	public String getCurrency() {
		return currency;
	}

	public WalletStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
