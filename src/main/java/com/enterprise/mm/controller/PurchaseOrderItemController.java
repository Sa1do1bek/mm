package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderItemResponse;
import com.enterprise.mm.payload.PurchaseOrderItemUpdateRequest;
import com.enterprise.mm.payload.PurchaseOrderItemCreateRequest;
import com.enterprise.mm.service.PurchaseOrderItemService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.enterprise.mm.payload.PurchaseOrderItemSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/purchase-order-items")
@RequiredArgsConstructor
public class PurchaseOrderItemController {
    private final PurchaseOrderItemService service;

    @GetMapping
    public ApiResponse<PageResponse<PurchaseOrderItemResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtTo,
        @RequestParam(required = false) String createdBy,
        @RequestParam(required = false) String updatedBy,
        @RequestParam(required = false) java.util.UUID materialId,
        @RequestParam(required = false) java.math.BigDecimal quantityMin,
        @RequestParam(required = false) java.math.BigDecimal quantityMax,
        @RequestParam(required = false) java.math.BigDecimal unitPriceMin,
        @RequestParam(required = false) java.math.BigDecimal unitPriceMax,
        @RequestParam(required = false) java.math.BigDecimal receivedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal receivedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal rejectedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal rejectedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal damagedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal damagedQuantityMax,
        @RequestParam(required = false) String shortageReason,
        @RequestParam(required = false) String receiptBatchNumber,
        @RequestParam(required = false) java.util.UUID receiptWarehouseId,
        @RequestParam(required = false) java.util.UUID receiptStorageLocationId,
        @RequestParam(required = false) java.util.List<String> qualityStatus,
        @RequestParam(required = false) String accreditationWarning,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        PurchaseOrderItemSearchRequest request = new PurchaseOrderItemSearchRequest(search, status, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, materialId, quantityMin, quantityMax, unitPriceMin, unitPriceMax, receivedQuantityMin, receivedQuantityMax, rejectedQuantityMin, rejectedQuantityMax, damagedQuantityMin, damagedQuantityMax, shortageReason, receiptBatchNumber, receiptWarehouseId, receiptStorageLocationId, qualityStatus, accreditationWarning);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseOrderItemResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseOrderItemResponse> create(@Valid @RequestBody PurchaseOrderItemCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PurchaseOrderItemResponse> update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderItemUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<PurchaseOrderItemResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

