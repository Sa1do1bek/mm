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
@Table(name = "import_freight_bookings")
public class ImportFreightBooking extends BusinessEntity {
    @Column(name = "booking_number", length = 120)
    private String bookingNumber;

    @Column(name = "import_delivery_id", nullable = false)
    private UUID importDeliveryId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_delivery_id", insertable = false, updatable = false)
    private ImportDelivery importDelivery;

    @Column(name = "carrier_supplier_id")
    private UUID carrierSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carrier_supplier_id", insertable = false, updatable = false)
    private Supplier carrierSupplier;

    @Column(name = "forwarder_supplier_id")
    private UUID forwarderSupplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "forwarder_supplier_id", insertable = false, updatable = false)
    private Supplier forwarderSupplier;

    @Column(name = "transport_mode", length = 40)
    private String transportMode;

    @Column(name = "carrier_name", length = 240)
    private String carrierName;

    @Column(name = "forwarder_name", length = 240)
    private String forwarderName;

    @Column(name = "origin_country", length = 3)
    private String originCountry;

    @Column(name = "origin_port", length = 240)
    private String originPort;

    @Column(name = "destination_country", length = 3)
    private String destinationCountry;

    @Column(name = "destination_place", length = 240)
    private String destinationPlace;

    @Column(name = "container_number", length = 120)
    private String containerNumber;

    @Column(name = "bill_of_lading_number", length = 120)
    private String billOfLadingNumber;

    @Column(name = "waybill_number", length = 120)
    private String waybillNumber;

    @Column(name = "planned_pickup_date")
    private LocalDate plannedPickupDate;

    @Column(name = "planned_delivery_date")
    private LocalDate plannedDeliveryDate;

    @Column(name = "actual_pickup_date")
    private LocalDate actualPickupDate;

    @Column(name = "actual_delivery_date")
    private LocalDate actualDeliveryDate;

    @Column(name = "freight_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal freightAmount = BigDecimal.ZERO;

    @Column(name = "fuel_surcharge_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal fuelSurchargeAmount = BigDecimal.ZERO;

    @Column(name = "other_charges_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal otherChargesAmount = BigDecimal.ZERO;

    @Column(name = "total_freight_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalFreightAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "UZS";

    @Column(name = "allocation_method", nullable = false, length = 40)
    private String allocationMethod = "VALUE";

    @Column(name = "included_in_inventory_cost", nullable = false)
    private boolean includedInInventoryCost = true;

    @Column(name = "invoice_number", length = 120)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "landed_cost_component_id")
    private UUID landedCostComponentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "landed_cost_component_id", insertable = false, updatable = false)
    private LandedCostComponent landedCostComponent;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        importDeliveryId = importDelivery == null ? importDeliveryId : importDelivery.getId();
        carrierSupplierId = carrierSupplier == null ? carrierSupplierId : carrierSupplier.getId();
        forwarderSupplierId = forwarderSupplier == null ? forwarderSupplierId : forwarderSupplier.getId();
        landedCostComponentId = landedCostComponent == null ? landedCostComponentId : landedCostComponent.getId();
    }
}
