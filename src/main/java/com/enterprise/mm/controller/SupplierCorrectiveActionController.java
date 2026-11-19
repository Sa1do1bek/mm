package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierCapaActionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierCorrectiveActionResponse;
import com.enterprise.mm.payload.SupplierCorrectiveActionUpdateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionCreateRequest;
import com.enterprise.mm.payload.SupplierCorrectiveActionSearchRequest;
import com.enterprise.mm.service.SupplierCorrectiveActionService;
import com.enterprise.mm.service.SupplierCorrectiveActionCommandService;
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

@RestController
@RequestMapping("/api/supplier-corrective-actions")
@RequiredArgsConstructor
public class SupplierCorrectiveActionController {
    private final SupplierCorrectiveActionService service;
    private final SupplierCorrectiveActionCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierCorrectiveActionResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) java.util.UUID sourceId,
        @RequestParam(required = false) String issueTitle,
        @RequestParam(required = false) java.util.UUID ownerEmployeeId,
        @RequestParam(required = false) String supplierResponsibleName,
        @RequestParam(required = false) String severity,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate verifiedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate verifiedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate closedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate closedTo,
        @RequestParam(required = false) String verificationResult,
        @RequestParam(required = false) Boolean recurrencePrevented,
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
        SupplierCorrectiveActionSearchRequest request = new SupplierCorrectiveActionSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, sourceType, sourceId, issueTitle, ownerEmployeeId, supplierResponsibleName, severity, dueDateFrom, dueDateTo, submittedFrom, submittedTo, verifiedFrom, verifiedTo, closedFrom, closedTo, verificationResult, recurrencePrevented);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierCorrectiveActionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierCorrectiveActionResponse> create(@Valid @RequestBody SupplierCorrectiveActionCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierCorrectiveActionResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierCorrectiveActionUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierCorrectiveActionResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/open")
    public ApiResponse<SupplierCorrectiveActionResponse> open(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedSupplierComplianceCommand command
    ) {
        return ApiResponse.ok(commandService.open(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<SupplierCorrectiveActionResponse> start(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedSupplierComplianceCommand command
    ) {
        return ApiResponse.ok(commandService.start(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<SupplierCorrectiveActionResponse> submit(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierCapaActionCommand command
    ) {
        return ApiResponse.ok(commandService.submit(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<SupplierCorrectiveActionResponse> verify(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierCapaActionCommand command
    ) {
        return ApiResponse.ok(commandService.verify(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<SupplierCorrectiveActionResponse> close(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedSupplierComplianceCommand command
    ) {
        return ApiResponse.ok(commandService.close(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<SupplierCorrectiveActionResponse> cancel(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierCapaActionCommand command
    ) {
        return ApiResponse.ok(commandService.cancel(id, command, idempotencyKey));
    }

}
