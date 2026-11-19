package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandManagementDtos.DemandActionRequest;
import com.enterprise.mm.payload.DemandManagementDtos.DemandReservationRequest;
import com.enterprise.mm.payload.DemandPlanItemResponse;
import com.enterprise.mm.payload.DemandPlanItemUpdateRequest;
import com.enterprise.mm.payload.DemandPlanItemCreateRequest;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.DemandPlanItemService;
import com.enterprise.mm.service.MaterialReservationService;
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
import com.enterprise.mm.payload.DemandPlanItemSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/demand-plan-items")
@RequiredArgsConstructor
public class DemandPlanItemController {
    private final DemandPlanItemService service;

    @GetMapping
    public ApiResponse<PageResponse<DemandPlanItemResponse>> search(
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
        @RequestParam(required = false) java.util.UUID departmentId,
        @RequestParam(required = false) java.util.UUID materialId,
        @RequestParam(required = false) java.util.UUID budgetItemId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate requiredDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate requiredDateTo,
        @RequestParam(required = false) java.util.List<String> demandType,
        @RequestParam(required = false) java.util.List<String> priority,
        @RequestParam(required = false) String projectCode,
        @RequestParam(required = false) String repairOrderCode,
        @RequestParam(required = false) java.math.BigDecimal requestedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal requestedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal approvedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal approvedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal reservedQuantityMin,
        @RequestParam(required = false) java.math.BigDecimal reservedQuantityMax,
        @RequestParam(required = false) java.math.BigDecimal estimatedUnitPriceMin,
        @RequestParam(required = false) java.math.BigDecimal estimatedUnitPriceMax,
        @RequestParam(required = false) java.math.BigDecimal estimatedAmountMin,
        @RequestParam(required = false) java.math.BigDecimal estimatedAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) java.util.UUID consolidatedDemandId,
        @RequestParam(required = false) java.util.UUID purchaseRequestItemId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        DemandPlanItemSearchRequest request = new DemandPlanItemSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, demandPlanId, departmentId, materialId, budgetItemId, requiredDateFrom, requiredDateTo, demandType, priority, projectCode, repairOrderCode, requestedQuantityMin, requestedQuantityMax, approvedQuantityMin, approvedQuantityMax, reservedQuantityMin, reservedQuantityMax, estimatedUnitPriceMin, estimatedUnitPriceMax, estimatedAmountMin, estimatedAmountMax, currencyCode, consolidatedDemandId, purchaseRequestItemId);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DemandPlanItemResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DemandPlanItemResponse> create(@Valid @RequestBody DemandPlanItemCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DemandPlanItemResponse> update(@PathVariable UUID id, @Valid @RequestBody DemandPlanItemUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DemandPlanItemResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private final MaterialReservationService materialReservationService;

    @PostMapping("/{id}/submit")
    public ApiResponse<DemandPlanItemResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<DemandPlanItemResponse> approve(@PathVariable UUID id, @RequestBody(required = false) DemandActionRequest request) {
        return ApiResponse.ok(service.approve(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<DemandPlanItemResponse> reject(@PathVariable UUID id, @RequestBody(required = false) DemandActionRequest request) {
        return ApiResponse.ok(service.reject(id, request == null ? null : request.comment()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<DemandPlanItemResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/{id}/reserve")
    public ApiResponse<MaterialReservationResponse> reserve(@PathVariable UUID id, @RequestBody DemandReservationRequest request) {
        return ApiResponse.ok(materialReservationService.reserveForDemandItem(id, request));
    }

}

