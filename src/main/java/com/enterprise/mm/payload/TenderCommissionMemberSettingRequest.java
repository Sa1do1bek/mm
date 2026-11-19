package com.enterprise.mm.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TenderCommissionMemberSettingRequest(
    @Schema(description = "Commission member id returned inside POST/GET /api/commissions response. This is not userId or employeeId.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull UUID commissionMemberId,
    @Schema(description = "Voting weight for this tender member. Defaults to 1 if omitted.", example = "1")
    BigDecimal votingWeight,
    @Schema(description = "Whether this member signed the tender conflict-of-interest declaration.", example = "true")
    Boolean conflictDeclarationSigned,
    @Schema(description = "When the conflict declaration was signed.", example = "2026-07-11T10:00:00")
    LocalDateTime declaredAt,
    @Schema(description = "Whether this member is active for this RFQ assignment.", example = "true")
    Boolean active
) {
}
