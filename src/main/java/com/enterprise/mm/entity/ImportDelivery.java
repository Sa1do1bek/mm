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
@Table(name = "import_deliveries")
public class ImportDelivery extends BusinessEntity {
    @Column(name = "shipment_number", length = 120)
    private String shipmentNumber;

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

    @Column(name = "asn_id")
    private UUID asnId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id", insertable = false, updatable = false)
    private AdvanceShippingNotice asn;

    @Column(name = "incoterm_id")
    private UUID incotermId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incoterm_id", insertable = false, updatable = false)
    private Incoterm incoterm;

    @Column(name = "import_flag", nullable = false)
    private boolean importFlag = true;

    @Column(name = "transport_mode", length = 40)
    private String transportMode;

    @Column(name = "carrier_name", length = 240)
    private String carrierName;

    @Column(name = "forwarder_name", length = 240)
    private String forwarderName;

    @Column(name = "broker_name", length = 240)
    private String brokerName;

    @Column(name = "origin_country", length = 3)
    private String originCountry;

    @Column(name = "origin_port", length = 240)
    private String originPort;

    @Column(name = "border_post", length = 240)
    private String borderPost;

    @Column(name = "destination_country", length = 3)
    private String destinationCountry;

    @Column(name = "destination_place", length = 240)
    private String destinationPlace;

    @Column(name = "destination_warehouse_id")
    private UUID destinationWarehouseId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_warehouse_id", insertable = false, updatable = false)
    private Warehouse destinationWarehouse;

    private LocalDate etd;
    private LocalDate eta;

    @Column(name = "actual_arrival_date")
    private LocalDate actualArrivalDate;

    @Column(name = "customs_declaration_id")
    private UUID customsDeclarationId;

    @Column(name = "base_goods_amount", precision = 19, scale = 4)
    private BigDecimal baseGoodsAmount = BigDecimal.ZERO;

    @Column(name = "logistics_cost_amount", precision = 19, scale = 4)
    private BigDecimal logisticsCostAmount = BigDecimal.ZERO;

    @Column(name = "customs_cost_amount", precision = 19, scale = 4)
    private BigDecimal customsCostAmount = BigDecimal.ZERO;

    @Column(name = "landed_cost_amount", precision = 19, scale = 4)
    private BigDecimal landedCostAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "UZS";

    @Column(name = "current_milestone", length = 80)
    private String currentMilestone;

    @Column(name = "last_event_at")
    private LocalDateTime lastEventAt;

    @Column(name = "risk_flag", nullable = false)
    private boolean riskFlag;

    @Column(name = "risk_reason", length = 1000)
    private String riskReason;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        supplierId = supplier == null ? supplierId : supplier.getId();
        purchaseOrderId = purchaseOrder == null ? purchaseOrderId : purchaseOrder.getId();
        asnId = asn == null ? asnId : asn.getId();
        incotermId = incoterm == null ? incotermId : incoterm.getId();
        destinationWarehouseId = destinationWarehouse == null ? destinationWarehouseId : destinationWarehouse.getId();
    }
}
