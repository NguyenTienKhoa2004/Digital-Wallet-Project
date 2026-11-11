package com.flowpay.transfer.dto;

import java.util.UUID;

public record RecipientResponse(
        UUID walletId,
        String username,
        String maskedEmail,
        String currency
) {
}
