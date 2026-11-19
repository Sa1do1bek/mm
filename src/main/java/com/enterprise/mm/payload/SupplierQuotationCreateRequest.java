package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierQuotationCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID rfqId;
    private UUID supplierId;
    private BigDecimal totalAmount;
    private String currencyCode;
    private LocalDateTime submittedAt;
    private boolean selected;
    private BigDecimal technicalScore;
    private BigDecimal deliveryScore;
    private BigDecimal priceScore;
    private BigDecimal totalScore;
    private String selectionReason;
    @Valid
    private List<SupplierQuotationItemCreateRequest> items;
}
