package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
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
@Table(name = "procurement_award_outcome_lines")
public class ProcurementAwardOutcomeLine extends BusinessEntity {
    @Column(name = "procurement_award_outcome_id", nullable = false)
    private UUID procurementAwardOutcomeId;

    @Column(name = "procurement_procedure_id", nullable = false)
    private UUID procurementProcedureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private ProcurementAwardSourceType sourceType;

    @Column(name = "purchase_request_id", nullable = false)
    private UUID purchaseRequestId;

    @Column(name = "purchase_request_item_id", nullable = false)
    private UUID purchaseRequestItemId;

    @Column(name = "rfq_item_id")
    private UUID rfqItemId;

    @Column(name = "supplier_quotation_item_id")
    private UUID supplierQuotationItemId;

    @Column(name = "rfq_purchase_request_source_id")
    private UUID rfqPurchaseRequestSourceId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "purchase_order_item_id")
    private UUID purchaseOrderItemId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "contract_specification_id")
    private UUID contractSpecificationId;

    @Column(name = "awarded_quantity", nullable = false, precision = 22, scale = 7)
    private BigDecimal awardedQuantity = BigDecimal.ZERO;

    @Column(name = "unit_price", precision = 19, scale = 4)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "awarded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal awardedAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "idempotency_key", nullable = false, length = 220)
    private String idempotencyKey;
}
