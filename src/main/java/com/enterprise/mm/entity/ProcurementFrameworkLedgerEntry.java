package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.ProcurementFrameworkLedgerType;
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
@Table(name = "procurement_framework_ledger")
public class ProcurementFrameworkLedgerEntry extends BusinessEntity {
    @Column(name = "framework_id", nullable = false) private UUID frameworkId;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30) private ProcurementFrameworkLedgerType transactionType;
    @Column(name = "call_off_reference", length = 160) private String callOffReference;
    @Column(name = "purchase_order_id") private UUID purchaseOrderId;
    @Column(name = "source_purchase_request_id") private UUID sourcePurchaseRequestId;
    @Column(name = "source_procedure_id") private UUID sourceProcedureId;
    @Column(name = "amount", nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(name = "currency_code", nullable = false, length = 3) private String currencyCode;
    @Column(name = "idempotency_key", nullable = false, length = 180) private String idempotencyKey;
    @Column(name = "reversal_reference") private UUID reversalReference;
    @Column(name = "occurred_at", nullable = false) private LocalDateTime occurredAt;
}
