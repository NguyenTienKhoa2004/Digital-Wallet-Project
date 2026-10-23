package com.flowpay.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.flowpay.auth.dto.LoginRequest;
import com.flowpay.auth.dto.LoginResponse;
import com.flowpay.auth.dto.RegisterRequest;
import com.flowpay.auth.dto.UserResponse;
import com.flowpay.common.exception.ApiException;
import com.flowpay.user.User;
import com.flowpay.user.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final String dummyPasswordHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		// Perform password verification even when an email has no account/password.
		this.dummyPasswordHash = passwordEncoder.encode("unused-dummy-password");
	}

	@Transactional
	public UserResponse register(RegisterRequest request) {
		validatePasswordLength(request.password());
		String email = normalizeEmail(request.email());
		if (userRepository.existsByEmail(email)) {
			throw emailAlreadyExists();
		}
		User user = User.register(request.username().strip(), email, passwordEncoder.encode(request.password()));
		try {
			return UserResponse.from(userRepository.saveAndFlush(user));
		} catch (DataIntegrityViolationException exception) {
			// Also handle simultaneous registrations, relying on the DB unique constraint.
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "uq_users_email".equals(violation.getConstraintName())) {
					throw emailAlreadyExists();
				}
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		validatePasswordLength(request.password());
		User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
		String hash = user != null && user.getPasswordHash() != null ? user.getPasswordHash() : dummyPasswordHash;
		boolean matches = passwordEncoder.matches(request.password(), hash);
		if (!matches || user == null || user.getPasswordHash() == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password.");
		}
		return jwtService.issueAccessToken(user);
	}

	private void validatePasswordLength(String password) {
		// BCrypt limits bytes, while Bean Validation @Size counts Java characters.
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password must not exceed 72 UTF-8 bytes.");
		}
	}

	private String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private ApiException emailAlreadyExists() {
		return new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered.");
	}

}
