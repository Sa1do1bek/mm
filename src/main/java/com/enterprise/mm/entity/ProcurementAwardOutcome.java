package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
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
@Table(name = "procurement_award_outcomes")
public class ProcurementAwardOutcome extends BusinessEntity {
    @Column(name = "procurement_procedure_id", nullable = false)
    private UUID procurementProcedureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private ProcurementAwardSourceType sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "awarded_supplier_id")
    private UUID awardedSupplierId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(name = "award_amount", precision = 19, scale = 4)
    private BigDecimal awardAmount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "award_reference", length = 160)
    private String awardReference;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Column(name = "finalized_at", nullable = false)
    private LocalDateTime finalizedAt;
}
