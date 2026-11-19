package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomsDeclarationResponse extends BaseRecordDto {
    private UUID importDeliveryId;
    private String importDeliveryCode;
    private String importDeliveryName;
    private UUID asnId;
    private String asnCode;
    private String asnName;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String declarationNumber;
    private LocalDate declarationDate;
    private String customsPostCode;
    private String customsRegime;
    private String brokerName;
    private UUID tnvedCodeId;
    private String tnvedCodeCode;
    private String tnvedCodeName;
    private String countryOfOrigin;
    private BigDecimal customsValue;
    private BigDecimal dutyAmount;
    private BigDecimal vatAmount;
    private BigDecimal exciseAmount;
    private BigDecimal feesAmount;
    private BigDecimal totalCustomsAmount;
    private String currencyCode;
    private LocalDateTime submittedAt;
    private LocalDateTime releasedAt;
    private UUID attachmentId;
    private String remarks;
}