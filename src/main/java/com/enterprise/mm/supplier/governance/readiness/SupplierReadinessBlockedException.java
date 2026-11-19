package com.enterprise.mm.supplier.governance.readiness;

import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.ReadinessBlocker;

public final class SupplierReadinessBlockedException extends RuntimeException {
    private final ReadinessBlocker blocker;

    public SupplierReadinessBlockedException(ReadinessBlocker blocker) {
        super(blocker.message());
        this.blocker = blocker;
    }

    public ReadinessBlocker blocker() {
        return blocker;
    }
}
