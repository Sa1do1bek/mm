package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;

public record TransferApproveRequest(@NotNull Long expectedVersion) {
}
