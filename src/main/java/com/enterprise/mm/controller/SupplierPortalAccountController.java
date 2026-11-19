package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalLifecycleCommand;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalAccountUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountSearchRequest;
import com.enterprise.mm.service.SupplierPortalAccountService;
import com.enterprise.mm.service.SupplierPortalAccountCommandService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping({"/api/supplier-portal/accounts", "/api/supplier-portal-accounts"})
@RequiredArgsConstructor
public class SupplierPortalAccountController {
    private final SupplierPortalAccountService service;
    private final SupplierPortalAccountCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierPortalAccountResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) Boolean mfaRequired,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate lastLoginFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate lastLoginTo,
        @RequestParam(required = false) String loginEmail,
        @RequestParam(required = false) String contactPerson,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String allowedIpRange,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate invitedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate invitedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate activatedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate activatedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate lastSubmissionFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate lastSubmissionTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate termsAcceptedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate termsAcceptedTo,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        SupplierPortalAccountSearchRequest request = new SupplierPortalAccountSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, mfaRequired, lastLoginFrom, lastLoginTo, loginEmail, contactPerson, phone, allowedIpRange, invitedFrom, invitedTo, activatedFrom, activatedTo, lastSubmissionFrom, lastSubmissionTo, termsAcceptedFrom, termsAcceptedTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierPortalAccountResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierPortalAccountResponse> create(@Valid @RequestBody SupplierPortalAccountCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierPortalAccountResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierPortalAccountUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierPortalAccountResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierPortalAccountResponse> suspend(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierPortalLifecycleCommand command
    ) {
        return ApiResponse.ok(commandService.suspend(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierPortalAccountResponse> revoke(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierPortalLifecycleCommand command
    ) {
        return ApiResponse.ok(commandService.revoke(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/reinvite")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierPortalAccountResponse> reinvite(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierPortalLifecycleCommand command
    ) {
        return ApiResponse.ok(commandService.reinvite(id, command, idempotencyKey));
    }

}
