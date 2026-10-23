package com.flowpay.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flowpay.auth.dto.LoginResponse;
import com.flowpay.auth.dto.UserResponse;
import com.flowpay.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;

	public JwtService(JwtEncoder jwtEncoder, JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public LoginResponse issueAccessToken(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.audience(List.of(properties.audience()))
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(properties.accessTokenTtlSeconds()))
				.id(UUID.randomUUID().toString())
				.claim("roles", List.of(user.getRole()))
				.build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
		return new LoginResponse(token, "Bearer", properties.accessTokenTtlSeconds(), UserResponse.from(user));
	}

}
