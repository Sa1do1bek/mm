package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.UUID;

public record ManualIssueContractOptionResponse(
    UUID id,
    String code,
    String contractNumber,
    String name,
    UUID supplierId,
    String supplierCode,
    String supplierName,
    String currencyCode,
    LocalDate startDate,
    LocalDate endDate,
    String status,
    int materialCount
) {}
