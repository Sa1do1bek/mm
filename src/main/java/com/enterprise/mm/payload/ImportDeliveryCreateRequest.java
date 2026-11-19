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
public class ImportDeliveryCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private String shipmentNumber;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID asnId;
    private UUID incotermId;
    private boolean importFlag;
    private String transportMode;
    private String carrierName;
    private String forwarderName;
    private String brokerName;
    private String originCountry;
    private String originPort;
    private String borderPost;
    private String destinationCountry;
    private String destinationPlace;
    private UUID destinationWarehouseId;
    private LocalDate etd;
    private LocalDate eta;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDate actualArrivalDate;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private UUID customsDeclarationId;
    private BigDecimal baseGoodsAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal logisticsCostAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal customsCostAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal landedCostAmount;
    private String currencyCode;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String currentMilestone;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime lastEventAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private boolean riskFlag;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String riskReason;
}
