package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class ServiceAcceptanceActPrefillResponse {
    private UUID serviceRequestId;
    private String serviceRequestName;
    private UUID serviceOrderId;
    private String serviceOrderName;
    
    private String name;
    private String stage;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    
    private BigDecimal amount;
    private BigDecimal vatRate;
    private BigDecimal vatAmount;
    
    private UUID purchaseOrderId;
    private UUID contractId;
    private String currencyCode;
}
