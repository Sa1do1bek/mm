package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MobileWarehouseOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "mobile_warehouse_operations")
public class MobileWarehouseOperation extends BusinessEntity {
    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    private MobileWarehouseOperationType operationType;
    private UUID materialId;
    private UUID warehouseId;
    private UUID targetWarehouseId;
    private UUID storageLocationId;
    private UUID targetStorageLocationId;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    private UUID stockMovementId;
    @Column(length = 160)
    private String barcodeOrRfid;
    private boolean signed;
    @Column(columnDefinition = "text")
    private String signaturePayload;
    private LocalDateTime signedAt;
}
