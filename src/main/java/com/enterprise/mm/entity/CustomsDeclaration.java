package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "customs_declarations")
public class CustomsDeclaration extends BusinessEntity {
    @Column(name = "import_delivery_id")
    private UUID importDeliveryId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_delivery_id", insertable = false, updatable = false)
    private ImportDelivery importDelivery;

    @Column(name = "asn_id")
    private UUID asnId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id", insertable = false, updatable = false)
    private AdvanceShippingNotice asn;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "declaration_number", length = 120)
    private String declarationNumber;

    @Column(name = "declaration_date")
    private LocalDate declarationDate;

    @Column(name = "customs_post_code", length = 80)
    private String customsPostCode;

    @Column(name = "customs_regime", length = 80)
    private String customsRegime;

    @Column(name = "broker_name", length = 240)
    private String brokerName;

    @Column(name = "tnved_code_id")
    private UUID tnvedCodeId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tnved_code_id", insertable = false, updatable = false)
    private TnvedCode tnvedCode;

    @Column(name = "country_of_origin", length = 3)
    private String countryOfOrigin;

    @Column(name = "customs_value", precision = 19, scale = 4)
    private BigDecimal customsValue = BigDecimal.ZERO;

    @Column(name = "duty_amount", precision = 19, scale = 4)
    private BigDecimal dutyAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", precision = 19, scale = 4)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "excise_amount", precision = 19, scale = 4)
    private BigDecimal exciseAmount = BigDecimal.ZERO;

    @Column(name = "fees_amount", precision = 19, scale = 4)
    private BigDecimal feesAmount = BigDecimal.ZERO;

    @Column(name = "total_customs_amount", precision = 19, scale = 4)
    private BigDecimal totalCustomsAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(length = 1000)
    private String remarks;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        importDeliveryId = importDelivery == null ? importDeliveryId : importDelivery.getId();
        asnId = asn == null ? asnId : asn.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        supplierId = supplier == null ? supplierId : supplier.getId();
        tnvedCodeId = tnvedCode == null ? tnvedCodeId : tnvedCode.getId();
    }
}
