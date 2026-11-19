package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractPaymentCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID contractId;
    private UUID contractInvoiceId;
    private UUID purchaseOrderId;
    private UUID supplierId;
    private String sourceDocumentType;
    private UUID sourceDocumentId;
    private String treasuryReference;
    private String paymentNumber;
    private LocalDate paymentDate;
    private BigDecimal amount;
    private String currencyCode;
    private String paymentMethod;
    private String bankReference;
}
