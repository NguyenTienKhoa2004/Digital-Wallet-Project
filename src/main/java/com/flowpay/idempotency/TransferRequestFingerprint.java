package com.flowpay.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.flowpay.transfer.dto.TransferRequest;
import org.springframework.stereotype.Component;

@Component
public class TransferRequestFingerprint {

    public String hash(TransferRequest request) {
        // Hash parsed business fields, not raw JSON: property order/whitespace must not change identity.
        // The version and length prefix distinguish null from empty descriptions and prevent ambiguity.
        String description = request.description();
        String descriptionPart = description == null ? "-1:"
                : description.getBytes(StandardCharsets.UTF_8).length + ":" + description;
        String canonical = "TRANSFER:v1\n" + request.destinationWalletId() + "\n"
                + request.amount() + "\n" + descriptionPart;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
