package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommercialInstrumentType;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractInvoiceCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    private String description;
    private UUID contractId;
    private CommercialInstrumentType commercialInstrumentType;
    private UUID purchaseOrderId;
    private UUID supplierId;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private String currencyCode;
    private List<UUID> evidenceAttachmentIds = List.of();
}
