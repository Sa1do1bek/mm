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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "advance_shipping_notices")
public class AdvanceShippingNotice extends BusinessEntity {
    @Column(name = "asn_number", length = 120)
    private String asnNumber;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "contract_id")
    private UUID contractId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    @Column(name = "incoterm_id")
    private UUID incotermId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incoterm_id", insertable = false, updatable = false)
    private Incoterm incoterm;

    @Column(name = "notice_date")
    private LocalDate noticeDate;

    @Column(name = "expected_ship_date")
    private LocalDate expectedShipDate;

    @Column(name = "expected_arrival_date")
    private LocalDate expectedArrivalDate;

    @Column(name = "transport_mode", length = 40)
    private String transportMode;

    @Column(name = "carrier_name", length = 240)
    private String carrierName;

    @Column(name = "vehicle_number", length = 120)
    private String vehicleNumber;

    @Column(name = "container_number", length = 120)
    private String containerNumber;

    @Column(name = "tracking_number", length = 160)
    private String trackingNumber;

    @Column(name = "origin_country", length = 3)
    private String originCountry;

    @Column(name = "destination_country", length = 3)
    private String destinationCountry;

    @Column(name = "loading_point", length = 240)
    private String loadingPoint;

    @Column(name = "unloading_point", length = 240)
    private String unloadingPoint;

    @Column(name = "total_quantity", precision = 22, scale = 7)
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** INTERNAL, SUPPLIER_PORTAL, or INTEGRATION. Defaults to INTERNAL for legacy rows. */
    @Column(length = 30)
    private String source = "INTERNAL";

    @Column(name = "portal_account_id")
    private UUID portalAccountId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_account_id", insertable = false, updatable = false)
    private SupplierPortalAccount portalAccount;
}
