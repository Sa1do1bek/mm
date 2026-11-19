package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreatePriceDiscoveryRfqRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreateTenderRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.CreateTenderResponse;
import com.enterprise.mm.payload.ProcurementCampaignResponse;
import com.enterprise.mm.payload.ProcurementCampaignUpdateRequest;
import com.enterprise.mm.payload.ProcurementCampaignCreateRequest;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateCampaignRfqRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.service.PriceDiscoveryService;
import com.enterprise.mm.service.ProcurementCampaignService;
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
import com.enterprise.mm.payload.ProcurementCampaignSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/procurement/campaigns")
@RequiredArgsConstructor
public class ProcurementCampaignController {
    private final ProcurementCampaignService service;
    private final PriceDiscoveryService priceDiscoveryService;

    @GetMapping
    public ApiResponse<PageResponse<ProcurementCampaignResponse>> search(
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
        @RequestParam(required = false) String campaignNumber,
        @RequestParam(required = false) java.util.UUID procurementPlanId,
        @RequestParam(required = false) java.util.UUID categoryStrategyId,
        @RequestParam(required = false) java.util.UUID materialGroupId,
        @RequestParam(required = false) java.util.UUID rfqId,
        @RequestParam(required = false) java.util.UUID purchaseRequestId,
        @RequestParam(required = false) java.util.UUID methodDecisionId,
        @RequestParam(required = false) java.util.UUID procurementProcedureId,
        @RequestParam(required = false) java.util.List<String> campaignType,
        @RequestParam(required = false) java.util.List<String> procedureType,
        @RequestParam(required = false) Integer targetSupplierCount,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMin,
        @RequestParam(required = false) java.math.BigDecimal plannedSpendAmountMax,
        @RequestParam(required = false) java.math.BigDecimal approvedSpendAmountMin,
        @RequestParam(required = false) java.math.BigDecimal approvedSpendAmountMax,
        @RequestParam(required = false) java.math.BigDecimal savingsTargetAmountMin,
        @RequestParam(required = false) java.math.BigDecimal savingsTargetAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate plannedStartDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate plannedStartDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate plannedEndDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate plannedEndDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime launchedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime launchedAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime completedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime completedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ProcurementCampaignSearchRequest request = new ProcurementCampaignSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, campaignNumber, procurementPlanId, categoryStrategyId, materialGroupId, rfqId, purchaseRequestId, methodDecisionId, procurementProcedureId, campaignType, procedureType, targetSupplierCount, plannedSpendAmountMin, plannedSpendAmountMax, approvedSpendAmountMin, approvedSpendAmountMax, savingsTargetAmountMin, savingsTargetAmountMax, currencyCode, plannedStartDateFrom, plannedStartDateTo, plannedEndDateFrom, plannedEndDateTo, launchedAtFrom, launchedAtTo, completedAtFrom, completedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcurementCampaignResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProcurementCampaignResponse> create(@Valid @RequestBody ProcurementCampaignCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProcurementCampaignResponse> update(@PathVariable UUID id, @Valid @RequestBody ProcurementCampaignUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ProcurementCampaignResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/plan")
    public ApiResponse<ProcurementCampaignResponse> plan(@PathVariable UUID id) {
        return ApiResponse.ok(service.plan(id));
    }

    @PostMapping("/{id}/launch")
    public ApiResponse<ProcurementCampaignResponse> launch(@PathVariable UUID id) {
        return ApiResponse.ok(service.launch(id));
    }

    @PostMapping("/{id}/create-rfq")
    public ApiResponse<ProcurementCampaignResponse> createRfq(
        @PathVariable UUID id,
        @RequestBody(required = false) CreateCampaignRfqRequest request
    ) {
        return ApiResponse.ok(service.createRfq(id, request));
    }

    @PostMapping("/{id}/create-price-discovery-rfq")
    public ApiResponse<RFQResponse> createPriceDiscoveryRfq(
        @PathVariable UUID id,
        @RequestBody(required = false) CreatePriceDiscoveryRfqRequest request
    ) {
        return ApiResponse.ok(priceDiscoveryService.createPriceDiscoveryRfq(id, request));
    }

    @PostMapping("/{id}/create-tender")
    public ApiResponse<CreateTenderResponse> createTender(
        @PathVariable UUID id,
        @RequestBody(required = false) CreateTenderRequest request
    ) {
        return ApiResponse.ok(priceDiscoveryService.createTender(id, request));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<ProcurementCampaignResponse> complete(@PathVariable UUID id) {
        return ApiResponse.ok(service.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ProcurementCampaignResponse> cancel(
        @PathVariable UUID id,
        @RequestBody(required = false) com.enterprise.mm.payload.ProcurementCampaignCancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request == null ? null : request.getCancellationReason()));
    }

}
