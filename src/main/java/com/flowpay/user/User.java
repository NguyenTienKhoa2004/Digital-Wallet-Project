package com.flowpay.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users", schema = "flowpay")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "user_id")
	private UUID id;

	@Column(nullable = false, length = 255)
	private String username;

	@Column(nullable = false, length = 320)
	private String email;

	@Column(nullable = false, length = 16)
	private String role;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected User() {
	}

	public static User register(String username, String email, String passwordHash) {
		User user = new User();
		user.username = username;
		user.email = email;
		user.passwordHash = passwordHash;
		user.role = "USER";
		user.createdAt = Instant.now();
		return user;
	}

	public UUID getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getEmail() {
		return email;
	}

	public String getRole() {
		return role;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

}
