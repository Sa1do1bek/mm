package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractReconciliationActResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String actNumber;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate actDate;
    private BigDecimal openingBalance;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal closingBalance;
}