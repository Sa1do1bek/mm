package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.SupplierEvaluationRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierEvaluationResponse;
import com.enterprise.mm.payload.SupplierEvaluationUpdateRequest;
import com.enterprise.mm.payload.SupplierEvaluationCreateRequest;
import com.enterprise.mm.payload.SupplierEvaluationSearchRequest;
import com.enterprise.mm.mapper.SupplierEvaluationMapper;
import com.enterprise.mm.service.SupplierEvaluationService;
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
@RequestMapping("/api/supplier-evaluations")
@RequiredArgsConstructor
public class SupplierEvaluationController {
    private final SupplierEvaluationService service;

    @GetMapping
    public ApiResponse<PageResponse<SupplierEvaluationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.math.BigDecimal totalScoreMin,
        @RequestParam(required = false) java.math.BigDecimal totalScoreMax,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate evaluatedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate evaluatedTo,
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
        SupplierEvaluationSearchRequest request = new SupplierEvaluationSearchRequest(search, status, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted, totalScoreMin, totalScoreMax, evaluatedFrom, evaluatedTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierEvaluationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierEvaluationResponse> create(@Valid @RequestBody SupplierEvaluationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierEvaluationResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierEvaluationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierEvaluationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/evaluate")
    public ApiResponse<SupplierEvaluationResponse> evaluate(@RequestBody SupplierEvaluationRequest request) {
        return ApiResponse.ok(service.evaluate(request));
    }

    @PostMapping("/po/{purchaseOrderId}/supplier/{supplierId}")
    public ApiResponse<SupplierEvaluationResponse> evaluatePo(@PathVariable UUID purchaseOrderId, @PathVariable UUID supplierId) {
        return ApiResponse.ok(new SupplierEvaluationMapper().toResponse(service.evaluateDelivery(supplierId, purchaseOrderId, 100)));
    }

}
