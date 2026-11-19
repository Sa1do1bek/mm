package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.CommercialInstrumentType;
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
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "contract_invoices")
public class ContractInvoice extends BusinessEntity {
    @Column(name = "contract_id")
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "commercial_instrument_type", nullable = false, length = 40)
    private CommercialInstrumentType commercialInstrumentType = CommercialInstrumentType.CONTRACT_CALLOFF;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @NotNull
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "invoice_number", length = 120)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "paid_amount", precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        contractId = contract == null ? contractId : contract.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
    }
}
