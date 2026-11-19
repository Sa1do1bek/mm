package com.enterprise.mm.payload;

public record SupplierSummary(  long total,
                                long blocked,
                                long withoutBankAccount,
                                long withoutTaxId,
                                long newThisMonth,
                                long headOfficeSuppliers,
                                long branchSuppliers) {
}
