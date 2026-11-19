package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Asserts the supplier-portal PO DTOs do NOT expose internal-only fields (approval
 * comments, budget details, warehouse-only data, internal cost/audit info) -- the
 * "portal DTO internal-field filtering" check from docs/CURRENT_TASK.md section 7.
 * Verification not run -- see final report.
 */
class SupplierPortalPurchaseOrderDtosFieldFilterTest {
    private static final List<String> FORBIDDEN_SUBSTRINGS = List.of(
        "internalcost", "approvalcomment", "budget", "warehouse", "auditlog",
        "createdby", "updatedby", "othersupplier", "securitytoken", "portaltokenhash"
    );

    private static final List<Class<?>> PORTAL_DTOS = List.of(
        SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderSummary.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderDetail.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderLine.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalResponseView.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalResponseLineView.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalCommitmentView.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalAsnView.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalAsnLineView.class,
        SupplierPortalPurchaseOrderDtos.SupplierPortalAttachmentView.class
    );

    @Test
    void portalDtosDoNotExposeInternalOnlyFields() {
        for (Class<?> dtoType : PORTAL_DTOS) {
            List<String> accessorNames = Arrays.stream(dtoType.getDeclaredMethods())
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
            for (String forbidden : FORBIDDEN_SUBSTRINGS) {
                assertThat(accessorNames)
                    .as("DTO %s must not expose an internal-only accessor matching '%s'", dtoType.getSimpleName(), forbidden)
                    .noneMatch(name -> name.contains(forbidden));
            }
        }
    }
}
