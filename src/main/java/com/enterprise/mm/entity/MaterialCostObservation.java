package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "material_cost_observations")
public class MaterialCostObservation extends BusinessEntity {
    @Column(name = "material_id")
    private UUID materialId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", insertable = false, updatable = false)
    private Material material;
    @Column(name = "mxik_code", nullable = false, length = 40)
    private String mxikCode;
    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";
    @Column(precision = 22, scale = 7)
    private BigDecimal quantity;
    @Column(name = "source_type", nullable = false, length = 40)
    private String sourceType;
    @Column(name = "source_entity_id")
    private UUID sourceEntityId;
    @Column(name = "source_line_id", nullable = false)
    private UUID sourceLineId;
    @Column(name = "document_code", length = 120)
    private String documentCode;
    @Column(name = "supplier_id")
    private UUID supplierId;
    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;
}
