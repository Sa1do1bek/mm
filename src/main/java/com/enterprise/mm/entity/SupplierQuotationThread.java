package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_quotation_threads")
public class SupplierQuotationThread extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "current_quotation_id")
    private UUID currentQuotationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_quotation_id", insertable = false, updatable = false)
    private SupplierQuotation currentQuotation;
}
