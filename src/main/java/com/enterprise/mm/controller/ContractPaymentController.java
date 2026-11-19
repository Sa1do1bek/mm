package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ContractPaymentResponse;
import com.enterprise.mm.payload.ContractPaymentUpdateRequest;
import com.enterprise.mm.payload.ContractPaymentCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentMarkPaidRequest;
import com.enterprise.mm.payload.ProcurementPayablesDtos.PaymentSubmitRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftPaymentFromInvoiceRequest;
import com.enterprise.mm.service.ContractPaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.enterprise.mm.payload.ContractPaymentSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/contract-payments")
@PreAuthorize(ContractPaymentController.READ_AUTHORIZATION)
@RequiredArgsConstructor
public class ContractPaymentController {
    public static final String READ_AUTHORIZATION =
        "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST',"
            + "'FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER','AUDITOR','VIEWER',"
            + "'DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    public static final String DRAFT_AUTHORIZATION =
        "hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')";
    public static final String TREASURY_SUBMIT_AUTHORIZATION =
        "hasAnyRole('ADMIN','FINANCE_CONTROLLER')";
    public static final String PAYMENT_CONFIRMATION_AUTHORIZATION =
        "hasAnyRole('ADMIN','FINANCE_CONTROLLER')";

    private final ContractPaymentService service;

    @GetMapping
    public ApiResponse<PageResponse<ContractPaymentResponse>> search(
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
        @RequestParam(required = false) String paymentNumber,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate paymentDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate paymentDateTo,
        @RequestParam(required = false) java.math.BigDecimal amountMin,
        @RequestParam(required = false) java.math.BigDecimal amountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(required = false) java.util.List<String> paymentMethod,
        @RequestParam(required = false) String bankReference,
        @RequestParam(required = false) UUID contractInvoiceId,
        @RequestParam(required = false) UUID purchaseOrderId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ContractPaymentSearchRequest request = new ContractPaymentSearchRequest(search, status, supplierId, contractId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, paymentNumber, paymentDateFrom, paymentDateTo, amountMin, amountMax, currencyCode, paymentMethod, bankReference, contractInvoiceId, purchaseOrderId);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContractPaymentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> create(@Valid @RequestBody ContractPaymentCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping("/draft-from-invoice")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> draftFromInvoice(
        @RequestBody DraftPaymentFromInvoiceRequest request
    ) {
        return ApiResponse.created(service.createDraftFromInvoice(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> update(@PathVariable UUID id, @Valid @RequestBody ContractPaymentUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/submit-to-treasury")
    @PreAuthorize(TREASURY_SUBMIT_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> submitToTreasury(
        @PathVariable UUID id,
        @RequestBody(required = false) PaymentSubmitRequest request
    ) {
        return ApiResponse.ok(service.submitToTreasury(id, request));
    }

    @PostMapping("/{id}/mark-paid")
    @PreAuthorize(PAYMENT_CONFIRMATION_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> markPaid(
        @PathVariable UUID id,
        @RequestBody(required = false) PaymentMarkPaidRequest request
    ) {
        return ApiResponse.ok(service.markPaid(id, request));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractPaymentResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}
