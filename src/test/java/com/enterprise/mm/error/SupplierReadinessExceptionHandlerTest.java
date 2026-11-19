package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessBlockedException;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SupplierReadinessExceptionHandlerTest {
    @Test
    void returnsStructuredReadinessBlockerWith422Status() {
        ReadinessBlocker blocker = new ReadinessBlocker(
            "APPROVED_SCOPE_REQUIRED",
            StageKey.DECISION,
            null,
            null,
            "Approved scope required",
            "Record an approval",
            "SUPPLIER_MANAGER",
            null
        );

        var response = new GlobalExceptionHandler()
            .readinessBlocked(new SupplierReadinessBlockedException(blocker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(blocker);
        assertThat(response.getBody().errorCode()).isEqualTo("APPROVED_SCOPE_REQUIRED");
    }
}
