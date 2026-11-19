package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProcurementCategoryStrategyResponse;
import com.enterprise.mm.payload.ProcurementCategoryStrategyUpdateRequest;
import com.enterprise.mm.payload.ProcurementCategoryStrategyCreateRequest;
import com.enterprise.mm.payload.ProcurementPlanningDtos.ProcurementPlanningActionRequest;
import com.enterprise.mm.service.ProcurementCategoryStrategyService;
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
import com.enterprise.mm.payload.ProcurementCategoryStrategySearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/procurement/category-strategies")
@RequiredArgsConstructor
public class ProcurementCategoryStrategyController {
    private final ProcurementCategoryStrategyService service;

    @GetMapping
    public ApiResponse<PageResponse<ProcurementCategoryStrategyResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        @RequestParam(required = false) java.util.UUID materialGroupId,
        @RequestParam(required = false) Integer strategyYear,
        @RequestParam(required = false) java.util.UUID ownerEmployeeId,
        @RequestParam(required = false) java.util.UUID budgetItemId,
        @RequestParam(required = false) java.util.List<String> strategyType,
        @RequestParam(required = false) java.util.List<String> sourcingApproach,
        @RequestParam(required = false) String supplierMarket,
        @RequestParam(required = false) java.util.List<String> riskLevel,
        @RequestParam(required = false) java.math.BigDecimal targetSavingsPercentMin,
        @RequestParam(required = false) java.math.BigDecimal targetSavingsPercentMax,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMin,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMax,
        @RequestParam(required = false) java.math.BigDecimal approvedSpendLimitMin,
        @RequestParam(required = false) java.math.BigDecimal approvedSpendLimitMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validFromFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validFromTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validToFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validToTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime activatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime activatedAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime closedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime closedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ProcurementCategoryStrategySearchRequest request = new ProcurementCategoryStrategySearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, materialGroupId, strategyYear, ownerEmployeeId, budgetItemId, strategyType, sourcingApproach, supplierMarket, riskLevel, targetSavingsPercentMin, targetSavingsPercentMax, plannedSpendAmountMin, plannedSpendAmountMax, approvedSpendLimitMin, approvedSpendLimitMax, currencyCode, validFromFrom, validFromTo, validToFrom, validToTo, approvedAtFrom, approvedAtTo, activatedAtFrom, activatedAtTo, closedAtFrom, closedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcurementCategoryStrategyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProcurementCategoryStrategyResponse> create(@Valid @RequestBody ProcurementCategoryStrategyCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProcurementCategoryStrategyResponse> update(@PathVariable UUID id, @Valid @RequestBody ProcurementCategoryStrategyUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ProcurementCategoryStrategyResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<ProcurementCategoryStrategyResponse> submit(@PathVariable UUID id, @RequestBody(required = false) ProcurementPlanningActionRequest request) {
        return ApiResponse.ok(service.submit(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ProcurementCategoryStrategyResponse> approve(@PathVariable UUID id, @RequestBody(required = false) ProcurementPlanningActionRequest request) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<ProcurementCategoryStrategyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<ProcurementCategoryStrategyResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ProcurementCategoryStrategyResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}

