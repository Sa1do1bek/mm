package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;
    @Column(name = "purchase_request_id")
    private UUID purchaseRequestId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_request_id", insertable = false, updatable = false)
    private PurchaseRequest purchaseRequest;
    @Column(name = "contract_id")
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;
    private LocalDate orderDate;
    private LocalDate expectedDeliveryDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", length = 20)
    private RequestType requestType = RequestType.GOODS;
    @Column(precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;
    @Column(length = 3)
    private String currencyCode = "UZS";
    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate = BigDecimal.ONE;

    @PrePersist
    @PreUpdate
    void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseRequestId = purchaseRequest == null ? purchaseRequestId : purchaseRequest.getId();
        contractId = contract == null ? contractId : contract.getId();
    }
}
