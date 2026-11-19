package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QualityControlDtos.QualityQuarantineDecisionRequest;
import com.enterprise.mm.payload.QualityQuarantineLotResponse;
import com.enterprise.mm.payload.QualityQuarantineLotUpdateRequest;
import com.enterprise.mm.payload.QualityQuarantineLotCreateRequest;
import com.enterprise.mm.payload.QualityQuarantineLotSearchRequest;
import com.enterprise.mm.service.QualityQuarantineLotService;
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
@RequestMapping("/api/quality/quarantine-lots")
@RequiredArgsConstructor
public class QualityQuarantineLotController {
    private final QualityQuarantineLotService service;

    @GetMapping
    public ApiResponse<PageResponse<QualityQuarantineLotResponse>> search(
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
        QualityQuarantineLotSearchRequest request = new QualityQuarantineLotSearchRequest(search, status, warehouseId, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<QualityQuarantineLotResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QualityQuarantineLotResponse> create(@Valid @RequestBody QualityQuarantineLotCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<QualityQuarantineLotResponse> update(@PathVariable UUID id, @Valid @RequestBody QualityQuarantineLotUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<QualityQuarantineLotResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<QualityQuarantineLotResponse> release(@PathVariable UUID id, @RequestBody(required = false) QualityQuarantineDecisionRequest request) {
        return ApiResponse.ok(service.release(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<QualityQuarantineLotResponse> reject(@PathVariable UUID id, @RequestBody(required = false) QualityQuarantineDecisionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<QualityQuarantineLotResponse> cancel(@PathVariable UUID id, @RequestBody(required = false) QualityQuarantineDecisionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }

}

