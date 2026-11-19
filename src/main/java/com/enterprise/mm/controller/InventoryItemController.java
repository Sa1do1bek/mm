package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.InventoryItemResponse;
import com.enterprise.mm.payload.InventoryLineHistoryResponse;
import com.enterprise.mm.payload.InventoryItemUpdateRequest;
import com.enterprise.mm.payload.InventoryItemCreateRequest;
import com.enterprise.mm.payload.InventoryCountRequest;
import com.enterprise.mm.payload.InventoryDiscrepancyDecisionRequest;
import com.enterprise.mm.payload.InventoryExplanationRequest;
import com.enterprise.mm.payload.InventoryRecountRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryItemSearchRequest;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.InventoryItemService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/inventory-items")
@RequiredArgsConstructor
public class InventoryItemController {
    private final InventoryItemService service;
    private final com.enterprise.mm.service.inventory.InventoryReviewService reviews;
    @PostMapping("/{id}/decision")
    public ApiResponse<InventoryItemResponse> decide(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.InventoryReviewDtos.Decision c) { return ApiResponse.ok(reviews.decide(id, c)); }
    @PostMapping("/{id}/valuation")
    public ApiResponse<InventoryItemResponse> value(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.InventoryReviewDtos.Valuation c) { return ApiResponse.ok(reviews.valueFoundStock(id, c)); }

    private final AttachmentService attachmentService;

    @PostMapping(value = "/{id}/count-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentDto> uploadCountEvidence(@PathVariable UUID id,
        @RequestParam MultipartFile file,
        @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.created(attachmentService.upload("INVENTORY_LINE_COUNT_EVIDENCE", id, "EVIDENCE", file, key));
    }

    @GetMapping
    public ApiResponse<PageResponse<InventoryItemResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.util.UUID inventoryDocumentId,
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
        InventoryItemSearchRequest request = new InventoryItemSearchRequest(search, status, materialIds, inventoryDocumentId, warehouseId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryItemResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<InventoryLineHistoryResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(service.history(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryItemResponse> create(@Valid @RequestBody InventoryItemCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryItemResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryItemUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/count")
    public ApiResponse<InventoryItemResponse> count(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key, @Valid @RequestBody InventoryCountRequest request) {
        return ApiResponse.ok(service.count(id, request, key));
    }

    @PostMapping("/{id}/resolve-discrepancy")
    public ApiResponse<InventoryItemResponse> resolveDiscrepancy(@PathVariable UUID id, @RequestBody(required = false) InventoryDiscrepancyDecisionRequest request) {
        return ApiResponse.ok(service.resolveDiscrepancy(id, request));
    }

    @PostMapping("/{id}/request-explanation")
    public ApiResponse<InventoryItemResponse> requestExplanation(@PathVariable UUID id, @RequestBody(required = false) InventoryExplanationRequest request) {
        return ApiResponse.ok(service.requestExplanation(id, request));
    }

    @PostMapping("/{id}/submit-explanation")
    public ApiResponse<InventoryItemResponse> submitExplanation(@PathVariable UUID id, @RequestBody(required = false) InventoryExplanationRequest request) {
        return ApiResponse.ok(service.submitExplanation(id, request));
    }

    @PostMapping("/{id}/accept-explanation")
    public ApiResponse<InventoryItemResponse> acceptExplanation(@PathVariable UUID id, @RequestBody(required = false) InventoryExplanationRequest request) {
        return ApiResponse.ok(service.acceptExplanation(id, request));
    }

    @PostMapping("/{id}/reject-explanation")
    public ApiResponse<InventoryItemResponse> rejectExplanation(@PathVariable UUID id, @RequestBody(required = false) InventoryExplanationRequest request) {
        return ApiResponse.ok(service.rejectExplanation(id, request));
    }

    @PostMapping("/{id}/request-recount")
    public ApiResponse<InventoryItemResponse> requestRecount(@PathVariable UUID id, @RequestBody(required = false) InventoryRecountRequest request) {
        return ApiResponse.ok(service.requestRecount(id, request));
    }

    @GetMapping("/{id}/photos")
    public ApiResponse<List<AttachmentDto>> photos(@PathVariable UUID id) {
        service.get(id);
        return ApiResponse.ok(attachmentService.list("INVENTORY_ITEM", id));
    }

    @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentDto> uploadPhoto(@PathVariable UUID id, @RequestParam MultipartFile file) {
        service.get(id);
        return ApiResponse.created(attachmentService.upload("INVENTORY_ITEM", id, "PHOTO", file));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryItemResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

