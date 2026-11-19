package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportFreightBookingCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private String bookingNumber;
    private UUID importDeliveryId;
    private UUID carrierSupplierId;
    private UUID forwarderSupplierId;
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
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDate actualPickupDate;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDate actualDeliveryDate;
    private BigDecimal freightAmount;
    private BigDecimal fuelSurchargeAmount;
    private BigDecimal otherChargesAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal totalFreightAmount;
    private String currencyCode;
    private String allocationMethod;
    private boolean includedInInventoryCost;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID landedCostComponentId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime postedAt;
}
