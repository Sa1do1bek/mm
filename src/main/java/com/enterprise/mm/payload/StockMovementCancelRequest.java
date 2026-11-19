package com.enterprise.mm.payload;

public record StockMovementCancelRequest(Long expectedVersion, String reason) {
}
