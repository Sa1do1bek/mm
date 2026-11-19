package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandManagementDtos.DemandActionRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandCyclePlanGenerationRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandCyclePlanGenerationResult;
import com.enterprise.mm.payload.DemandPlanningCycleResponse;
import com.enterprise.mm.payload.DemandPlanningCycleUpdateRequest;
import com.enterprise.mm.payload.DemandPlanningCycleCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DemandPlanningCycleService;
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
import com.enterprise.mm.payload.DemandPlanningCycleSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/demand-planning-cycles")
@RequiredArgsConstructor
public class DemandPlanningCycleController {
    private final DemandPlanningCycleService service;

    @GetMapping
    public ApiResponse<PageResponse<DemandPlanningCycleResponse>> search(
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
        @RequestParam(required = false) Integer planYear,
        @RequestParam(required = false) java.util.List<String> periodType,
        @RequestParam(required = false) Integer quarter,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodStartTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate periodEndTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate collectionStartDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate collectionStartDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate collectionEndDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate collectionEndDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate budgetReviewDeadlineFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate budgetReviewDeadlineTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate consolidationDeadlineFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate consolidationDeadlineTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate activationDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate activationDateTo,
        @RequestParam(required = false) String ownerUsername,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) Integer totalPlanCount,
        @RequestParam(required = false) Integer totalItemCount,
        @RequestParam(required = false) java.math.BigDecimal totalPlannedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalPlannedAmountMax,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedAmountMax,
        @RequestParam(required = false) java.math.BigDecimal totalReservedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal totalReservedQuantityMax,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime generatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime generatedAtTo,
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
        DemandPlanningCycleSearchRequest request = new DemandPlanningCycleSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, planYear, periodType, quarter, periodStartFrom, periodStartTo, periodEndFrom, periodEndTo, collectionStartDateFrom, collectionStartDateTo, collectionEndDateFrom, collectionEndDateTo, budgetReviewDeadlineFrom, budgetReviewDeadlineTo, consolidationDeadlineFrom, consolidationDeadlineTo, activationDateFrom, activationDateTo, ownerUsername, currencyCode, totalPlanCount, totalItemCount, totalPlannedAmountMin, totalPlannedAmountMax, totalApprovedAmountMin, totalApprovedAmountMax, totalReservedQuantityMin, totalReservedQuantityMax, generatedAtFrom, generatedAtTo, submittedAtFrom, submittedAtTo, approvedAtFrom, approvedAtTo, activatedAtFrom, activatedAtTo, closedAtFrom, closedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DemandPlanningCycleResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DemandPlanningCycleResponse> create(@Valid @RequestBody DemandPlanningCycleCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DemandPlanningCycleResponse> update(@PathVariable UUID id, @Valid @RequestBody DemandPlanningCycleUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DemandPlanningCycleResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/start-collection")
    public ApiResponse<DemandPlanningCycleResponse> startCollection(@PathVariable UUID id) {
        return ApiResponse.ok(service.startCollection(id));
    }

    @PostMapping("/{id}/generate-plans")
    public ApiResponse<DemandCyclePlanGenerationResult> generatePlans(@PathVariable UUID id, @RequestBody(required = false) DemandCyclePlanGenerationRequest request) {
        return ApiResponse.ok(service.generatePlans(id, request));
    }

    @PostMapping("/{id}/close-collection")
    public ApiResponse<DemandPlanningCycleResponse> closeCollection(@PathVariable UUID id) {
        return ApiResponse.ok(service.closeCollection(id));
    }

    @PostMapping("/{id}/submit-budget")
    public ApiResponse<DemandPlanningCycleResponse> submitBudget(@PathVariable UUID id, @RequestBody(required = false) DemandActionRequest request) {
        return ApiResponse.ok(service.submitBudget(id, request));
    }

    @PostMapping("/{id}/approve-budget")
    public ApiResponse<DemandPlanningCycleResponse> approveBudget(@PathVariable UUID id, @RequestBody(required = false) DemandActionRequest request) {
        return ApiResponse.ok(service.approveBudget(id, request));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<DemandPlanningCycleResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<DemandPlanningCycleResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DemandPlanningCycleResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}
