package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "non_moving_stock_cases")
public class NonMovingStockCase extends BusinessEntity {
    @Column(length = 80, nullable = false)
    private String caseNo;
    private LocalDateTime detectedAt;
    private LocalDate reviewDueDate;
    @Column(precision = 22, scale = 7)
    private BigDecimal totalQuantity = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalBookValue = BigDecimal.ZERO;
    @Column(length = 120)
    private String responsibleUser;
    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;
    @Column(name = "responsible_username", length = 120)
    private String responsibleUsername;
    @Column(name = "accounting_currency_code", length = 3)
    private String accountingCurrencyCode;
    @Column(name = "valuation_health", length = 20, nullable = false)
    private String valuationHealth = "PARTIAL";
    @Column(name = "valuation_health_message", columnDefinition = "text")
    private String valuationHealthMessage =
        "Valuation provenance was not explicitly captured; review the persisted accounting currency before use.";
    @Column(name = "blocker_type", length = 80)
    private String blockerType;
    @Column(name = "blocker_details", columnDefinition = "text")
    private String blockerDetails;
    private LocalDateTime closedAt;
    @Column(length = 120)
    private String closedBy;
    @Column(columnDefinition = "text")
    private String closureReason;
    private LocalDateTime cancelledAt;
    @Column(length = 120)
    private String cancelledBy;
    @Column(columnDefinition = "text")
    private String cancellationReason;
    private LocalDateTime reopenedAt;
    @Column(length = 120)
    private String reopenedBy;
    @Column(columnDefinition = "text")
    private String reopenReason;
    @Column(name = "commission_id")
    private UUID commissionId;
    @Column(length = 120)
    private String commissionProtocolNo;
}
