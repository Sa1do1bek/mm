package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierComplianceSummary;
import com.enterprise.mm.service.SupplierComplianceService;
import com.enterprise.mm.service.SupplierService;
import com.enterprise.mm.service.SupplierWorkspacePreviewService;
import com.enterprise.mm.service.SupplierQualificationService;
import com.enterprise.mm.supplier.governance.readiness.SupplierActivationService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {
    private final SupplierService service;
    private final SupplierQualificationService qualificationService;
    private final SupplierActivationService activationService;
    private final SupplierWorkspacePreviewService workspacePreviewService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) java.math.BigDecimal ratingMin,
        @RequestParam(required = false) java.math.BigDecimal ratingMax,
        @RequestParam(required = false) String legalName,
        @RequestParam(required = false) String shortName,
        @RequestParam(required = false) String taxId,
        @RequestParam(required = false) String okedCode,
        @RequestParam(required = false) String vatRegistrationCode,
        @RequestParam(required = false) String registrationNumber,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate registrationDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate registrationDateTo,
        @RequestParam(required = false) String legalAddress,
        @RequestParam(required = false) String actualAddress,
        @RequestParam(required = false) String phone,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String directorName,
        @RequestParam(required = false) String chiefAccountantName,
        @RequestParam(required = false) String bankName,
        @RequestParam(required = false) String bankMfo,
        @RequestParam(required = false) String bankAccount,
        @RequestParam(required = false) String bankAddress,
        @RequestParam(required = false) String blockedReason,
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
        SupplierSearchRequest request = new SupplierSearchRequest(search, status, dateFrom, dateTo, includeDeleted, category, ratingMin, ratingMax, legalName, shortName, taxId, okedCode, vatRegistrationCode, registrationNumber, registrationDateFrom, registrationDateTo, legalAddress, actualAddress, phone, email, directorName, chiefAccountantName, bankName, bankMfo, bankAccount, bankAddress, blockedReason);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierDashboardResponse> dashboard() {
        return ApiResponse.ok(service.buildDashboard());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierResponse> create(
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody SupplierCreateRequest request
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PostMapping("/workspace-preview")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','SUPPLIER_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<SupplierWorkspacePreviewResponse> preview(
        @Valid @RequestBody SupplierWorkspacePreviewRequest request
    ) {
        return ApiResponse.ok(workspacePreviewService.preview(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierReadinessSummary> activate(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierActivationCommand command
    ) {
        return ApiResponse.ok(activationService.activate(id, command, idempotencyKey));
    }

    private final SupplierComplianceService complianceService;

    @GetMapping("/{id}/compliance-summary")
    public ApiResponse<SupplierComplianceSummary> complianceSummary(@PathVariable UUID id) {
        return ApiResponse.ok(complianceService.summarize(id));
    }

    @GetMapping("/{id}/qualification-status")
    public ApiResponse<SupplierQualificationResponse> qualificationStatus(@PathVariable UUID id) {
        return ApiResponse.ok(qualificationService.get(id));
    }

    @PostMapping("/{id}/request-unblock")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierUnblockResponse> requestUnblock(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierUnblockRequestDto request
    ) {
        return ApiResponse.ok(qualificationService.requestUnblock(id, request, idempotencyKey));
    }

    @PostMapping("/{supplierId}/unblock-requests/{requestId}/review")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierUnblockResponse> reviewUnblock(
        @PathVariable UUID supplierId,
        @PathVariable UUID requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierUnblockDecisionRequest request
    ) {
        return ApiResponse.ok(qualificationService.review(supplierId, requestId, request, idempotencyKey));
    }

    @PostMapping("/{supplierId}/unblock-requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierUnblockResponse> approveUnblock(
        @PathVariable UUID supplierId,
        @PathVariable UUID requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierUnblockDecisionRequest request
    ) {
        return ApiResponse.ok(qualificationService.approve(supplierId, requestId, request, idempotencyKey));
    }

    @PostMapping("/{supplierId}/unblock-requests/{requestId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierUnblockResponse> rejectUnblock(
        @PathVariable UUID supplierId,
        @PathVariable UUID requestId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierUnblockDecisionRequest request
    ) {
        return ApiResponse.ok(qualificationService.reject(supplierId, requestId, request, idempotencyKey));
    }

}
