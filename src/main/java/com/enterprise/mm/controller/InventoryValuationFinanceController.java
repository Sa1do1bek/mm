package com.enterprise.mm.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryCostLayer;
import com.enterprise.mm.entity.StockCostMovement;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryCostLayerResponse;
import com.enterprise.mm.payload.InventoryValuationReconciliationIssueResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.StockCostMovementResponse;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.service.InventoryValuationReconciliationService;
import com.enterprise.mm.common.PaginationUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/inventory-valuation", "/api/v1/inventory-valuation"})
@RequiredArgsConstructor
public class InventoryValuationFinanceController {
    private final InventoryCostLayerRepository costLayerRepository;
    private final StockCostMovementRepository costMovementRepository;
    private final InventoryValuationReconciliationService reconciliationService;

    @GetMapping("/cost-layers")
    public ApiResponse<PageResponse<InventoryCostLayerResponse>> costLayers(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("receivedAt").descending());
        return ApiResponse.ok(PageResponse.from(costLayerRepository.searchFinance(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId, materialId, stockBatchId, dateFrom, dateTo, pageable
        ).map(this::toLayerResponse)));
    }

    @GetMapping("/cost-layers/{id}")
    public ApiResponse<InventoryCostLayerResponse> costLayer(@PathVariable UUID id) {
        return costLayerRepository.findByIdAndIsDeletedFalse(id)
            .filter(layer -> TenantContext.currentTenant().equals(layer.getTenantId()))
            .filter(layer -> CompanyContext.currentCompany().equals(layer.getCompanyId()))
            .map(this::toLayerResponse)
            .map(ApiResponse::ok)
            .orElseThrow(() -> new com.enterprise.mm.error.NotFoundException("Cost layer not found: " + id));
    }

    @GetMapping("/cost-movements")
    public ApiResponse<PageResponse<StockCostMovementResponse>> costMovements(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID stockMovementId,
        @RequestParam(required = false) UUID costLayerId,
        @RequestParam(required = false) String movementType,
        @RequestParam(required = false) String sourceDocumentType,
        @RequestParam(required = false) UUID sourceDocumentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(costMovementRepository.searchFinance(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId, materialId, stockMovementId, costLayerId,
            movementType, sourceDocumentType, sourceDocumentId, dateFrom, dateTo, pageable
        ).map(this::toMovementResponse)));
    }

    @GetMapping("/cost-movements/{id}")
    public ApiResponse<StockCostMovementResponse> costMovement(@PathVariable UUID id) {
        return costMovementRepository.findByIdAndIsDeletedFalse(id)
            .filter(entry -> TenantContext.currentTenant().equals(entry.getTenantId()))
            .filter(entry -> CompanyContext.currentCompany().equals(entry.getCompanyId()))
            .map(this::toMovementResponse)
            .map(ApiResponse::ok)
            .orElseThrow(() -> new com.enterprise.mm.error.NotFoundException("Cost movement not found: " + id));
    }

    @GetMapping("/exceptions")
    public ApiResponse<PageResponse<InventoryValuationReconciliationIssueResponse>> exceptions(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String severity,
        @RequestParam(required = false) String issueType,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(pageIssues(reconciliationService.run(warehouseId), severity, issueType, page, size));
    }

    @GetMapping("/batches/{batchId}/cost-history")
    public ApiResponse<List<StockCostMovementResponse>> batchCostHistory(@PathVariable UUID batchId) {
        Set<UUID> layerIds = costLayerRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()).stream()
            .filter(layer -> batchId.equals(layer.getStockBatchId()))
            .map(InventoryCostLayer::getId)
            .collect(java.util.stream.Collectors.toSet());
        return ApiResponse.ok(costMovementRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany()).stream()
            .filter(entry -> entry.getCostLayerId() != null && layerIds.contains(entry.getCostLayerId()))
            .map(this::toMovementResponse)
            .toList());
    }

    private InventoryCostLayerResponse toLayerResponse(InventoryCostLayer layer) {
        return new InventoryCostLayerResponse(
            layer.getId(), layer.getMaterialId(), layer.getWarehouseId(), layer.getStockBatchId(),
            layer.getSourceMovementId(), layer.getSourceDocumentType(), layer.getSourceDocumentId(),
            layer.getParentCostLayerId(), layer.getTransferTransactionId(), layer.getSourceWarehouseId(), layer.getTargetWarehouseId(),
            layer.getSourceStockMovementId(), layer.getTargetStockMovementId(), layer.getLayerType(), layer.getStatus(),
            layer.getReceivedQuantity(), layer.getRemainingQuantity(), layer.getUnitCost(), layer.getTotalCost(),
            layer.getCurrencyCode(), layer.getExchangeRate(), layer.getReceivedAt()
        );
    }

    private StockCostMovementResponse toMovementResponse(StockCostMovement entry) {
        return new StockCostMovementResponse(
            entry.getId(), entry.getMaterialId(), entry.getWarehouseId(), entry.getStockMovementId(),
            entry.getCostLayerId(), entry.getMovementType(), entry.getQuantity(), entry.getUnitCost(),
            entry.getTotalCost(), entry.getCostCenterId(), entry.getSourceDocumentType(), entry.getSourceDocumentId(),
            entry.getTransferTransactionId(), entry.isReversal(), entry.getReversalOfId(), entry.getReversalReason(),
            entry.getReversedAt(), entry.getReversedBy(), entry.getCreatedAt(), entry.getCreatedBy()
        );
    }

    private PageResponse<InventoryValuationReconciliationIssueResponse> pageIssues(
        List<InventoryValuationReconciliationIssueResponse> issues,
        String severity,
        String issueType,
        int page,
        int size
    ) {
        List<InventoryValuationReconciliationIssueResponse> filtered = issues.stream()
            .filter(issue -> severity == null || severity.equalsIgnoreCase(issue.severity()))
            .filter(issue -> issueType == null || issueType.equalsIgnoreCase(issue.issueType()))
            .toList();
        int safeSize = Math.max(1, Math.min(size, com.enterprise.mm.common.PaginationUtils.MAX_PAGE_SIZE));
        int from = Math.min(Math.max(page, 0) * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new PageResponse<>(filtered.subList(from, to), Math.max(page, 0), safeSize, filtered.size(), (int) Math.ceil(filtered.size() / (double) safeSize));
    }
}
