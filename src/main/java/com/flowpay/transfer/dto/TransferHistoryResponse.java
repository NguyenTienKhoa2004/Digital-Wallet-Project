package com.flowpay.transfer.dto;

import java.util.List;

public record TransferHistoryResponse(
        List<TransferResponse> items, int page, int size, long totalElements, int totalPages) {
}
