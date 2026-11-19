package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record PurchaseRequestItemRemovalRequest(@NotNull UUID id, @NotNull @PositiveOrZero Long version) {
}
