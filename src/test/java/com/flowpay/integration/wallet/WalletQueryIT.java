package com.flowpay.integration.wallet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.flowpay.auth.AuthService;
import com.flowpay.auth.dto.LoginRequest;
import com.flowpay.auth.dto.RegisterRequest;
import com.flowpay.support.TestcontainersConfiguration;
import com.flowpay.wallet.WalletService;
import com.flowpay.wallet.dto.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"flowpay.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class WalletQueryIT {

	@Autowired
	private Environment environment;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuthService authService;

	@Autowired
	private WalletService walletService;

	@Test
	void authenticatedUserWithWalletReceivesWalletDetails() throws Exception {
		AuthenticatedUser user = registerAndLogin();
		WalletResponse wallet = walletService.createWallet(user.userId());

		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = getMyWallet(client, bearer(user.accessToken()));

			assertThat(response.statusCode()).isEqualTo(200);
			JsonNode body = objectMapper.readTree(response.body());
			assertThat(body.get("walletId").asString()).isEqualTo(wallet.walletId().toString());
			assertThat(body.get("balance").asLong()).isZero();
			assertThat(body.get("currency").asString()).isEqualTo("USD");
			assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
			assertThat(body.get("createdAt").asString()).isNotBlank();
			assertThat(body.get("updatedAt").asString()).isNotBlank();
		}
	}

	@Test
	void authenticatedUserWithoutWalletReceivesNotFound() throws Exception {
		AuthenticatedUser user = registerAndLogin();

		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = getMyWallet(client, bearer(user.accessToken()));

			assertThat(response.statusCode()).isEqualTo(404);
			assertThat(objectMapper.readTree(response.body()).get("code").asString())
					.isEqualTo("WALLET_NOT_FOUND");
		}
	}

	@Test
	void missingOrInvalidTokenCannotReadWallet() throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> missing = getMyWallet(client, null);
			assertThat(missing.statusCode()).isEqualTo(401);
			assertThat(missing.headers().firstValue("WWW-Authenticate")).isPresent();

			HttpResponse<String> invalid = getMyWallet(client, "Bearer invalid-token");
			assertThat(invalid.statusCode()).isEqualTo(401);
		}
	}

	@Test
	void authenticatedUserReceivesOnlyTheirOwnWallet() throws Exception {
		AuthenticatedUser firstUser = registerAndLogin();
		AuthenticatedUser secondUser = registerAndLogin();
		WalletResponse firstWallet = walletService.createWallet(firstUser.userId());
		WalletResponse secondWallet = walletService.createWallet(secondUser.userId());

		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = getMyWallet(client, bearer(firstUser.accessToken()));

			assertThat(response.statusCode()).isEqualTo(200);
			String returnedWalletId = objectMapper.readTree(response.body()).get("walletId").asString();
			assertThat(returnedWalletId).isEqualTo(firstWallet.walletId().toString());
			assertThat(returnedWalletId).isNotEqualTo(secondWallet.walletId().toString());
		}
	}

	private AuthenticatedUser registerAndLogin() {
		String email = UUID.randomUUID() + "@example.com";
		String password = "wallet-query-password";
		UUID userId = authService.register(new RegisterRequest("Wallet query test", email, password)).userId();
		String accessToken = authService.login(new LoginRequest(email, password)).accessToken();
		return new AuthenticatedUser(userId, accessToken);
	}

	private String bearer(String accessToken) {
		return "Bearer " + accessToken;
	}

	private HttpResponse<String> getMyWallet(HttpClient client, String authorization) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port")
						+ "/wallets/me"))
				.timeout(Duration.ofSeconds(15))
				.GET();
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private record AuthenticatedUser(UUID userId, String accessToken) {
	}

}
