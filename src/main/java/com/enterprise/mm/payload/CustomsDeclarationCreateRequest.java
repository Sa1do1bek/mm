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
public class CustomsDeclarationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String status;
    private String description;
    private UUID importDeliveryId;
    private UUID asnId;
    private UUID purchaseOrderId;
    private UUID supplierId;
    private String declarationNumber;
    private LocalDate declarationDate;
    private String customsPostCode;
    private String customsRegime;
    private String brokerName;
    private UUID tnvedCodeId;
    private String countryOfOrigin;
    private BigDecimal customsValue;
    private BigDecimal dutyAmount;
    private BigDecimal vatAmount;
    private BigDecimal exciseAmount;
    private BigDecimal feesAmount;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private BigDecimal totalCustomsAmount;
    private String currencyCode;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime submittedAt;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime releasedAt;
    private UUID attachmentId;
    private String remarks;
}
