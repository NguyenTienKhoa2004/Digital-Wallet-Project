package com.flowpay.transfer;

import java.util.UUID;

import com.flowpay.transfer.dto.TransferHistoryResponse;
import com.flowpay.transfer.dto.TransferRequest;
import com.flowpay.transfer.dto.TransferResponse;

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
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService queryService;

    public TransferController(TransferService transferService, TransferQueryService queryService) {
        this.transferService = transferService;
        this.queryService = queryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        UUID authenticatedUserId = UUID.fromString(jwt.getSubject());
        return transferService.transfer(authenticatedUserId, idempotencyKey, request);
    }

    @GetMapping("/by-idempotency-key")
    public TransferResponse findByIdempotencyKey(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key) {
        return transferService.findByIdempotencyKey(UUID.fromString(jwt.getSubject()), key);
    }

    @GetMapping("/{reference}")
    public TransferResponse findByReference(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String reference) {
        return queryService.findByReference(UUID.fromString(jwt.getSubject()), reference);
    }

    @GetMapping
    public TransferHistoryResponse history(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.history(UUID.fromString(jwt.getSubject()), page, size);
    }
}

