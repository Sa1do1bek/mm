package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommissionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommissionCreateRequest {
    @Size(max = 80)
    @Schema(description = "Optional reusable commission code. If omitted, backend generates one.", example = "COM-TENDER-2026-01")
    private String code;
    @Size(max = 240)
    @Schema(description = "Reusable commission group name.", example = "Tender Commission 2026")
    private String name;
    @Size(max = 50)
    @Schema(description = "Generic record status. Use ACTIVE for normal commission groups.", example = "ACTIVE", allowableValues = {"ACTIVE", "INACTIVE"})
    private String status;
    @Schema(description = "Optional notes about the commission group.", example = "Standing procurement commission for competitive tenders")
    private String description;
    @Schema(description = "Company scope. Optional for normal company users; useful for admin/global users.", example = "00000000-0000-0000-0000-000000000001")
    private UUID companyId;
    @NotNull
    @Schema(description = "Module purpose of the reusable commission group.", example = "TENDER", allowableValues = {"INVENTORY", "TENDER"})
    private CommissionType type;
    @Schema(
        description = "Optional RFQ to attach when type=TENDER. Creates tender-commission assignment after the master commission is saved. Ignored/rejected for INVENTORY. Not required — commission may exist before an RFQ is available.",
        example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED
    )
    private UUID rfqId;
    @Schema(description = "Optional reusable commission members. Member ids returned here are later used by module-specific settings.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<CommissionMemberRequest> members;
    @Schema(description = "Date from which the commission is valid.", example = "2026-07-11")
    private LocalDate validFrom;
    @Schema(description = "Date until which the commission is valid.", example = "2026-12-31")
    private LocalDate validTo;
    @Schema(description = "Whether the reusable commission group is active.", example = "true")
    private Boolean active;
}
