package com.flowpay.wallet.dto;

import java.time.Instant;
import java.util.UUID;

import com.flowpay.wallet.Wallet;
import com.flowpay.wallet.WalletStatus;

public record WalletResponse(UUID walletId, long balance, String currency,
		WalletStatus status, Instant createdAt, Instant updatedAt) {

	public static WalletResponse from(Wallet wallet) {
		return new WalletResponse(wallet.getId(), wallet.getBalance(), wallet.getCurrency(),
				wallet.getStatus(), wallet.getCreatedAt(), wallet.getUpdatedAt());
	}

}
