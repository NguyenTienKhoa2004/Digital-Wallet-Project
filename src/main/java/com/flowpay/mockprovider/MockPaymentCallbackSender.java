package com.flowpay.mockprovider;

import java.time.Duration;

import com.flowpay.payment.provider.ProviderEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("mock-provider")
public class MockPaymentCallbackSender {

    private static final Logger LOG = LoggerFactory.getLogger(MockPaymentCallbackSender.class);
    private final MockPaymentRepository repository;
    private final ProviderEndpoints endpoints;
    private final RestClient client;
    private final boolean enabled;

    public MockPaymentCallbackSender(MockPaymentRepository repository, ProviderEndpoints endpoints,
            @Value("${mock-provider.callback-key}") String key,
            @Value("${flowpay.background-jobs-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.endpoints = endpoints;
        this.enabled = enabled;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).defaultHeader("X-Callback-Key", key).build();
    }

    @Scheduled(fixedDelayString = "${mock-provider.callback-interval-ms:1000}")
    public void scheduledDelivery() {
        if (enabled) {
            dispatchDueCallbacks();
        }
    }

    public void dispatchDueCallbacks() {
        for (int i = 0; i < 10; i++) {
            var next = repository.claimCallback();
            if (next.isEmpty()) {
                return;
            }
            var payment = next.orElseThrow();
            try {
                client.post().uri(endpoints.callback()).contentType(MediaType.APPLICATION_JSON)
                        .body(payment).retrieve().toBodilessEntity();
                repository.callbackDelivered(payment.paymentId());
            } catch (RuntimeException failure) {
                LOG.warn("Callback for payment {} will be retried ({})",
                        payment.paymentId(), failure.getClass().getSimpleName());
                repository.retryCallback(payment.paymentId());
            }
        }
    }
}
