package com.enterprise.mm.payload;

import com.enterprise.mm.enums.StockMovementStatus;
import jakarta.validation.constraints.NotNull;

public record StockMovementStatusChangeRequest(@NotNull StockMovementStatus status) {
}
