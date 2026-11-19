package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contract_specifications")
public class ContractSpecification extends BusinessEntity {
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;
    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 20)
    private ProcurementLineType lineType = ProcurementLineType.MATERIAL;
    @Column(name = "material_id")
    private UUID materialId;
    @Column(name = "material_group_id")
    private UUID materialGroupId;
    @Column(name = "unit_of_measure_id")
    private UUID unitOfMeasureId;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type_snapshot", length = 30)
    private MaterialType materialTypeSnapshot;
    @Column(name = "service_description", columnDefinition = "text")
    private String serviceDescription;
    @Column(name = "service_category", length = 120)
    private String serviceCategory;
    @Column(length = 80)
    private String unit;
    @Column(length = 120)
    private String period;
    @Column(length = 120)
    private String stage;
    @Column(name = "service_limit", precision = 19, scale = 4)
    private BigDecimal serviceLimit = BigDecimal.ZERO;
    @Column(length = 120)
    private String sla;
    @Column(name = "pricing_model", length = 40)
    private String pricingModel;
    @Column(precision = 22, scale = 7)
    private BigDecimal quantityLimit = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal price = BigDecimal.ZERO;
    @Column(name = "source_rfq_item_id")
    private UUID sourceRfqItemId;
    @Column(name = "source_quotation_item_id")
    private UUID sourceQuotationItemId;
    @Column(name = "source_purchase_request_item_id")
    private UUID sourcePurchaseRequestItemId;
}
