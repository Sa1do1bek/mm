package com.enterprise.mm.supply.workspace.payload;

import com.enterprise.mm.supply.workspace.enums.SupplyAssignmentRole;
import java.util.UUID;

public final class SupplyWorkspaceSelectorDtos {
    private SupplyWorkspaceSelectorDtos() {
    }

    public record CompanyOption(
        UUID id,
        String code,
        String displayName,
        String status
    ) {
    }

    public record OwnerOption(
        UUID id,
        UUID companyId,
        String username,
        String displayName,
        SupplyAssignmentRole role
    ) {
    }
}
