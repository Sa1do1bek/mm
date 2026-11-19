package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.ApprovalDecision;
import com.enterprise.mm.payload.BusinessActionDtos.ConvertToPoRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.payload.PurchaseRequestCreateRequest;
import com.enterprise.mm.payload.PurchaseRequestResponse;
import com.enterprise.mm.payload.PurchaseRequestUpdateRequest;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.service.PurchaseRequestService;
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
import com.enterprise.mm.payload.PurchaseRequestSearchRequest;
import com.enterprise.mm.enums.RequestType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/purchase-requests")
@RequiredArgsConstructor
public class PurchaseRequestController {
    private final PurchaseRequestService service;

    @GetMapping("/edit-capabilities")
    public ApiResponse<java.util.Map<String, Object>> editCapabilities() {
        return ApiResponse.ok(java.util.Map.of("itemUpdateMode", "IDENTITY_PATCH", "requiredItemVersion", true));
    }

    @GetMapping
    public ApiResponse<PageResponse<PurchaseRequestResponse>> search(
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
        @RequestParam(required = false) java.util.UUID departmentId,
        @RequestParam(required = false) java.util.UUID demandPlanId,
        @RequestParam(required = false) String requestedBy,
        @RequestParam(required = false) java.util.List<RequestType> requestType,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate requiredDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate requiredDateTo,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        PurchaseRequestSearchRequest request = new PurchaseRequestSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, departmentId, demandPlanId, requestedBy, requestType, requiredDateFrom, requiredDateTo, totalAmountMin, totalAmountMax, currencyCode);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseRequestResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseRequestResponse> create(@Valid @RequestBody PurchaseRequestCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PurchaseRequestResponse> update(@PathVariable UUID id, @Valid @RequestBody PurchaseRequestUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<PurchaseRequestResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<PurchaseRequestResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<PurchaseRequestResponse> approve(@PathVariable UUID id, @RequestBody(required = false) ApprovalDecision decision) {
        return ApiResponse.ok(service.approve(id, decision == null ? null : decision.comment()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<PurchaseRequestResponse> reject(@PathVariable UUID id, @RequestBody(required = false) ApprovalDecision decision) {
        return ApiResponse.ok(service.reject(id, decision == null ? null : decision.comment()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PurchaseRequestResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/{id}/create-rfq")
    public ApiResponse<RFQResponse> createRfq(@PathVariable UUID id) {
        return ApiResponse.ok(service.createRfq(id));
    }

    @PostMapping("/{id}/convert-to-po")
    public ApiResponse<PurchaseOrderResponse> convertToPo(@PathVariable UUID id, @RequestBody ConvertToPoRequest request) {
        return ApiResponse.ok(service.convertToPo(id, request.quotationId()));
    }

}
