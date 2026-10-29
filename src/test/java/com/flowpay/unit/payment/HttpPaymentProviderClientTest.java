package com.flowpay.unit.payment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.HttpPaymentProviderClient;
import com.flowpay.payment.provider.PaymentProviderStatus;
import com.flowpay.payment.provider.ProviderEndpoints;
import com.flowpay.payment.provider.ProviderPaymentResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class HttpPaymentProviderClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private HttpServer server;
    private ExecutorService executor;
    private HttpPaymentProviderClient client;
    private ProviderEndpoints endpoints;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        endpoints = new ProviderEndpoints(new MockEnvironment().withProperty("mock-provider.base-url",
                "http://127.0.0.1:" + server.getAddress().getPort()));
        client = new HttpPaymentProviderClient(endpoints, "test-key", 1000, 1000);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.close();
    }

    @Test
    void postSendsTheStablePaymentIdPayloadAndServiceKey() {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/payments", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            key.set(exchange.getRequestHeaders().getFirst("X-Provider-Key"));
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, result(id, 1000));
        });
        assertThat(client.createPayment(new CreateProviderPaymentRequest(id, 1000, "USD")))
                .isEqualTo(result(id, 1000));
        assertThat(method.get()).isEqualTo("POST");
        assertThat(key.get()).isEqualTo("test-key");
        assertThat(body.get().get("paymentId").asString()).isEqualTo(id.toString());
        assertThat(body.get().get("amount").asLong()).isEqualTo(1000);
        assertThat(body.get().get("currency").asString()).isEqualTo("USD");
    }

    @Test
    void aResponseForAnotherPaymentCannotBeAccepted() {
        server.createContext("/payments", exchange -> respond(exchange, 200, result(UUID.randomUUID(), 1000)));
        assertThatThrownBy(() -> client.findByPaymentId(id)).isInstanceOf(RestClientException.class)
                .hasMessageContaining("invalid payment response");
    }

    @Test
    void aDifferentAmountCannotBeAcceptedAsThePostResult() {
        server.createContext("/payments", exchange -> respond(exchange, 200, result(id, 2000)));
        assertThatThrownBy(() -> client.createPayment(new CreateProviderPaymentRequest(id, 1000, "USD")))
                .isInstanceOf(RestClientException.class).hasMessageContaining("different amount or currency");
    }

    @Test
    void anIncompleteJsonResponseIsRejected() {
        server.createContext("/payments", exchange -> respond(exchange, 200, java.util.Map.of("paymentId", id)));
        assertThatThrownBy(() -> client.findByPaymentId(id)).isInstanceOf(RestClientException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 409, 503})
    void httpFailuresRetainTheirStatusForWorkerRecovery(int status) {
        server.createContext("/payments", exchange -> respond(exchange, status, java.util.Map.of("error", "test")));
        assertThatThrownBy(() -> client.findByPaymentId(id))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void aRealReadTimeoutIsAnUnknownOutcomeRatherThanAFailedPayment() {
        client = new HttpPaymentProviderClient(endpoints, "test-key", 1000, 100);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/payments", exchange -> {
            try {
                release.await(2, TimeUnit.SECONDS);
                respond(exchange, 200, result(id, 1000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try {
            assertThatThrownBy(() -> client.findByPaymentId(id)).isInstanceOf(ResourceAccessException.class);
        } finally {
            release.countDown();
        }
    }

    private ProviderPaymentResult result(UUID paymentId, long amount) {
        return new ProviderPaymentResult(paymentId, "MOCK-test", amount, "USD", PaymentProviderStatus.PENDING);
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        try (var output = exchange.getResponseBody()) {
            output.write(json);
        } finally {
            exchange.close();
        }
    }
}
