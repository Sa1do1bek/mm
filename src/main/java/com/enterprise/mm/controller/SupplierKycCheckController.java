package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierKycDecisionCommand;
import com.enterprise.mm.payload.SupplierComplianceDtos.VersionedSupplierComplianceCommand;
import com.enterprise.mm.payload.SupplierKycCheckResponse;
import com.enterprise.mm.payload.SupplierKycCheckUpdateRequest;
import com.enterprise.mm.payload.SupplierKycCheckCreateRequest;
import com.enterprise.mm.payload.SupplierKycCheckSearchRequest;
import com.enterprise.mm.service.SupplierKycCheckService;
import com.enterprise.mm.service.SupplierKycCheckCommandService;
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
@RequestMapping("/api/supplier-kyc-checks")
@RequiredArgsConstructor
public class SupplierKycCheckController {
    private final SupplierKycCheckService service;
    private final SupplierKycCheckCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierKycCheckResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) String checkType,
        @RequestParam(required = false) String riskLevel,
        @RequestParam(required = false) Boolean remediationRequired,
        @RequestParam(required = false) java.math.BigDecimal scoreMin,
        @RequestParam(required = false) java.math.BigDecimal scoreMax,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) String externalCheckId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate checkedFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate checkedTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validUntilFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validUntilTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate nextReviewDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate nextReviewDateTo,
        @RequestParam(required = false) java.util.UUID evidenceAttachmentId,
        @RequestParam(required = false) String findings,
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
        SupplierKycCheckSearchRequest request = new SupplierKycCheckSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, checkType, riskLevel, remediationRequired, scoreMin, scoreMax, provider, externalCheckId, checkedFrom, checkedTo, validUntilFrom, validUntilTo, nextReviewDateFrom, nextReviewDateTo, evidenceAttachmentId, findings);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierKycCheckResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierKycCheckResponse> create(@Valid @RequestBody SupplierKycCheckCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierKycCheckResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierKycCheckUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierKycCheckResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/request")
    public ApiResponse<SupplierKycCheckResponse> request(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedSupplierComplianceCommand command
    ) {
        return ApiResponse.ok(commandService.request(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/pass")
    public ApiResponse<SupplierKycCheckResponse> pass(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierKycDecisionCommand command
    ) {
        return ApiResponse.ok(commandService.pass(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/fail")
    public ApiResponse<SupplierKycCheckResponse> fail(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierKycDecisionCommand command
    ) {
        return ApiResponse.ok(commandService.fail(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/waive")
    public ApiResponse<SupplierKycCheckResponse> waive(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierKycDecisionCommand command
    ) {
        return ApiResponse.ok(commandService.waive(id, command, idempotencyKey));
    }

    @PostMapping("/{id}/expire")
    public ApiResponse<SupplierKycCheckResponse> expire(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody VersionedSupplierComplianceCommand command
    ) {
        return ApiResponse.ok(commandService.expire(id, command, idempotencyKey));
    }

}
