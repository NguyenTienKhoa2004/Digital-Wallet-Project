package com.flowpay.topup.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TopUpRequest(
        @Positive long amount,
        @Size(max = 255) String description
) {
}
