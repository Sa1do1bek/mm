package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "transfer_documents")
public class TransferDocument extends BusinessEntity {
    @Column(name="return_of_transfer_id") private java.util.UUID returnOfTransferId;
    @Column(name = "reconciliation_status", nullable = false, length = 40)
    private String reconciliationStatus = "NOT_REQUIRED";

    @Column(name = "reconciliation_provenance", columnDefinition = "text")
    private String reconciliationProvenance;

    @Column(name = "source_warehouse_id", nullable = false)
    private UUID sourceWarehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_warehouse_id", insertable = false, updatable = false)
    private Warehouse sourceWarehouse;

    @Column(name = "source_location_id")
    private UUID sourceLocationId;

    @Column(name = "destination_warehouse_id", nullable = false)
    private UUID destinationWarehouseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_warehouse_id", insertable = false, updatable = false)
    private Warehouse destinationWarehouse;

    @Column(name = "destination_location_id")
    private UUID destinationLocationId;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "dispatched_date")
    private LocalDate dispatchedDate;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Column(name = "dispatch_movement_document_id")
    private UUID dispatchMovementDocumentId;

    @Column(name = "receive_movement_document_id")
    private UUID receiveMovementDocumentId;
}
