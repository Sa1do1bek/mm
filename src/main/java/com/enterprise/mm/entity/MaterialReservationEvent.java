package com.enterprise.mm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Immutable
@Table(name = "material_reservation_events")
public class MaterialReservationEvent {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 80)
    private String tenantId;
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "created_by", updatable = false, length = 120)
    private String createdBy;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Long sequenceNo;
    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 160)
    private String idempotencyKey;
    @Column(name = "expected_version", updatable = false)
    private Long expectedVersion;
    @Column(name = "reservation_version", nullable = false, updatable = false)
    private Long reservationVersion;
    @Column(name = "stock_balance_id", nullable = false, updatable = false)
    private UUID stockBalanceId;
    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;
    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;
    @Column(name = "storage_location_id", updatable = false)
    private UUID storageLocationId;
    @Column(name = "storage_bin_id", updatable = false)
    private UUID storageBinId;
    @Column(name = "stock_batch_id", updatable = false)
    private UUID stockBatchId;
    @Column(name = "disposition_snapshot", nullable = false, updatable = false, length = 40)
    private String dispositionSnapshot;
    @Column(name = "uom_id_snapshot", updatable = false)
    private UUID uomIdSnapshot;
    @Column(name = "uom_code_snapshot", updatable = false, length = 80)
    private String uomCodeSnapshot;
    @Column(nullable = false, updatable = false, precision = 22, scale = 7)
    private BigDecimal quantity;
    @Column(name = "on_hand_delta", nullable = false, updatable = false, precision = 22, scale = 7)
    private BigDecimal onHandDelta;
    @Column(name = "available_delta", nullable = false, updatable = false, precision = 22, scale = 7)
    private BigDecimal availableDelta;
    @Column(name = "reserved_delta", nullable = false, updatable = false, precision = 22, scale = 7)
    private BigDecimal reservedDelta;
    @Column(name = "total_value_delta", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalValueDelta;
    @Column(name = "status_from", updatable = false, length = 50)
    private String statusFrom;
    @Column(name = "status_to", nullable = false, updatable = false, length = 50)
    private String statusTo;
    @Column(updatable = false, length = 500)
    private String reason;
    @Column(name = "evidence_reference", updatable = false, length = 500)
    private String evidenceReference;
}
