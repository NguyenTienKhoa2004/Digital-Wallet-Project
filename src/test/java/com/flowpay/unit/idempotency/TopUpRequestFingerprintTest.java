package com.flowpay.unit.idempotency;

import com.flowpay.idempotency.TopUpRequestFingerprint;
import com.flowpay.topup.dto.TopUpRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopUpRequestFingerprintTest {

    private final TopUpRequestFingerprint fingerprint = new TopUpRequestFingerprint();

    @Test
    void identicalBusinessFieldsHaveTheSameSha256Fingerprint() {
        String hash = fingerprint.hash(new TopUpRequest(1000, "Nạp tiền"));
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(fingerprint.hash(new TopUpRequest(1000, "Nạp tiền")));
    }

    @Test
    void amountDescriptionAndAbsentDescriptionAllContributeToIdentity() {
        String original = fingerprint.hash(new TopUpRequest(1000, null));
        assertThat(original).isNotEqualTo(fingerprint.hash(new TopUpRequest(2000, null)));
        assertThat(original).isNotEqualTo(fingerprint.hash(new TopUpRequest(1000, "")));
        assertThat(original).isNotEqualTo(fingerprint.hash(new TopUpRequest(1000, "Deposit")));
    }
}
