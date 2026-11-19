package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.InventoryDocumentResponse;
import com.enterprise.mm.payload.InventoryDocumentUpdateRequest;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryDocumentSearchRequest;
import com.enterprise.mm.payload.InventoryItemResponse;
import com.enterprise.mm.payload.InventoryScanCountRequest;
import com.enterprise.mm.payload.InventoryAdjustmentPostRequest;
import com.enterprise.mm.payload.InventoryAdjustmentPostingResponse;
import com.enterprise.mm.service.InventoryDocumentService;
import com.enterprise.mm.service.InventoryItemService;
import com.enterprise.mm.service.InventoryProcessingService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryDocumentService service;
    private final com.enterprise.mm.service.inventory.InventoryReviewService reviews;
    private final com.enterprise.mm.service.inventory.InventoryFoundStockService foundStock;

    @PostMapping("/{id}/return-for-correction")
    public ApiResponse<InventoryDocumentResponse> returnForCorrection(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.InventoryReviewDtos.ReturnForCorrection c) { return ApiResponse.ok(reviews.returnForCorrection(id, c)); }
    @PostMapping("/{id}/found-stock")
    public ApiResponse<InventoryItemResponse> foundStock(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.InventoryReviewDtos.FoundStock c) { return ApiResponse.ok(foundStock.add(id, c)); }
    @PostMapping("/{id}/unidentified-stock")
    public ApiResponse<com.enterprise.mm.entity.InventoryUnidentifiedObservation> unidentifiedStock(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.InventoryReviewDtos.UnidentifiedStock c) { return ApiResponse.ok(foundStock.unidentified(id, c)); }

    private final InventoryItemService itemService;
    private final com.enterprise.mm.service.inventory.InventoryCountBatchService counts;

    @PostMapping("/{id}/counts")
    public ApiResponse<com.enterprise.mm.payload.InventoryCountBatchDtos.BatchResult> saveCounts(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key, @RequestBody com.enterprise.mm.payload.InventoryCountBatchDtos.BatchCommand command) {
        return ApiResponse.ok(counts.save(id, command, key));
    }
    private final com.enterprise.mm.service.inventory.InventoryWorkspaceService workspace;
    private final com.enterprise.mm.service.inventory.InventoryScanResolutionService scans;

    @GetMapping("/{id}/workspace")
    public ApiResponse<com.enterprise.mm.payload.InventoryWorkspaceDtos.Context> workspace(@PathVariable UUID id) {
        return ApiResponse.ok(workspace.get(id));
    }

    @GetMapping("/{id}/batch-candidates")
    public ApiResponse<PageResponse<com.enterprise.mm.service.inventory.InventoryWorkspaceService.BatchIdentity>> batchCandidates(
        @PathVariable UUID id, @RequestParam UUID materialId, @RequestParam UUID warehouseId,
        @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(workspace.batchCandidates(id, materialId, warehouseId, search, page, size));
    }

    @GetMapping("/{id}/scan-candidates")
    public ApiResponse<List<com.enterprise.mm.payload.InventoryWorkspaceDtos.StockKeyOption>> scanCandidates(@PathVariable UUID id, @RequestParam String code) {
        return ApiResponse.ok(scans.resolve(id, code));
    }

    @GetMapping
    public ApiResponse<PageResponse<InventoryDocumentResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        InventoryDocumentSearchRequest request = new InventoryDocumentSearchRequest(search, status, warehouseId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryDocumentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<InventoryItemResponse>> items(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "false") boolean blindCount
    ) {
        return ApiResponse.ok(service.items(id, blindCount));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryDocumentResponse> create(@Valid @RequestBody InventoryDocumentCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryDocumentResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryDocumentUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryDocumentResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<InventoryDocumentResponse> start(@PathVariable UUID id) {
        return ApiResponse.ok(service.start(id));
    }

    @PostMapping("/{id}/complete-counting")
    public ApiResponse<InventoryDocumentResponse> completeCounting(@PathVariable UUID id) {
        return ApiResponse.ok(service.completeCounting(id));
    }

    @PostMapping("/{id}/submit-approval")
    public ApiResponse<InventoryDocumentResponse> submitForApproval(@PathVariable UUID id) {
        return ApiResponse.ok(service.submitForApproval(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<InventoryDocumentResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<InventoryDocumentResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @GetMapping("/{id}/mobile-lines")
    public ApiResponse<List<InventoryItemResponse>> mobileLines(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "ALL") String filter
    ) {
        return ApiResponse.ok(itemService.mobileLines(id, filter));
    }

    @PostMapping("/{id}/scan-count")
    public ApiResponse<InventoryItemResponse> scanCount(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody InventoryScanCountRequest request) {
        return ApiResponse.ok(itemService.scanAndCount(id, request, key));
    }

    @PostMapping("/{id}/generate-document")
    public ApiResponse<AttachmentDto> generateDocument(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "SUMMARY") String documentType
    ) {
        return ApiResponse.created(service.generateDocument(id, documentType));
    }

    private final InventoryProcessingService inventoryProcessingService;
    private final com.enterprise.mm.service.inventory.InventoryPostingReadinessService postingReadiness;
    @GetMapping("/{id}/posting-readiness")
    public ApiResponse<com.enterprise.mm.service.inventory.InventoryPostingReadinessService.Readiness> postingReadiness(@PathVariable UUID id, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) java.time.LocalDate postingDate) {
        return ApiResponse.ok(postingReadiness.get(id, postingDate));
    }


    @PostMapping("/{id}/post-adjustments")
    public ApiResponse<InventoryAdjustmentPostingResponse> postAdjustments(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody InventoryAdjustmentPostRequest request
    ) {
        return ApiResponse.ok(inventoryProcessingService.postAdjustments(id, request, idempotencyKey));
    }

}
