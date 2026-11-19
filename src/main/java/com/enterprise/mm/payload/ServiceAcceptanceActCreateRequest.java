package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceAcceptanceActCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID serviceRequestId;
    private UUID serviceOrderId;
    private UUID purchaseOrderId;
    private UUID contractId;
    private LocalDateTime acceptedAt;
    private String acceptedBy;
    private BigDecimal amount;
    private BigDecimal vatAmount;
    private BigDecimal vatRate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String stage;
    private String commissionNotes;
    private String remarks;
    private String paymentReference;
}
