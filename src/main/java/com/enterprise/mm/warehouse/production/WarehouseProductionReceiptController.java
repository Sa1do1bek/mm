package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptEvent;
import com.enterprise.mm.warehouse.production.ProductionReceiptCommands.ProductionReceiptResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Industrial Production Receipts (Account 2810 / Finished Goods intake from MES/Shopfloor).
 */
@RestController
@RequestMapping("/api/warehouse/production-receipts")
public class WarehouseProductionReceiptController {

    private final ProductionReceiptAdapterService receiptService;
    private final WarehouseProductionReceiptRepository receiptRepository;

    public WarehouseProductionReceiptController(
        ProductionReceiptAdapterService receiptService,
        WarehouseProductionReceiptRepository receiptRepository
    ) {
        this.receiptService = receiptService;
        this.receiptRepository = receiptRepository;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','PRODUCTION_OPERATOR')")
    public ResponseEntity<ApiResponse<ProductionReceiptResult>> receiveProduction(
        @Valid @RequestBody ProductionReceiptEvent event
    ) {
        return ResponseEntity.ok(ApiResponse.ok(receiptService.processProductionReceipt(event)));
    }

    @GetMapping("/events/{eventId}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','PRODUCTION_OPERATOR')")
    public ResponseEntity<ApiResponse<WarehouseProductionReceipt>> getByEventId(@PathVariable String eventId) {
        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        WarehouseProductionReceipt receipt = receiptRepository.findByTenantIdAndCompanyIdAndEventId(
            tenantId, companyId.toString(), eventId
        ).orElseThrow(() -> new NotFoundException("Production receipt not found for event: " + eventId));

        return ResponseEntity.ok(ApiResponse.ok(receipt));
    }
}
