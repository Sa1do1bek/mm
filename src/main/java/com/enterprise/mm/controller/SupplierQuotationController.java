package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.SelectQuotationRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.AwardContractRequest;
import com.enterprise.mm.payload.RfqAggregationDtos.AwardContractResponse;
import com.enterprise.mm.payload.SupplierQuotationResponse;
import com.enterprise.mm.payload.SupplierQuotationUpdateRequest;
import com.enterprise.mm.payload.SupplierQuotationCreateRequest;
import com.enterprise.mm.service.SupplierQuotationService;
import com.enterprise.mm.service.RfqAwardService;
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
import com.enterprise.mm.payload.SupplierQuotationSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
public class SupplierQuotationController {
    private final SupplierQuotationService service;
    private final RfqAwardService rfqAwardService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierQuotationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
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
        @RequestParam(required = false) java.util.UUID rfqId,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime submittedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime submittedAtTo,
        @RequestParam(required = false) Boolean selected,
        @RequestParam(required = false) java.math.BigDecimal technicalScoreMin,
        @RequestParam(required = false) java.math.BigDecimal technicalScoreMax,
        @RequestParam(required = false) java.math.BigDecimal deliveryScoreMin,
        @RequestParam(required = false) java.math.BigDecimal deliveryScoreMax,
        @RequestParam(required = false) java.math.BigDecimal priceScoreMin,
        @RequestParam(required = false) java.math.BigDecimal priceScoreMax,
        @RequestParam(required = false) java.math.BigDecimal totalScoreMin,
        @RequestParam(required = false) java.math.BigDecimal totalScoreMax,
        @RequestParam(required = false) String selectionReason,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        SupplierQuotationSearchRequest request = new SupplierQuotationSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, rfqId, totalAmountMin, totalAmountMax, currencyCode, submittedAtFrom, submittedAtTo, selected, technicalScoreMin, technicalScoreMax, deliveryScoreMin, deliveryScoreMax, priceScoreMin, priceScoreMax, totalScoreMin, totalScoreMax, selectionReason);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierQuotationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierQuotationResponse> create(@Valid @RequestBody SupplierQuotationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierQuotationResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierQuotationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierQuotationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @GetMapping("/rfq/{rfqId}/compare")
    public ApiResponse<PageResponse<SupplierQuotationResponse>> compare(@PathVariable UUID rfqId) {
        return ApiResponse.ok(PageResponse.from(service.compare(rfqId)));
    }

    @PostMapping("/{id}/select-create-po")
    public ApiResponse<PurchaseOrderResponse> selectAndCreatePo(@PathVariable UUID id, @RequestBody(required = false) SelectQuotationRequest request) {
        return ApiResponse.ok(service.selectAndCreatePo(id, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/award-contract")
    public ApiResponse<AwardContractResponse> awardContract(@PathVariable UUID id, @RequestBody(required = false) AwardContractRequest request) {
        return ApiResponse.ok(rfqAwardService.award(id, request == null ? null : request.reason()));
    }

}

