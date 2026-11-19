package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QualityControlDtos.QualityDecisionRequest;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.QualityInspectionUpdateRequest;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.payload.QualityInspectionSearchRequest;
import com.enterprise.mm.service.QualityInspectionService;
import jakarta.validation.Valid;
import java.util.UUID;
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

@RestController
@RequestMapping("/api/quality/inspections")
@RequiredArgsConstructor
public class QualityInspectionController {
    private final QualityInspectionService service;

    @GetMapping
    public ApiResponse<PageResponse<QualityInspectionResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
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
        QualityInspectionSearchRequest request = new QualityInspectionSearchRequest(search, status, warehouseId, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QualityInspectionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QualityInspectionResponse> create(@Valid @RequestBody QualityInspectionCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<QualityInspectionResponse> update(@PathVariable UUID id, @Valid @RequestBody QualityInspectionUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<QualityInspectionResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/quarantine")
    public ApiResponse<QualityInspectionResponse> quarantine(@PathVariable UUID id) {
        return ApiResponse.ok(service.startQuarantine(id));
    }

    @PostMapping("/{id}/sampling")
    public ApiResponse<QualityInspectionResponse> sampling(@PathVariable UUID id) {
        return ApiResponse.ok(service.startSampling(id));
    }

    @PostMapping("/{id}/send-to-lab")
    public ApiResponse<QualityInspectionResponse> sendToLab(@PathVariable UUID id) {
        return ApiResponse.ok(service.sendToLab(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<QualityInspectionResponse> release(@PathVariable UUID id, @RequestBody(required = false) QualityDecisionRequest request) {
        return ApiResponse.ok(service.release(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<QualityInspectionResponse> reject(@PathVariable UUID id, @RequestBody(required = false) QualityDecisionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<QualityInspectionResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}

