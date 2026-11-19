package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.entity.Supplier;

final class FakturaSupplierInn {
    private FakturaSupplierInn() {}

    static String from(Supplier supplier) {
        if (supplier == null) return null;
        String taxId = supplier.getTaxId();
        boolean branch = "BRANCH".equalsIgnoreCase(supplier.getSupplierType())
            || supplier.getTaxIdSuffix() != null
            || taxId != null && taxId.matches(".*_[0-9]+$");
        String inn = branch && supplier.getBaseTaxId() != null && !supplier.getBaseTaxId().isBlank()
            ? supplier.getBaseTaxId() : taxId;
        if (inn == null) return null;
        if (branch) inn = inn.replaceFirst("_[0-9]+$", "");
        String digits = inn.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }
}
