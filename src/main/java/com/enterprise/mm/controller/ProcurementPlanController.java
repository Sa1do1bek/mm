package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProcurementPlanResponse;
import com.enterprise.mm.payload.ProcurementPlanExecutionResponse;
import com.enterprise.mm.payload.ProcurementPlanUpdateRequest;
import com.enterprise.mm.payload.PriorYearPlanImport;
import com.enterprise.mm.payload.ProcurementPlanCreateRequest;
import com.enterprise.mm.payload.ProcurementPlanningDtos.ProcurementPlanConsolidationResult;
import com.enterprise.mm.payload.ProcurementPlanningDtos.ProcurementPlanningActionRequest;
import com.enterprise.mm.service.ProcurementPlanService;
import com.enterprise.mm.service.ProcurementPlanExecutionService;
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
import com.enterprise.mm.payload.ProcurementPlanSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/procurement/plans")
@RequiredArgsConstructor
public class ProcurementPlanController {
    private final ProcurementPlanService service;
    private final ProcurementPlanExecutionService executionService;

    @GetMapping
    public ApiResponse<PageResponse<ProcurementPlanResponse>> search(
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
        @RequestParam(required = false) String planNumber,
        @RequestParam(required = false) Integer planYear,
        @RequestParam(required = false) java.util.List<String> periodType,
        @RequestParam(required = false) Integer periodQuarter,
        @RequestParam(required = false) java.util.UUID demandPlanningCycleId,
        @RequestParam(required = false) java.util.UUID categoryStrategyId,
        @RequestParam(required = false) java.util.UUID materialGroupId,
        @RequestParam(required = false) java.util.UUID budgetItemId,
        @RequestParam(required = false) java.util.UUID ownerEmployeeId,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMin,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMax,
        @RequestParam(required = false) java.math.BigDecimal approvedBudgetAmountMin,
        @RequestParam(required = false) java.math.BigDecimal approvedBudgetAmountMax,
        @RequestParam(required = false) java.math.BigDecimal reservedBudgetAmountMin,
        @RequestParam(required = false) java.math.BigDecimal reservedBudgetAmountMax,
        @RequestParam(required = false) java.math.BigDecimal expectedSavingsAmountMin,
        @RequestParam(required = false) java.math.BigDecimal expectedSavingsAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate planStartDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate planStartDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate planEndDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate planEndDateTo,
        @RequestParam(required = false) Integer demandCount,
        @RequestParam(required = false) Integer campaignCount,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime submittedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime submittedAtTo,
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
        ProcurementPlanSearchRequest request = new ProcurementPlanSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, planNumber, planYear, periodType, periodQuarter, demandPlanningCycleId, categoryStrategyId, materialGroupId, budgetItemId, ownerEmployeeId, plannedSpendAmountMin, plannedSpendAmountMax, approvedBudgetAmountMin, approvedBudgetAmountMax, reservedBudgetAmountMin, reservedBudgetAmountMax, expectedSavingsAmountMin, expectedSavingsAmountMax, currencyCode, planStartDateFrom, planStartDateTo, planEndDateFrom, planEndDateTo, demandCount, campaignCount, submittedAtFrom, submittedAtTo, approvedAtFrom, approvedAtTo, activatedAtFrom, activatedAtTo, closedAtFrom, closedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcurementPlanResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/execution")
    public ApiResponse<ProcurementPlanExecutionResponse> execution(@PathVariable UUID id) {
        return ApiResponse.ok(executionService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProcurementPlanResponse> create(@Valid @RequestBody ProcurementPlanCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProcurementPlanResponse> update(@PathVariable UUID id, @Valid @RequestBody ProcurementPlanUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ProcurementPlanResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/consolidate")
    public ApiResponse<ProcurementPlanConsolidationResult> consolidate(@PathVariable UUID id) {
        return ApiResponse.ok(service.consolidate(id));
    }

    @PostMapping("/{id}/submit-budget")
    public ApiResponse<ProcurementPlanResponse> submitBudget(@PathVariable UUID id, @RequestBody(required = false) ProcurementPlanningActionRequest request) {
        return ApiResponse.ok(service.submitBudget(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ProcurementPlanResponse> approve(@PathVariable UUID id, @RequestBody(required = false) ProcurementPlanningActionRequest request) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<ProcurementPlanResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<ProcurementPlanResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ProcurementPlanResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/import-prior-year")
    public ApiResponse<java.util.Map<String, Integer>> importPriorYear(@RequestBody PriorYearPlanImport request) {
        return ApiResponse.ok(service.importPriorYear(request));
    }

}

