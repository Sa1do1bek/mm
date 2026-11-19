package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stock_movement_documents")
public class StockMovementDocument extends BusinessEntity {
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "target_warehouse_id")
    private UUID targetWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", length = 40, nullable = false)
    private MovementType movementType;

    @Column(name = "movement_date", nullable = false)
    private LocalDate movementDate;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "reference_type", length = 80)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reversal_of_id")
    private UUID reversalOfId;

    /** Immutable command snapshot used to execute a manual warehouse operation after approval. */
    @Column(name = "manual_operation_payload", columnDefinition = "text")
    private String manualOperationPayload;
}
