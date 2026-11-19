package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "budget_transactions")
public class BudgetTransaction extends BusinessEntity {
    @Column(name = "budget_item_id", nullable = false)
    private UUID budgetItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_item_id", insertable = false, updatable = false)
    private BudgetItem budgetItem;

    @Column(name = "transaction_type", nullable = false, length = 50)
    private String transactionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "reserved_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedDelta = BigDecimal.ZERO;

    @Column(name = "committed_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal committedDelta = BigDecimal.ZERO;

    @Column(name = "used_delta", nullable = false, precision = 19, scale = 4)
    private BigDecimal usedDelta = BigDecimal.ZERO;

    @Column(name = "consumed_from_reserved", nullable = false, precision = 19, scale = 4)
    private BigDecimal consumedFromReserved = BigDecimal.ZERO;

    @Column(name = "consumed_from_committed", nullable = false, precision = 19, scale = 4)
    private BigDecimal consumedFromCommitted = BigDecimal.ZERO;

    @Column(name = "consumed_directly", nullable = false, precision = 19, scale = 4)
    private BigDecimal consumedDirectly = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    @Column(name = "reversal_of_transaction_id")
    private UUID reversalOfTransactionId;

    @Column(name = "source_type", nullable = false, length = 80)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "source_line_id")
    private UUID sourceLineId;

    @Column(name = "source_key", nullable = false, length = 220)
    private String sourceKey;

    @Column(name = "action_key", nullable = false, length = 120)
    private String actionKey;

    @Column(name = "idempotency_key", nullable = false, length = 400)
    private String idempotencyKey;
}
