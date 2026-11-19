package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.MaterialQualityIssueService;
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

@RestController
@RequestMapping("/api/material-quality-issues")
@RequiredArgsConstructor
public class MaterialQualityIssueController {
    private static final String GOVERNANCE_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";
    private final MaterialQualityIssueService service;

    @GetMapping
    public ApiResponse<PageResponse<MaterialQualityIssueResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
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
        MaterialQualityIssueSearchRequest request = new MaterialQualityIssueSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialQualityIssueResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialQualityIssueResponse> create(@Valid @RequestBody MaterialQualityIssueCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialQualityIssueResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialQualityIssueUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaterialQualityIssueResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialQualityIssueResponse> assign(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialQualityIssueActionRequest request
    ) {
        return ApiResponse.ok(service.assign(id, request));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialQualityIssueResponse> start(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialQualityIssueActionRequest request
    ) {
        return ApiResponse.ok(service.start(id, request));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialQualityIssueResponse> resolve(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialQualityIssueActionRequest request
    ) {
        return ApiResponse.ok(service.resolve(id, request));
    }

    @PostMapping("/{id}/ignore")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialQualityIssueResponse> ignore(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialQualityIssueActionRequest request
    ) {
        return ApiResponse.ok(service.ignore(id, request));
    }
}
