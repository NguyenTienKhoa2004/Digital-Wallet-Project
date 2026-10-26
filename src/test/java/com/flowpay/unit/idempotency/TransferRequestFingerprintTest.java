package com.flowpay.unit.idempotency;

import java.util.UUID;

import com.flowpay.idempotency.TransferRequestFingerprint;
import com.flowpay.transfer.dto.TransferRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestFingerprintTest {

    private final TransferRequestFingerprint fingerprint = new TransferRequestFingerprint();

    @Test
    void theSameBusinessFieldsProduceAStableSha256Fingerprint() {
        UUID walletId = UUID.randomUUID();
        String hash = fingerprint.hash(new TransferRequest(walletId, 3000, "Café\nDinner"));
        assertThat(hash).matches("[0-9a-f]{64}")
                .isEqualTo(fingerprint.hash(new TransferRequest(walletId, 3000, "Café\nDinner")));
    }

    @Test
    void everyFinancialInputParticipatesInTheFingerprint() {
        UUID walletId = UUID.randomUUID();
        String original = fingerprint.hash(new TransferRequest(walletId, 3000, "Dinner"));
        assertThat(fingerprint.hash(new TransferRequest(UUID.randomUUID(), 3000, "Dinner"))).isNotEqualTo(original);
        assertThat(fingerprint.hash(new TransferRequest(walletId, 3001, "Dinner"))).isNotEqualTo(original);
        assertThat(fingerprint.hash(new TransferRequest(walletId, 3000, "Other"))).isNotEqualTo(original);
    }

    @Test
    void nullEmptyAndWhitespaceDescriptionsAreDifferentPayloads() {
        UUID walletId = UUID.randomUUID();
        assertThat(java.util.Set.of(
                fingerprint.hash(new TransferRequest(walletId, 1, null)),
                fingerprint.hash(new TransferRequest(walletId, 1, "")),
                fingerprint.hash(new TransferRequest(walletId, 1, " ")))).hasSize(3);
    }
}
