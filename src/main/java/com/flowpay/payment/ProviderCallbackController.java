package com.flowpay.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.flowpay.common.exception.ApiException;
import com.flowpay.payment.provider.ProviderPaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mock-provider")
public class ProviderCallbackController {

    private final PaymentSettlementService settlement;
    private final byte[] expectedKey;

    public ProviderCallbackController(PaymentSettlementService settlement,
            @Value("${mock-provider.callback-key}") String key) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("Provider callback key must not be blank.");
        }
        this.settlement = settlement;
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/provider-callbacks/mock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void callback(@RequestHeader(value = "X-Callback-Key", required = false) String key,
            @RequestBody ProviderPaymentResult result) {
        if (key == null || !MessageDigest.isEqual(expectedKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CALLBACK_KEY", "A valid callback key is required.");
        }
        settlement.accept(result, "CALLBACK");
    }
}
