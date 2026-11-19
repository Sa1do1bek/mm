package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;

public record StockMovementPostRequest(@NotNull Long expectedVersion) {
}
