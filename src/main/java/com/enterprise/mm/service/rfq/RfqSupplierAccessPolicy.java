package com.enterprise.mm.service.rfq;

import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.enums.RfqPurpose;
import java.util.Locale;
import java.util.Set;

public final class RfqSupplierAccessPolicy {
    private static final Set<String> OPEN_SUPPLIER_ACCESS_METHODS = Set.of(
        ProcurementMethodType.OPEN_TENDER.name(),
        ProcurementMethodType.OPEN_COMPETITION.name()
    );

    private RfqSupplierAccessPolicy() {
    }

    public static boolean usesOpenSupplierAccess(RFQ rfq) {
        return rfq != null
            && RfqPurpose.PROCUREMENT.name().equals(normalize(rfq.getPurpose()))
            && OPEN_SUPPLIER_ACCESS_METHODS.contains(normalize(rfq.getTenderProcedureType()));
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
