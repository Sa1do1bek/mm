package com.enterprise.mm.payload;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InventoryCommissionMemberSettingRequest(
    @NotNull UUID commissionMemberId,
    @NotNull Boolean showSystemQuantityToCounter
) {
}
