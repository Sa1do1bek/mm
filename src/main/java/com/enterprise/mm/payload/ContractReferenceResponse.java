package com.enterprise.mm.payload;

import java.util.UUID;

/** Selector identity without contract amounts, balances, specifications or legal documents. */
public record ContractReferenceResponse(UUID id, String code, String name, String contractNumber,
    UUID supplierId, String supplierName, String status) {}
