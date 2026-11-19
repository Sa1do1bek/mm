package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import com.enterprise.mm.enums.RequestType;
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
@Table(name = "contract_invoice_evidence_snapshots")
public class ContractInvoiceEvidenceSnapshot extends BusinessEntity {
    @Column(name = "contract_invoice_id", nullable = false, updatable = false)
    private UUID contractInvoiceId;

    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commercial_instrument_type", length = 40, nullable = false, updatable = false)
    private CommercialInstrumentType commercialInstrumentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", length = 30, nullable = false, updatable = false)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_document_type", length = 64, updatable = false)
    private ProcurementSourceDocumentType sourceDocumentType;

    @Column(name = "source_document_id", updatable = false)
    private UUID sourceDocumentId;

    @Column(name = "evidence_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal evidenceAmount = BigDecimal.ZERO;

    @Column(name = "evidence_currency_code", length = 12, updatable = false)
    private String evidenceCurrencyCode;

    @Column(name = "evidence_exchange_rate", precision = 19, scale = 6, updatable = false)
    private BigDecimal evidenceExchangeRate;

    @Column(name = "evidence_exchange_rate_source", length = 120, updatable = false)
    private String evidenceExchangeRateSource;

    @Column(name = "acquisition_evidence_type", length = 64, updatable = false)
    private String acquisitionEvidenceType;

    @Column(name = "invoiced_amount_before", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal invoicedAmountBefore = BigDecimal.ZERO;

    @Column(name = "invoice_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal invoiceAmount = BigDecimal.ZERO;

    @Column(name = "remaining_invoiceable_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal remainingInvoiceableAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 12, nullable = false, updatable = false)
    private String currencyCode;

    @Column(name = "invoice_status_snapshot", length = 50, updatable = false)
    private String invoiceStatusSnapshot;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;
}
