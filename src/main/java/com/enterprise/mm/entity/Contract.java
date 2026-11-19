package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contracts")
public class Contract extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;
    @Column(length = 120)
    private String contractNumber;
    private LocalDate startDate;
    private LocalDate endDate;
    @Column(precision = 19, scale = 4)
    private BigDecimal limitAmount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 4)
    private BigDecimal usedAmount = BigDecimal.ZERO;
    @Column(name = "service_used_amount", precision = 19, scale = 4)
    private BigDecimal serviceUsedAmount = BigDecimal.ZERO;
    @Column(length = 3)
    private String currencyCode = "UZS";
    @Column(name = "source_rfq_id")
    private UUID sourceRfqId;
    @Column(name = "source_quotation_id")
    private UUID sourceQuotationId;
}
