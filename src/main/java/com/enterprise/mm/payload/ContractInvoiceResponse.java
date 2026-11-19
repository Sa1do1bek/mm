package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommercialInstrumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractInvoiceResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private CommercialInstrumentType commercialInstrumentType;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String currencyCode;
}
