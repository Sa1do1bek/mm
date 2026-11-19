package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionCommand;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionCorrectionResult;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReconciliationReport;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Production Yield Corrections & Reconciliation.
 */
@RestController
@RequestMapping("/api/warehouse/production-corrections")
public class WarehouseProductionCorrectionController {

    private final ProductionReconciliationService reconciliationService;

    public WarehouseProductionCorrectionController(ProductionReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<ProductionCorrectionResult>> applyCorrection(
        @Valid @RequestBody ProductionCorrectionCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(reconciliationService.applyProductionCorrection(command)));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<ProductionReconciliationReport>> getReconciliation(
        @RequestParam UUID materialId,
        @RequestParam UUID warehouseId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(reconciliationService.reconstructProductionReceiptTotals(materialId, warehouseId)));
    }
}
