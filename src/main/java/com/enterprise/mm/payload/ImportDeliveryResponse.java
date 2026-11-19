package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportDeliveryResponse extends BaseRecordDto {
    private String shipmentNumber;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID asnId;
    private String asnCode;
    private String asnName;
    private UUID incotermId;
    private String incotermCode;
    private String incotermName;
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
    private String destinationWarehouseCode;
    private String destinationWarehouseName;
    private LocalDate etd;
    private LocalDate eta;
    private LocalDate actualArrivalDate;
    private UUID customsDeclarationId;
    private BigDecimal baseGoodsAmount;
    private BigDecimal logisticsCostAmount;
    private BigDecimal customsCostAmount;
    private BigDecimal landedCostAmount;
    private String currencyCode;
    private String currentMilestone;
    private LocalDateTime lastEventAt;
    private boolean riskFlag;
    private String riskReason;
}