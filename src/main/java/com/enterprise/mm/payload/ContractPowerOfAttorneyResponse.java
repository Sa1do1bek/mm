package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContractPowerOfAttorneyResponse extends BaseRecordDto {
    private UUID contractId;
    private String contractCode;
    private String contractName;
    private UUID supplierId;
    private String supplierCode;
    private String supplierName;
    private String attorneyNumber;
    private String issuedTo;
    private LocalDate issueDate;
    private LocalDate validTo;
    private BigDecimal amountLimit;
}