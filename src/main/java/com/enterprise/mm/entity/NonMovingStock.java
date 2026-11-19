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
@Table(name = "non_moving_stocks")
public class NonMovingStock extends BusinessEntity {
    @Column(name = "material_id", nullable = false)
    private UUID materialId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity;
    private Integer monthsWithoutMovement;
    private LocalDateTime detectedAt;
    @Column(length = 80)
    private String plannedAction;
    @Column(columnDefinition = "text")
    private String actionComment;
}
