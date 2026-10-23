package com.flowpay.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
