package com.flowpay.topup;

import java.util.UUID;

import com.flowpay.topup.dto.TopUpRequest;
import com.flowpay.topup.dto.TopUpResponse;
import com.flowpay.topup.dto.TopUpHistoryResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/top-ups")
@SecurityRequirement(name = "bearerAuth")
public class TopUpController {

    private final TopUpService topUpService;
    private final TopUpQueryService queryService;

    public TopUpController(TopUpService topUpService, TopUpQueryService queryService) {
        this.topUpService = topUpService;
        this.queryService = queryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TopUpResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TopUpRequest request) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return topUpService.create(userId, key, request);
    }

    @GetMapping("/by-idempotency-key")
    public TopUpResponse byKey(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key) {
        return queryService.findByKey(UUID.fromString(jwt.getSubject()), key);
    }

    @GetMapping("/{reference}")
    public TopUpResponse byReference(@AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return queryService.findByReference(UUID.fromString(jwt.getSubject()), reference);
    }

    @GetMapping
    public TopUpHistoryResponse history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queryService.history(UUID.fromString(jwt.getSubject()), page, size);
    }
}
