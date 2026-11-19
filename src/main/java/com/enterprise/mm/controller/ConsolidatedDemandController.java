package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ConsolidatedDemandResponse;
import com.enterprise.mm.payload.ConsolidatedDemandUpdateRequest;
import com.enterprise.mm.payload.ConsolidatedDemandCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ConsolidatedDemandService;
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
import com.enterprise.mm.payload.ConsolidatedDemandSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/consolidated-demands")
@RequiredArgsConstructor
public class ConsolidatedDemandController {
    private final ConsolidatedDemandService service;

    @GetMapping
    public ApiResponse<PageResponse<ConsolidatedDemandResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        @RequestParam(required = false) java.util.UUID demandPlanId,
        @RequestParam(required = false) java.util.UUID materialId,
        @RequestParam(required = false) java.util.UUID budgetItemId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndTo,
        @RequestParam(required = false) java.util.List<String> highestPriority,
        @RequestParam(required = false) java.math.BigDecimal totalRequestedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal totalRequestedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal totalEstimatedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalEstimatedAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) Integer sourceItemCount,
        @RequestParam(required = false) java.util.UUID purchaseRequestId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime consolidatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime consolidatedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ConsolidatedDemandSearchRequest request = new ConsolidatedDemandSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, demandPlanId, materialId, budgetItemId, periodStartFrom, periodStartTo, periodEndFrom, periodEndTo, highestPriority, totalRequestedQuantityMin, totalRequestedQuantityMax, totalApprovedQuantityMin, totalApprovedQuantityMax, totalEstimatedAmountMin, totalEstimatedAmountMax, currencyCode, sourceItemCount, purchaseRequestId, consolidatedAtFrom, consolidatedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConsolidatedDemandResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConsolidatedDemandResponse> create(@Valid @RequestBody ConsolidatedDemandCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ConsolidatedDemandResponse> update(@PathVariable UUID id, @Valid @RequestBody ConsolidatedDemandUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ConsolidatedDemandResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

