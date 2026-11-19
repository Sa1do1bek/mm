package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record InventoryCommissionSettingsRequest(
    @NotEmpty List<@Valid InventoryCommissionMemberSettingRequest> members
) {
}
