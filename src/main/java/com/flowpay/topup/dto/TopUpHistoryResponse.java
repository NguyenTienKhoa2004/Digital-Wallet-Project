package com.flowpay.topup.dto;

import java.util.List;

public record TopUpHistoryResponse(List<TopUpResponse> items, int page, int size, long totalElements, int totalPages) {
}
