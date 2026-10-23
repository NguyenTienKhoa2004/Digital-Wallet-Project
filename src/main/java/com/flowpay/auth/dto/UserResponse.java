package com.flowpay.auth.dto;

import java.util.UUID;

import com.flowpay.user.User;

public record UserResponse(UUID userId, String username, String email, String role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
	}

}
