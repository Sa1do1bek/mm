package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalReviewCommand;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.SupplierPortalSubmissionUpdateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionCreateRequest;
import com.enterprise.mm.payload.SupplierPortalSubmissionSearchRequest;
import com.enterprise.mm.service.SupplierPortalSubmissionService;
import com.enterprise.mm.service.SupplierPortalSubmissionCommandService;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping({"/api/supplier-portal/submissions", "/api/supplier-portal-submissions"})
@RequiredArgsConstructor
public class SupplierPortalSubmissionController {
    private final SupplierPortalSubmissionService service;
    private final SupplierPortalSubmissionCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierPortalSubmissionResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID portalAccountId,
        @RequestParam(required = false) java.util.UUID referenceId,
        @RequestParam(required = false) String referenceEntityType,
        @RequestParam(required = false) String submissionType,
        @RequestParam(required = false) String payloadJson,
        @RequestParam(required = false) java.util.UUID attachmentId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate reviewedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate reviewedTo,
        @RequestParam(required = false) String reviewedBy,
        @RequestParam(required = false) String reviewComment,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("created_at").descending()
        );
        SupplierPortalSubmissionSearchRequest request = new SupplierPortalSubmissionSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, portalAccountId, referenceId, referenceEntityType, submissionType, payloadJson, attachmentId, submittedFrom, submittedTo, reviewedFrom, reviewedTo, reviewedBy, reviewComment);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierPortalSubmissionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierPortalSubmissionResponse> create(@Valid @RequestBody SupplierPortalSubmissionCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierPortalSubmissionResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierPortalSubmissionUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierPortalSubmissionResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierPortalSubmissionResponse> accept(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierPortalReviewCommand command
    ) {
        return ApiResponse.ok(commandService.accept(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPLIER_MANAGER')")
    public ApiResponse<SupplierPortalSubmissionResponse> reject(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierPortalReviewCommand command
    ) {
        return ApiResponse.ok(commandService.reject(id, command, idempotencyKey));
    }

}
