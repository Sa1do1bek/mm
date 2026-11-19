package com.enterprise.mm.controller;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.enums.ManagementOpportunityConfidence;
import com.enterprise.mm.enums.ManagementOpportunityDataCompleteness;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ManagementOpportunityCalculationRunResponse;
import com.enterprise.mm.payload.ManagementOpportunityCalculationStartRequest;
import com.enterprise.mm.payload.ManagementOpportunityResponse;
import com.enterprise.mm.payload.ManagementOpportunityReviewRequest;
import com.enterprise.mm.payload.ManagementOpportunityRunSearchRequest;
import com.enterprise.mm.payload.ManagementOpportunitySearchRequest;
import com.enterprise.mm.payload.ManagementOpportunitySummaryResponse;
import com.enterprise.mm.payload.ManagementOpportunityBreakdownResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ManagementOpportunityReportService;
import com.enterprise.mm.service.ManagementOpportunityRunService;
import com.enterprise.mm.service.ManagementOpportunityService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management-opportunities")
@RequiredArgsConstructor
public class ManagementOpportunityController {
    private static final String READ_ROLES = "hasAnyRole('ADMIN','EXECUTIVE','MM_MANAGER','FINANCE_CONTROLLER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER','ACCOUNTANT','AUDITOR','VIEWER')";
    private static final String RUN_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT')";
    private static final String SUBMIT_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')";
    private static final String REVIEW_ROLES = "hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT','MM_MANAGER')";
    private static final String ARCHIVE_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";

    private final ManagementOpportunityService service;
    private final ManagementOpportunityRunService runService;
    private final ManagementOpportunityReportService reportService;

    @GetMapping
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<ManagementOpportunityResponse>> search(
        @RequestParam(required = false) ManagementOpportunityType type,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) ManagementOpportunityDataCompleteness completeness,
        @RequestParam(required = false) String sourceAggregateType,
        @RequestParam(required = false) UUID sourceAggregateId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) ManagementOpportunityConfidence confidence,
        @RequestParam(required = false) String assignedRole,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate asOfDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate asOfDateTo,
        @RequestParam(defaultValue = "false") boolean includeArchived,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        LocalDate effectiveAsOfDateFrom = asOfDateFrom == null ? dateFrom : asOfDateFrom;
        LocalDate effectiveAsOfDateTo = asOfDateTo == null ? dateTo : asOfDateTo;
        ManagementOpportunitySearchRequest request = new ManagementOpportunitySearchRequest(
            type, status, completeness, sourceAggregateType, sourceAggregateId, supplierId,
            materialId, warehouseId, confidence, assignedRole, effectiveAsOfDateFrom, effectiveAsOfDateTo, includeArchived
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/runs")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<ManagementOpportunityCalculationRunResponse>> searchRuns(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String requestedType,
        @RequestParam(required = false) String targetCurrencyCode,
        @RequestParam(required = false) LocalDate asOfDateFrom,
        @RequestParam(required = false) LocalDate asOfDateTo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedAtFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedAtTo,
        @RequestParam(required = false) Boolean hasSkipped,
        @RequestParam(required = false) Boolean hasFailure,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("startedAt").descending());
        ManagementOpportunityRunSearchRequest request = new ManagementOpportunityRunSearchRequest(
            status, requestedType, targetCurrencyCode, asOfDateFrom, asOfDateTo,
            startedAtFrom, startedAtTo, hasSkipped, hasFailure, includeDeleted
        );
        return ApiResponse.ok(PageResponse.from(runService.search(request, pageable)));
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunityCalculationRunResponse> getRun(@PathVariable UUID runId) {
        return ApiResponse.ok(runService.get(runId));
    }

    @GetMapping("/runs/{runId}/cases")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<PageResponse<ManagementOpportunityResponse>> getRunCases(
        @PathVariable UUID runId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        runService.get(runId);
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").ascending());
        return ApiResponse.ok(PageResponse.from(service.byCalculationRun(runId, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunityResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/runs")
    @PreAuthorize(RUN_ROLES)
    public ApiResponse<ManagementOpportunityCalculationRunResponse> startRun(
        @Valid @RequestBody ManagementOpportunityCalculationStartRequest request
    ) {
        return ApiResponse.ok(runService.start(request));
    }

    @PostMapping("/{id}/submit-review")
    @PreAuthorize(SUBMIT_ROLES)
    public ApiResponse<ManagementOpportunityResponse> submitReview(@PathVariable UUID id) {
        return ApiResponse.ok(service.submitReview(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(REVIEW_ROLES)
    public ApiResponse<ManagementOpportunityResponse> approve(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) ManagementOpportunityReviewRequest request
    ) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.getComment()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(REVIEW_ROLES)
    public ApiResponse<ManagementOpportunityResponse> reject(
        @PathVariable UUID id,
        @Valid @RequestBody ManagementOpportunityReviewRequest request
    ) {
        return ApiResponse.ok(service.reject(id, request == null ? null : request.getReason()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize(ARCHIVE_ROLES)
    public ApiResponse<ManagementOpportunityResponse> archive(@PathVariable UUID id) {
        return ApiResponse.ok(service.archive(id));
    }

    @GetMapping("/summary")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunitySummaryResponse> summary(
        @RequestParam(required = false) ManagementOpportunityType type,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) ManagementOpportunityDataCompleteness completeness,
        @RequestParam(required = false) String sourceAggregateType,
        @RequestParam(required = false) UUID sourceAggregateId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) ManagementOpportunityConfidence confidence,
        @RequestParam(required = false) String assignedRole,
        @RequestParam(required = false) LocalDate asOfDateFrom,
        @RequestParam(required = false) LocalDate asOfDateTo,
        @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        ManagementOpportunitySearchRequest request = new ManagementOpportunitySearchRequest(
            type, status, completeness, sourceAggregateType, sourceAggregateId,
            supplierId, materialId, warehouseId, confidence, assignedRole,
            asOfDateFrom, asOfDateTo, includeArchived
        );
        return ApiResponse.ok(reportService.summary(request));
    }

    @GetMapping("/breakdown")
    @PreAuthorize(READ_ROLES)
    public ApiResponse<ManagementOpportunityBreakdownResponse> breakdown(
        @RequestParam(required = false) ManagementOpportunityType type,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) ManagementOpportunityDataCompleteness completeness,
        @RequestParam(required = false) String sourceAggregateType,
        @RequestParam(required = false) UUID sourceAggregateId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) ManagementOpportunityConfidence confidence,
        @RequestParam(required = false) String assignedRole,
        @RequestParam(required = false) LocalDate asOfDateFrom,
        @RequestParam(required = false) LocalDate asOfDateTo,
        @RequestParam(defaultValue = "false") boolean includeArchived,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("asOfDate").descending().and(Sort.by("createdAt").descending()));
        ManagementOpportunitySearchRequest request = new ManagementOpportunitySearchRequest(
            type, status, completeness, sourceAggregateType, sourceAggregateId,
            supplierId, materialId, warehouseId, confidence, assignedRole,
            asOfDateFrom, asOfDateTo, includeArchived
        );
        return ApiResponse.ok(reportService.breakdown(request, pageable));
    }
}
