package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "damaged_stock_cases")
public class DamagedStockCase extends BusinessEntity {
    public static final String TYPE = "DAMAGED_STOCK";

    public enum Condition { DAMAGED, UNUSABLE }
    public enum Status { QUARANTINED, PENDING_APPROVAL, RETURNED, APPROVED, REJECTED, RELEASED, WRITTEN_OFF }

    @Column(name = "balance_id", nullable = false)
    private UUID balanceId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "balance_id", insertable = false, updatable = false)
    private StockBalance balance;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Condition condition;
    @Column(nullable = false, precision = 22, scale = 7)
    private BigDecimal quantity;
    @Column(name = "approval_instance_id")
    private UUID approvalInstanceId;
    @Column(name = "movement_id")
    private UUID movementId;
    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;
}
