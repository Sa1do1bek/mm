package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandManagementDtos.DemandActionRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandPurchaseRequestResult;
import com.enterprise.mm.payload.DemandPlanResponse;
import com.enterprise.mm.payload.DemandPlanUpdateRequest;
import com.enterprise.mm.payload.DemandPlanCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DemandPlanService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
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
import com.enterprise.mm.payload.DemandPlanSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/demand-plans")
@RequiredArgsConstructor
public class DemandPlanController {
    private final DemandPlanService service;

    @GetMapping("/date-capabilities")
    public ApiResponse<java.util.Map<String, Boolean>> dateCapabilities() {
        return ApiResponse.ok(java.util.Map.of("explicitNeedByDate", true));
    }

    @GetMapping
    public ApiResponse<PageResponse<DemandPlanResponse>> search(
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
        @RequestParam(required = false) String ownerUsername,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) java.math.BigDecimal totalPlannedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalPlannedAmountMax,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalApprovedAmountMax,
        @RequestParam(required = false) java.math.BigDecimal totalReservedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal totalReservedQuantityMax,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime consolidatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime consolidatedAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        DemandPlanSearchRequest request = new DemandPlanSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, planYear, periodType, quarter, periodStartFrom, periodStartTo, periodEndFrom, periodEndTo, ownerUsername, currencyCode, totalPlannedAmountMin, totalPlannedAmountMax, totalApprovedAmountMin, totalApprovedAmountMax, totalReservedQuantityMin, totalReservedQuantityMax, consolidatedAtFrom, consolidatedAtTo, approvedAtFrom, approvedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DemandPlanResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DemandPlanResponse> create(@Valid @RequestBody DemandPlanCreateRequest request) {
        var result = service.createWithApprovalWarning(request);
        return new ApiResponse<>(
            result.response(),
            result.warning() == null ? "created" : result.warning(),
            null,
            java.time.LocalDateTime.now()
        );
    }

    @PutMapping("/{id}")
    public ApiResponse<DemandPlanResponse> update(@PathVariable UUID id, @Valid @RequestBody DemandPlanUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DemandPlanResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/start-collection")
    public ApiResponse<DemandPlanResponse> startCollection(@PathVariable UUID id) {
        return ApiResponse.ok(service.startCollection(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<DemandPlanResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<DemandPlanResponse> approve(@PathVariable UUID id, @RequestBody(required = false) DemandActionRequest request) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<DemandPlanResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<DemandPlanResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DemandPlanResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/{id}/create-purchase-requests")
    public ApiResponse<DemandPurchaseRequestResult> createPurchaseRequests(@PathVariable UUID id) {
        return ApiResponse.ok(service.createPurchaseRequests(id));
    }

}
