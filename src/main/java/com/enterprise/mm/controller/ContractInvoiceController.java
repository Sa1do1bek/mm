package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ContractInvoiceResponse;
import com.enterprise.mm.payload.ContractInvoiceUpdateRequest;
import com.enterprise.mm.payload.ContractInvoiceCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ContractInvoiceService;
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
import com.enterprise.mm.payload.ContractInvoiceSearchRequest;
import com.enterprise.mm.payload.ProcurementWorkspaceDtos.DraftInvoiceFromPurchaseOrderRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/contract-invoices")
@PreAuthorize(ContractInvoiceController.READ_AUTHORIZATION)
@RequiredArgsConstructor
public class ContractInvoiceController {
    public static final String READ_AUTHORIZATION =
        "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST',"
            + "'FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER','AUDITOR','VIEWER',"
            + "'DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    public static final String DRAFT_AUTHORIZATION =
        "hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')";
    public static final String ISSUE_AUTHORIZATION =
        "hasAnyRole('ADMIN','FINANCE_CONTROLLER')";

    private final ContractInvoiceService service;

    @GetMapping
    public ApiResponse<PageResponse<ContractInvoiceResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID contractId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
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
        @RequestParam(required = false) String invoiceNumber,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate invoiceDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate invoiceDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateTo,
        @RequestParam(required = false) java.math.BigDecimal amountMin,
        @RequestParam(required = false) java.math.BigDecimal amountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ContractInvoiceSearchRequest request = new ContractInvoiceSearchRequest(search, status, supplierId, contractId, purchaseOrderId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, invoiceNumber, invoiceDateFrom, invoiceDateTo, dueDateFrom, dueDateTo, amountMin, amountMax, currencyCode);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContractInvoiceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractInvoiceResponse> create(@Valid @RequestBody ContractInvoiceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping("/draft-from-purchase-order")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractInvoiceResponse> draftFromPurchaseOrder(
        @RequestBody DraftInvoiceFromPurchaseOrderRequest request
    ) {
        return ApiResponse.created(service.createDraftFromPurchaseOrder(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractInvoiceResponse> update(@PathVariable UUID id, @Valid @RequestBody ContractInvoiceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(ISSUE_AUTHORIZATION)
    public ApiResponse<ContractInvoiceResponse> issue(@PathVariable UUID id) {
        return ApiResponse.ok(service.issue(id));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(DRAFT_AUTHORIZATION)
    public ApiResponse<ContractInvoiceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}
