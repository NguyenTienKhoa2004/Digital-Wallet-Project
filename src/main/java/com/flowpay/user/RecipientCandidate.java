package com.flowpay.user;

import java.util.UUID;

public record RecipientCandidate(
		UUID walletId,
		String username,
		String email,
		String currency) {
}
