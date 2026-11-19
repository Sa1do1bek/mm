package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import com.enterprise.mm.enums.InventoryScopeMode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_documents")
public class InventoryDocument extends BusinessEntity {
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    @Column(name = "inventory_sequence", insertable = false, updatable = false)
    private Long inventorySequence;

    public String getInventoryNumber() {
        return inventorySequence == null ? null : String.format(java.util.Locale.ROOT, "INV-%06d", inventorySequence);
    }

    @Column(name = "warehouse_id")
    private UUID warehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", insertable = false, updatable = false)
    private Warehouse warehouse;
    @Column(length = 40)
    private String inventoryType = "PLANNED";
    private LocalDate plannedStartDate;
    private LocalDate plannedEndDate;
    @Column(name = "stock_as_of_date")
    private LocalDate stockAsOfDate;
    private LocalDate actualStartDate;
    private LocalDate actualEndDate;
    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;
    @Column(name = "commission_id")
    private UUID commissionId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commission_id", insertable = false, updatable = false)
    private Commission commission;
    @Column(nullable = false)
    private boolean globalWarehouse = false;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_mode", length = 24)
    private InventoryScopeMode scopeMode;
    @ElementCollection
    @CollectionTable(name = "inventory_document_departments", joinColumns = @JoinColumn(name = "inventory_document_id"))
    @Column(name = "department_id", nullable = false)
    private Set<UUID> departmentIds = new LinkedHashSet<>();
    @ElementCollection
    @CollectionTable(name = "inventory_document_selected_warehouses", joinColumns = @JoinColumn(name = "inventory_document_id"))
    @Column(name = "warehouse_id", nullable = false)
    private Set<UUID> selectedWarehouseIds = new LinkedHashSet<>();
    @ElementCollection
    @CollectionTable(name = "inventory_document_resolved_warehouses", joinColumns = @JoinColumn(name = "inventory_document_id"))
    @Column(name = "warehouse_id", nullable = false)
    private Set<UUID> resolvedWarehouseIds = new LinkedHashSet<>();
    @Column(nullable = false)
    private boolean freezeOperations = true;
    @Column(name = "snapshot_at")
    private LocalDateTime snapshotAt;
    @Column(nullable = false)
    private int totalItems = 0;
    @Column(nullable = false)
    private int countedItems = 0;
    @Column(nullable = false)
    private int discrepancyItems = 0;

    @Column(nullable=false) private long countRevision;
    private Long submittedCountRevision;
    private Long approvedCountRevision;
    private LocalDate postingDate;
    private UUID storageLocationId;
    private UUID storageBinId;
    private UUID approvalInstanceId;
    private Long commissionApprovedRevision;
    private String commissionApprovedBy;
    private LocalDateTime commissionApprovedAt;
    private Long financeApprovedRevision;
    private String financeApprovedBy;
    private LocalDateTime financeApprovedAt;
    @Column(length=1000) private String correctionReason;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition="jsonb", nullable=false)
    private java.util.List<InventoryUnidentifiedObservation> unidentifiedStock = new java.util.ArrayList<>();

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (warehouse != null) {
            warehouseId = warehouse.getId();
        }
        commissionId = commission == null ? commissionId : commission.getId();
    }
}
