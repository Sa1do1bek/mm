package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "consignment_stock_transactions")
public class ConsignmentStockTransaction extends BusinessEntity {
    @Column(name = "vmi_agreement_id", nullable = false)
    private UUID vmiAgreementId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "stock_movement_id")
    private UUID stockMovementId;

    @Column(name = "transaction_type", nullable = false, length = 40)
    private String transactionType;

    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "settlement_status", length = 40)
    private String settlementStatus;

    @Column(name = "settlement_reference", length = 160)
    private String settlementReference;

    @Column(name = "transaction_at")
    private LocalDateTime transactionAt;
}