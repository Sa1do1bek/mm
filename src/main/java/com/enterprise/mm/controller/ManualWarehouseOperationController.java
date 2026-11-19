package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.ManualWarehouseOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/manual")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class ManualWarehouseOperationController {
    private final ManualWarehouseOperationService service;
    private final com.enterprise.mm.service.ManualAssetReceiptService assetReceipts;

    @GetMapping("/counterpart-warehouses")
    public ApiResponse<PageResponse<ManualWarehouseOperationService.WarehouseOption>> counterpartWarehouses(
        @RequestParam java.util.UUID warehouseId, @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.counterpartWarehouses(warehouseId, search,
            com.enterprise.mm.common.PaginationUtils.pageRequest(page, size,
                org.springframework.data.domain.Sort.by("name").ascending())));
    }

    @GetMapping("/contracts")
    public ApiResponse<PageResponse<ManualIssueContractOptionResponse>> contracts(
        @RequestParam java.util.UUID warehouseId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String contractNumber,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.searchContractsForIssue(
            warehouseId,
            search,
            code,
            contractNumber,
            supplierId,
            PaginationUtils.pageRequest(page, size,
                Sort.by("contractNumber").ascending())
        ));
    }

    @GetMapping("/contracts/{contractId}/issue-context")
    public ApiResponse<ContractManualIssueContextResponse> contractIssueContext(
        @PathVariable UUID contractId,
        @RequestParam UUID warehouseId
    ) {
        return ApiResponse.ok(service.contractManualIssueContext(warehouseId, contractId));
    }

    @GetMapping("/contracts/{contractId}/receipt-context")
    public ApiResponse<ContractManualIssueContextResponse> contractReceiptContext(
        @PathVariable UUID contractId, @RequestParam UUID warehouseId) {
        return ApiResponse.ok(service.contractManualReceiptContext(warehouseId, contractId));
    }

    @GetMapping("/contracts/{contractId}/specifications/{specificationId}/materials")
    public ApiResponse<PageResponse<ContractManualIssueContextResponse.ContractManualIssueLineResponse>> contractSpecificationMaterials(
        @PathVariable UUID contractId, @PathVariable UUID specificationId, @RequestParam UUID warehouseId,
        @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.contractSpecificationMaterials(warehouseId, contractId, specificationId, search,
            PaginationUtils.pageRequest(page, size, Sort.by("name").ascending().and(Sort.by("id").ascending()))));
    }

    @PostMapping("/receipt-batch")
    public ApiResponse<ManualWarehouseDocumentResponse> receiptBatch(@Valid @RequestBody ManualWarehouseDocumentRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.receiptBatch(request, key));
    }

    @PostMapping("/issue-batch")
    public ApiResponse<ManualWarehouseDocumentResponse> issueBatch(@Valid @RequestBody ManualWarehouseDocumentRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        throw new com.enterprise.mm.error.BusinessException(
            "Select an approved material issue request to issue stock", "issueRequest.required");
    }

    @PostMapping("/receipt")
    public ApiResponse<StockMovementResponse> receipt(@Valid @RequestBody ManualWarehouseOperationRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(service.receipt(request, key));
    }

    @PostMapping("/issue")
    public ApiResponse<StockMovementResponse> issue(@Valid @RequestBody ManualWarehouseOperationRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        throw new com.enterprise.mm.error.BusinessException(
            "Select an approved material issue request to issue stock", "issueRequest.required");
    }

    @GetMapping("/asset-receipts")
    public ApiResponse<PageResponse<AssetAcceptanceResponse>> assetReceipts(@RequestParam java.util.UUID warehouseId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(assetReceipts.pending(warehouseId,
            com.enterprise.mm.common.PaginationUtils.pageRequest(page, size,
                org.springframework.data.domain.Sort.by("createdAt").descending())));
    }

    @PostMapping("/asset-receipt")
    public ApiResponse<AssetAcceptanceResponse> assetReceipt(@Valid @RequestBody ManualWarehouseOperationRequest request,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(assetReceipts.create(request, key));
    }
}
