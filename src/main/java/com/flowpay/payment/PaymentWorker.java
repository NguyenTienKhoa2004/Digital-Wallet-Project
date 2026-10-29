package com.flowpay.payment;

import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.PaymentProviderClient;
import com.flowpay.payment.provider.ProviderPaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("mock-provider")
public class PaymentWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWorker.class);
    private final PaymentWorkRepository work;
    private final PaymentProviderClient client;
    private final PaymentSettlementService settlement;
    private final boolean enabled;

    public PaymentWorker(PaymentWorkRepository work, PaymentProviderClient client,
            PaymentSettlementService settlement, @Value("${flowpay.background-jobs-enabled:true}") boolean enabled) {
        this.work = work;
        this.client = client;
        this.settlement = settlement;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${flowpay.payment-poll-interval-ms:1000}")
    public void scheduledPoll() {
        if (enabled) {
            processDuePayments();
        }
    }

    // Intentionally not @Transactional: HTTP runs after the claim has committed.
    public void processDuePayments() {
        for (int i = 0; i < 10; i++) {
            var next = work.claimNext();
            if (next.isEmpty()) {
                return;
            }
            var task = next.orElseThrow();
            try {
                ProviderPaymentResult result;
                try {
                    result = client.findByPaymentId(task.paymentId());
                } catch (HttpClientErrorException.NotFound absent) {
                    result = client.createPayment(new CreateProviderPaymentRequest(
                            task.paymentId(), task.amount(), task.currency()));
                }
                settlement.accept(result, "POLL");
            } catch (RuntimeException failure) {
                String code = failure instanceof RestClientResponseException http
                        ? "PROVIDER_HTTP_" + http.getStatusCode().value() : "PROVIDER_OUTCOME_UNKNOWN";
                LOG.warn("Payment {} will be retried: {} ({})", task.paymentId(), code, failure.getClass().getSimpleName());
                try {
                    settlement.retry(task.paymentId(), code);
                } catch (RuntimeException unavailable) {
                    // The durable lease will expire even if recording this retry fails.
                    LOG.error("Could not record retry for payment {}", task.paymentId());
                }
            }
        }
    }
}
