package com.flowpay.mockprovider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.ProviderPaymentResult;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@Profile("mock-provider")
public class MockPaymentController {

    private final MockPaymentService service;
    private final byte[] expectedApiKey;

    public MockPaymentController(
            MockPaymentService service,
            @Value("${mock-provider.api-key}") String apiKey) {

        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Mock provider API key must not be blank.");
        }

        this.service = service;
        this.expectedApiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ProviderPaymentResult create(
            @RequestHeader(
                    value = "X-Provider-Key",
                    required = false) String apiKey,
            @Valid @RequestBody CreateProviderPaymentRequest request) {

        requireValidApiKey(apiKey);
        return service.create(request);
    }

    @GetMapping("/{paymentId}")
    public ProviderPaymentResult findByPaymentId(
            @RequestHeader(
                    value = "X-Provider-Key",
                    required = false) String apiKey,
            @PathVariable UUID paymentId) {

        requireValidApiKey(apiKey);
        return service.findByPaymentId(paymentId);
    }

    private void requireValidApiKey(String apiKey) {
        if (apiKey == null
                || !MessageDigest.isEqual(
                        expectedApiKey,
                        apiKey.getBytes(StandardCharsets.UTF_8))) {

            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_PROVIDER_KEY",
                    "A valid provider API key is required.");
        }
    }
}
