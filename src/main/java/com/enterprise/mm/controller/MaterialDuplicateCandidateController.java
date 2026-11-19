package com.enterprise.mm.controller;

import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.MaterialDuplicateCandidateService;
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
@RequestMapping("/api/material-duplicate-candidates")
@RequiredArgsConstructor
public class MaterialDuplicateCandidateController {
    private static final String GOVERNANCE_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";
    private final MaterialDuplicateCandidateService service;
    private final AiAnalysisJobService aiAnalysisJobService;

    @GetMapping
    public ApiResponse<PageResponse<MaterialDuplicateCandidateResponse>> search(
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
        MaterialDuplicateCandidateSearchRequest request = new MaterialDuplicateCandidateSearchRequest(search, status, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialDuplicateCandidateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialDuplicateCandidateResponse> create(@Valid @RequestBody MaterialDuplicateCandidateCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping("/ai/scan/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<AiAnalysisJobResponse> scanAiJob(
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        return ApiResponse.ok(aiAnalysisJobService.startMaterialDedupScanJob(language));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialDuplicateCandidateResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialDuplicateCandidateUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaterialDuplicateCandidateResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialDuplicateCandidateResponse> confirm(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.confirm(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialDuplicateCandidateResponse> reject(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize(GOVERNANCE_ROLES)
    public ApiResponse<MaterialDuplicateCandidateResponse> merge(
        @PathVariable UUID id,
        @Valid @RequestBody MaterialGovernanceActionRequest request
    ) {
        return ApiResponse.ok(service.merge(id, request));
    }
}
