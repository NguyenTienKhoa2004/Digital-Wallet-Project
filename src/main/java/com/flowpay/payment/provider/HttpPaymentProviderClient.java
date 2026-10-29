package com.flowpay.payment.provider;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Profile("mock-provider")
public class HttpPaymentProviderClient implements PaymentProviderClient {

    private final RestClient restClient;
    private final ProviderEndpoints endpoints;

    public HttpPaymentProviderClient(
            ProviderEndpoints endpoints,
            @Value("${mock-provider.api-key}") String apiKey,
            @Value("${mock-provider.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${mock-provider.read-timeout-ms:3000}") int readTimeoutMs) {

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.endpoints = endpoints;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("X-Provider-Key", apiKey)
                .build();
    }

    @Override
    public ProviderPaymentResult createPayment(
            CreateProviderPaymentRequest request) {

        ProviderPaymentResult result = restClient.post()
                .uri(endpoints.provider("/payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ProviderPaymentResult.class);

        result = requireValidResult(result, request.paymentId());

        if (result.amount() != request.amount()
                || !result.currency().equals(request.currency())) {
            throw new RestClientException(
                    "Provider returned a different amount or currency.");
        }

        return result;
    }

    @Override
    public ProviderPaymentResult findByPaymentId(UUID paymentId) {
        ProviderPaymentResult result = restClient.get()
                .uri(endpoints.provider("/payments/" + paymentId))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ProviderPaymentResult.class);

        return requireValidResult(result, paymentId);
    }

    private ProviderPaymentResult requireValidResult(
            ProviderPaymentResult result,
            UUID expectedPaymentId) {

        if (result == null
                || !expectedPaymentId.equals(result.paymentId())
                || result.providerPaymentId() == null
                || result.providerPaymentId().isBlank()
                || result.providerPaymentId().length() > 255
                || result.status() == null
                || result.amount() <= 0
                || !"USD".equals(result.currency())) {

            throw new RestClientException(
                    "Provider returned an invalid payment response.");
        }

        return result;
    }
}
