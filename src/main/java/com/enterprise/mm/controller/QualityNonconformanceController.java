package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
import com.enterprise.mm.payload.QualityNonconformanceResponse;
import com.enterprise.mm.payload.QualityNonconformanceUpdateRequest;
import com.enterprise.mm.payload.QualityNonconformanceCreateRequest;
import com.enterprise.mm.payload.QualityNonconformanceSearchRequest;
import com.enterprise.mm.service.QualityNonconformanceService;
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
@RequestMapping("/api/quality/nonconformances")
@RequiredArgsConstructor
public class QualityNonconformanceController {
    private final QualityNonconformanceService service;

    @GetMapping
    public ApiResponse<PageResponse<QualityNonconformanceResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        QualityNonconformanceSearchRequest request = new QualityNonconformanceSearchRequest(search, status, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QualityNonconformanceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QualityNonconformanceResponse> create(@Valid @RequestBody QualityNonconformanceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<QualityNonconformanceResponse> update(@PathVariable UUID id, @Valid @RequestBody QualityNonconformanceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<QualityNonconformanceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<QualityNonconformanceResponse> review(@PathVariable UUID id) {
        return ApiResponse.ok(service.review(id));
    }

    @PostMapping("/{id}/require-capa")
    public ApiResponse<QualityNonconformanceResponse> requireCapa(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.requireCapa(id, request));
    }

    @PostMapping("/{id}/accept-deviation")
    public ApiResponse<QualityNonconformanceResponse> acceptDeviation(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.acceptDeviation(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<QualityNonconformanceResponse> reject(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<QualityNonconformanceResponse> close(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.close(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<QualityNonconformanceResponse> cancel(@PathVariable UUID id, @RequestBody(required = false) QualityActionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }

}

