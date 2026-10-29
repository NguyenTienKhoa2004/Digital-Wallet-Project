package com.flowpay.mockprovider;

import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.payment.provider.CreateProviderPaymentRequest;
import com.flowpay.payment.provider.ProviderPaymentResult;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mock-provider")
public class MockPaymentService {

    private final MockPaymentRepository repository;

    public MockPaymentService(MockPaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProviderPaymentResult create(
            CreateProviderPaymentRequest request) {

        validate(request);

        repository.insertIfAbsent(
                request, "MOCK-" + UUID.randomUUID());

        ProviderPaymentResult payment = repository
                .findByPaymentId(request.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment is missing after insert."));

        if (payment.amount() != request.amount()
                || !payment.currency().equals(request.currency())) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_ID_CONFLICT",
                    "This payment ID was already used with different data.");
        }

        return payment;
    }

    @Transactional(readOnly = true)
    public ProviderPaymentResult findByPaymentId(UUID paymentId) {
        return repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_NOT_FOUND",
                        "Provider payment does not exist."));
    }

    private void validate(CreateProviderPaymentRequest request) {
        if (request == null
                || request.paymentId() == null
                || request.amount() <= 0
                || !"USD".equals(request.currency())) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAYMENT_REQUEST",
                    "Payment ID, positive amount and USD currency are required.");
        }
    }
}
