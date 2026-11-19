package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierAccreditationApplicationCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationApplicationUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.DecisionCommand;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.VersionedCommand;
import com.enterprise.mm.payload.SupplierAccreditationSearchRequest;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierAccreditationCommandService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/supplier-accreditations")
@RequiredArgsConstructor
public class SupplierAccreditationController {
    private final SupplierAccreditationService service;
    private final SupplierAccreditationCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierAccreditationApplicationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) String accreditationType,
        @RequestParam(required = false) String complianceRecommendation,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate submittedTo,
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
        SupplierAccreditationSearchRequest request = new SupplierAccreditationSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, accreditationType, complianceRecommendation, submittedFrom, submittedTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierAccreditationApplicationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierAccreditationApplicationResponse> create(@Valid @RequestBody SupplierAccreditationApplicationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierAccreditationApplicationResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierAccreditationApplicationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierAccreditationApplicationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<SupplierAccreditationApplicationResponse> submit(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.submit(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/start-review")
    public ApiResponse<SupplierAccreditationApplicationResponse> startReview(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.startReview(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/request-clarification")
    public ApiResponse<SupplierAccreditationApplicationResponse> requestClarification(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.requestClarification(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/run-compliance-check")
    public ApiResponse<SupplierAccreditationApplicationResponse> runComplianceCheck(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.runComplianceCheck(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/submit-for-approval")
    public ApiResponse<SupplierAccreditationApplicationResponse> submitForApproval(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DecisionCommand command
    ) {
        return ApiResponse.ok(commandService.submitForApproval(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<SupplierAccreditationApplicationResponse> approve(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DecisionCommand command
    ) {
        return ApiResponse.ok(commandService.approve(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<SupplierAccreditationApplicationResponse> reject(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DecisionCommand command
    ) {
        return ApiResponse.ok(commandService.reject(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<SupplierAccreditationApplicationResponse> suspend(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.suspend(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/renew")
    public ApiResponse<SupplierAccreditationApplicationResponse> renew(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedCommand command
    ) {
        return ApiResponse.ok(commandService.renew(id, command, idempotencyKey));
    }
}
