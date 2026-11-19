package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdvanceShippingNoticeCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 3200)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private String asnNumber;
    private UUID supplierId;
    private UUID purchaseOrderId;
    private UUID contractId;
    private UUID incotermId;
    private LocalDate noticeDate;
    private LocalDate expectedShipDate;
    private LocalDate expectedArrivalDate;
    private String transportMode;
    private String carrierName;
    private String vehicleNumber;
    private String containerNumber;
    private String trackingNumber;
    private String originCountry;
    private String destinationCountry;
    private String loadingPoint;
    private String unloadingPoint;
    private BigDecimal totalQuantity;
    private BigDecimal totalAmount;
    private String currencyCode;
    private LocalDateTime sentAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime receivedAt;
    @Valid
    private List<AdvanceShippingNoticeItemCreateRequest> items;
}
