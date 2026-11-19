package com.enterprise.mm.warehouse.reconciliation;

import com.enterprise.mm.payload.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse/reconciliation")
public class WarehouseReconciliationController {
    private final WarehouseReconciliationService service;

    public WarehouseReconciliationController(WarehouseReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<WarehouseReconciliationResult> reconcile(
        @RequestParam(required = false) String accountCode,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) ReconciliationStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        var filter = new ReconciliationFilter(accountCode, warehouseId, materialId, status, page, size);
        return ApiResponse.ok(service.reconcile(filter));
    }
}
