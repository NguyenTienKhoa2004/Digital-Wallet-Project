package com.flowpay.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.flowpay.common.exception.ApiException;
import com.flowpay.transfer.dto.RecipientResponse;
import com.flowpay.user.RecipientCandidate;
import com.flowpay.user.UserRepository;
import com.flowpay.wallet.WalletRepository;
import com.flowpay.wallet.WalletStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipientService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public RecipientService(
            UserRepository userRepository,
            WalletRepository walletRepository) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public List<RecipientResponse> search(UUID currentUserId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String keyword = query.strip().toLowerCase(Locale.ROOT);

        if (keyword.length() > 320) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "Search keyword must not exceed 320 characters.");
        }

        boolean searchingByEmail = keyword.contains("@");

        if (!searchingByEmail && keyword.length() < 3) {
            return List.of();
        }

        String currency = walletRepository.findByUserId(currentUserId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "SOURCE_WALLET_NOT_FOUND",
                        "User does not have a wallet."))
                .getCurrency();

        String exactEmail = searchingByEmail ? keyword : null;
        String usernamePattern = searchingByEmail
                ? null
                : escapeLike(keyword) + "%";

        List<RecipientCandidate> candidates =
                userRepository.searchTransferRecipients(
                        currentUserId,
                        WalletStatus.ACTIVE,
                        currency,
                        exactEmail,
                        usernamePattern,
                        PageRequest.of(0, 10));

        List<RecipientResponse> recipients = new ArrayList<>();
        for (RecipientCandidate candidate : candidates) {
            recipients.add(new RecipientResponse(
                    candidate.walletId(),
                    candidate.username(),
                    maskEmail(candidate.email()),
                    candidate.currency()));
        }
        return recipients;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');

        if (atIndex <= 0) {
            return "***";
        }

        return email.substring(0, 1) + "***" + email.substring(atIndex);
    }
}
