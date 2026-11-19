package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommercialInstrumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractPaymentResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private CommercialInstrumentType commercialInstrumentType;
    private UUID contractInvoiceId;
    private String contractInvoiceCode;
    private String contractInvoiceNumber;
    private UUID purchaseOrderId;
    private String purchaseOrderCode;
    private String purchaseOrderName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
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
