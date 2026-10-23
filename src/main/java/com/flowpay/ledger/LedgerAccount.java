package com.flowpay.ledger;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_accounts", schema = "flowpay")
public class LedgerAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "account_id")
	private UUID id;

	@Column(name = "account_code", nullable = false, unique = true, length = 128, updatable = false)
	private String accountCode;

	@Column(name = "wallet_id", unique = true, updatable = false)
	private UUID walletId;

	@Column(nullable = false, length = 32, updatable = false)
	private String purpose;

	@Column(name = "accounting_type", nullable = false, length = 16, updatable = false)
	private String accountingType;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerAccount() {
	}

	public static LedgerAccount forWallet(UUID walletId, Instant now) {
		LedgerAccount account = new LedgerAccount();
		account.accountCode = "WALLET_" + walletId;
		account.walletId = walletId;
		account.purpose = "WALLET";
		account.accountingType = "LIABILITY";
		account.currency = "USD";
		account.createdAt = now;
		return account;
	}

}
