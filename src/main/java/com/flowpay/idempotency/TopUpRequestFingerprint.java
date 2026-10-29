package com.flowpay.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.flowpay.topup.dto.TopUpRequest;
import org.springframework.stereotype.Component;

@Component
public class TopUpRequestFingerprint {

    public String hash(TopUpRequest request) {
        String description = request.description();
        String descriptionPart = description == null
                ? "-1:"
                : description.getBytes(StandardCharsets.UTF_8).length
                        + ":" + description;

        String canonical = "TOP_UP:v1\n"
                + request.amount() + "\n"
                + descriptionPart;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime.", exception);
        }
    }
}
