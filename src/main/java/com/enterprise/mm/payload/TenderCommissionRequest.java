package com.enterprise.mm.payload;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderCommissionRequest {
    @Size(max = 80)
    @Schema(description = "Optional tender commission assignment code. If omitted, backend generates one.", example = "TC-RFQ-2026-001")
    private String code;
    @Size(max = 240)
    @Schema(description = "Tender-specific commission assignment name.", example = "RFQ-2026-001 evaluation commission")
    private String name;
    @Size(max = 50)
    @Schema(description = "Generic assignment status.", example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
    private String status;
    @Schema(description = "Optional tender-specific commission notes.", example = "Commission assigned for conflict checks and bid evaluation")
    private String description;
    @NotNull
    @Schema(description = "RFQ/tender id that this commission is assigned to.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID rfqId;
    @NotNull
    @Schema(description = "Reusable commission id from POST /api/commissions. Must have type TENDER.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID commissionId;
    @Schema(description = "Minimum signed/active members required for tender actions.", example = "3")
    @PositiveOrZero
    private Integer quorumRequired;
    @Schema(description = "Whether this commission assignment is active for the RFQ.", example = "true")
    private Boolean active;
    @Schema(description = "Tender-specific settings for members of the reusable commission. commissionMemberId must come from the selected commission members.", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<TenderCommissionMemberSettingRequest> members;
}
