package com.enterprise.mm.payload;

import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.enums.InventoryType;
import com.enterprise.mm.enums.InventoryScopeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryDocumentCreateRequest {
    @Size(max = 80)
    @Schema(description = "Optional inventory document code. If omitted, backend generates one.", example = "INV-2026-07-MAIN")
    private String code;
    @Size(max = 240)
    @Schema(description = "Inventory check name.", example = "Main Warehouse July 2026 Inventory Count")
    private String name;
    @Schema(description = "Initial inventory workflow status. Usually PLANNED for a newly created inventory.", example = "PLANNED", allowableValues = {"DRAFT", "PLANNED", "IN_PROGRESS", "COUNTING_COMPLETED", "DISCREPANCY_REVIEW", "WAITING_APPROVAL", "APPROVED", "REJECTED", "ADJUSTMENT_POSTED", "CLOSED", "CANCELLED"})
    private InventoryStatus status;
    @Schema(description = "Optional inventory notes.", example = "Scheduled physical inventory count for Main Warehouse")
    private String description;
    @Schema(description = "Company scope. Optional for normal company users; useful for admin/global users.", example = "00000000-0000-0000-0000-000000000001")
    private UUID companyId;
    @Schema(description = "Warehouse id. Required when globalWarehouse is false.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    @Schema(description = "If true, inventory is created for all warehouses of the selected company.", example = "false")
    private Boolean globalWarehouse;
    @Schema(description = "Use MIXED for independent department and warehouse selections; DEPARTMENTS and WAREHOUSES remain supported. Omit for legacy single/all warehouse requests.")
    private InventoryScopeMode scopeMode;
    private List<UUID> departmentIds;
    private List<UUID> warehouseIds;
    @Schema(description = "Reason/category of inventory check.", example = "PLANNED", allowableValues = {"PLANNED", "UNSCHEDULED", "SAMPLE", "CYCLE", "EVENT"})
    private InventoryType inventoryType;
    @Schema(description = "Planned start date.", example = "2026-07-11")
    private LocalDate plannedStartDate;
    @Schema(description = "Planned end date.", example = "2026-07-20")
    private LocalDate plannedEndDate;
    @Schema(description = "Accounting stock cut-off date; distinct from the physical counting dates and snapshot capture time.", example = "2026-10-01")
    private LocalDate stockAsOfDate;
    @Schema(description = "Responsible user id for this inventory document.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID responsibleUserId;
    @Schema(description = "Reusable INVENTORY commission id from POST /api/commissions.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID commissionId;
    @Schema(description = "Optional material filter. Empty means all applicable materials in scope.", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private List<UUID> materialIds;
    @Schema(description = "Optional material group filter. Empty means all applicable groups in scope.", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private List<UUID> materialGroupIds;
    @Schema(description = "Whether stock operations are frozen while this inventory is in process. Defaults to true in service logic.", example = "true")
    private Boolean freezeOperations;
    @Schema(description = "Optional owned AI draft whose source documents are bound when this inventory is created.")
    private UUID sourceAiDraftId;
}
