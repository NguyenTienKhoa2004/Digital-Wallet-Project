package com.flowpay.common.config;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.flowpay.auth.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {

	@Bean
	SecretKey jwtSigningKey(JwtProperties properties) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(properties.secret());
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("JWT_SECRET must be a Base64-encoded signing key.");
		}
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET must decode to at least 32 random bytes for HS256.");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
				.macAlgorithm(MacAlgorithm.HS256).build();
		OAuth2TokenValidator<Jwt> requiredClaims = jwt -> validClaims(jwt, properties)
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims.", null));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.issuer()), requiredClaims));
		return decoder;
	}

	private boolean validClaims(Jwt jwt, JwtProperties properties) {
		String subject = jwt.getSubject();
		try {
			if (subject == null || !UUID.fromString(subject).toString().equals(subject)) {
				return false;
			}
		} catch (IllegalArgumentException exception) {
			return false;
		}
		Object roles = jwt.getClaims().get("roles");
		return jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
				&& jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
				&& jwt.getAudience() != null && jwt.getAudience().contains(properties.audience())
				&& roles instanceof List<?> roleList && !roleList.isEmpty()
				&& roleList.stream().allMatch(role -> "USER".equals(role) || "ADMIN".equals(role));
	}

}
