package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.PurchaseOrderReceivingContextService;
import lombok.RequiredArgsConstructor;
import java.util.List;

import com.enterprise.mm.payload.BusinessActionDtos.ReceivePoRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PayablesReadinessResponse;
import com.enterprise.mm.service.ProcurementPayablesService;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.service.PurchaseOrderReceivingModeService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.enterprise.mm.enums.RequestType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {
    private final PurchaseOrderService service;
    private final ProcurementPayablesService payablesService;
    private final PurchaseOrderReceivingModeService receivingModeService;
    private final PurchaseOrderReceivingContextService contextService;

    @GetMapping
    public ApiResponse<PageResponse<PurchaseOrderResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID contractId,
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
        @RequestParam(required = false) java.util.UUID purchaseRequestId,
        @RequestParam(required = false) java.util.List<RequestType> requestType,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate orderDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate orderDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate expectedDeliveryDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate expectedDeliveryDateTo,
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
        PurchaseOrderSearchRequest request = new PurchaseOrderSearchRequest(search, status, supplierId, contractId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, purchaseRequestId, requestType, orderDateFrom, orderDateTo, expectedDeliveryDateFrom, expectedDeliveryDateTo, totalAmountMin, totalAmountMax, currencyCode);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<PurchaseOrderResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/receiving-mode")
    public ApiResponse<ReceivingModeResponse> receivingMode(@PathVariable UUID id) {
        return ApiResponse.ok(receivingModeService.receivingMode(id));
    }

    @GetMapping("/{id}/receiving-context")
    public ApiResponse<PoReceivingContextResponse> receivingContext(@PathVariable UUID id) {
        return ApiResponse.ok(contextService.buildContext(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseOrderResponse> create(@Valid @RequestBody PurchaseOrderCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PurchaseOrderResponse> update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<PurchaseOrderResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/send")
    public ApiResponse<PurchaseOrderResponse> send(@PathVariable UUID id) {
        return ApiResponse.ok(service.send(id));
    }

    @PostMapping("/{id}/submit-for-approval")
    public ApiResponse<PurchaseOrderResponse> submitForApproval(@PathVariable UUID id) {
        return ApiResponse.ok(service.submitForApproval(id));
    }

    @PostMapping("/{id}/receive")
    public ApiResponse<PurchaseOrderResponse> receive(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody ReceivePoRequest request
    ) {
        return ApiResponse.ok(service.receiveIdempotent(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<PurchaseOrderResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PurchaseOrderResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @GetMapping("/{id}/payables-readiness")
    public ApiResponse<PayablesReadinessResponse> payablesReadiness(@PathVariable UUID id) {
        return ApiResponse.ok(payablesService.purchaseOrderReadiness(id));
    }

    @GetMapping("/{id}/revisions")
    public ApiResponse<PageResponse<PurchaseOrderRevisionDto>> revisions(@PathVariable UUID id) {
        return ApiResponse.ok(PageResponse.from(service.revisions(id)));
    }

}
