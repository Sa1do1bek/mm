package com.enterprise.mm.warehouse.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseReconciliationService {
    private final WarehouseReconciliationQuery query;

    public WarehouseReconciliationService(WarehouseReconciliationQuery query) {
        this.query = query;
    }

    @Transactional(readOnly = true)
    public WarehouseReconciliationResult reconcile(ReconciliationFilter filter) {
        return query.reconcile(filter);
    }
}
