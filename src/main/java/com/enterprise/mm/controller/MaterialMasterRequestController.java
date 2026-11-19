package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.MaterialMasterRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.MaterialMasterRequestSearchRequest;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/material-master-requests")
@RequiredArgsConstructor
public class MaterialMasterRequestController {
    private static final String GOVERNANCE_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";
    private final MaterialMasterRequestService service;

    @GetMapping
    public ApiResponse<PageResponse<MaterialMasterRequestResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
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
        @RequestParam(required = false) java.util.List<String> requestType,
        @RequestParam(required = false) java.util.UUID materialId,
        @RequestParam(required = false) String proposedName,
        @RequestParam(required = false) java.util.UUID materialGroupId,
        @RequestParam(required = false) java.util.UUID uomId,
        @RequestParam(required = false) String mxikCode,
        @RequestParam(required = false) String tnvedCode,
        @RequestParam(required = false) String requestedBy,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        MaterialMasterRequestSearchRequest request = new MaterialMasterRequestSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, requestType, materialId, proposedName, materialGroupId, uomId, mxikCode, tnvedCode, requestedBy);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialMasterRequestResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialMasterRequestResponse> create(@Valid @RequestBody MaterialMasterRequestCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialMasterRequestResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialMasterRequestUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaterialMasterRequestResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> submit(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.submit(id, request));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> review(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.review(id, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> approve(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> reject(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/apply")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> apply(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.apply(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialMasterRequestResponse> cancel(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request));
    }
}
