package com.enterprise.mm.payload;

import com.enterprise.mm.enums.InventoryStatus;
import com.enterprise.mm.enums.InventoryType;
import com.enterprise.mm.enums.InventoryScopeMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class InventoryDocumentResponse extends BaseRecordDto {
    private String inventoryNumber;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID storageBinId;
    private String warehouseCode;
    private String warehouseName;
    private boolean globalWarehouse;
    private InventoryScopeMode scopeMode;
    private List<UUID> departmentIds;
    private List<UUID> selectedWarehouseIds;
    private List<UUID> warehouseIds;
    private List<WarehouseResponse> warehouses;
    private InventoryStatus inventoryStatus;
    private InventoryType inventoryType;
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    private LocalDate stockAsOfDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    private UUID responsibleUserId;
    private UUID commissionId;
    private String commissionCode;
    private String commissionName;
    private List<UUID> materialIds;
    private List<UUID> materialGroupIds;
    private List<UUID> commissionMemberIds;
    private List<InventoryCommissionMemberResponse> commissionMembers;
    private List<InventoryItemResponse> items;
    private boolean freezeOperations;
    private LocalDateTime snapshotAt;
    private long countRevision;
    private Long submittedCountRevision;
    private Long approvedCountRevision;
    private LocalDate postingDate;
    private UUID approvalInstanceId;
    private Long commissionApprovedRevision;
    private String commissionApprovedBy;
    private LocalDateTime commissionApprovedAt;
    private Long financeApprovedRevision;
    private String financeApprovedBy;
    private LocalDateTime financeApprovedAt;
    private String correctionReason;
    private java.util.List<com.enterprise.mm.entity.InventoryUnidentifiedObservation> unidentifiedStock;
    private int totalItems;
    private int countedItems;
    private Integer discrepancyItems;
}
