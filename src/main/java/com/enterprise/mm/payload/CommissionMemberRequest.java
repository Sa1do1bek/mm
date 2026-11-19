package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommissionMemberRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CommissionMemberRequest(
    @Schema(description = "Application user id. Required for inventory commission members.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID userId,
    @Schema(description = "Role of this member inside the reusable commission group.", example = "CHAIRMAN", allowableValues = {"CHAIRMAN", "MEMBER", "COUNTER", "CHECKER", "ACCOUNTANT", "STOREKEEPER", "SECRETARY", "AUDITOR"})
    CommissionMemberRole role,
    @Schema(description = "Employee id. Can be used for tender commission members when no user account is needed.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID employeeId,
    @Schema(description = "Manual member display name when userId/employeeId is not provided.", example = "External technical expert")
    String memberName,
    @Schema(description = "Whether this member is active in the commission group.", example = "true")
    Boolean active
) {
    public CommissionMemberRequest(UUID userId, CommissionMemberRole role) {
        this(userId, role, null, null, null);
    }
}
