package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdvanceShippingNoticeItemCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID asnId;
    private UUID purchaseOrderItemId;
    private UUID materialId;
    private BigDecimal quantity;
    private BigDecimal packageCount;
    private String packageType;
    private BigDecimal grossWeight;
    private BigDecimal netWeight;
    private String batchNumber;
    private String serialNumbers;
    private LocalDate expirationDate;
}
