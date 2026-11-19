package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportFreightBookingResponse extends BaseRecordDto {
    private String bookingNumber;
    private UUID importDeliveryId;
    private String importDeliveryCode;
    private String importDeliveryName;
    private UUID carrierSupplierId;
    private String carrierSupplierCode;
    private String carrierSupplierName;
    private UUID forwarderSupplierId;
    private String forwarderSupplierCode;
    private String forwarderSupplierName;
    private String transportMode;
    private String carrierName;
    private String forwarderName;
    private String originCountry;
    private String originPort;
    private String destinationCountry;
    private String destinationPlace;
    private String containerNumber;
    private String billOfLadingNumber;
    private String waybillNumber;
    private LocalDate plannedPickupDate;
    private LocalDate plannedDeliveryDate;
    private LocalDate actualPickupDate;
    private LocalDate actualDeliveryDate;
    private BigDecimal freightAmount;
    private BigDecimal fuelSurchargeAmount;
    private BigDecimal otherChargesAmount;
    private BigDecimal totalFreightAmount;
    private String currencyCode;
    private String allocationMethod;
    private boolean includedInInventoryCost;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private UUID landedCostComponentId;
    private String landedCostComponentCode;
    private String landedCostComponentName;
    private LocalDateTime postedAt;
}