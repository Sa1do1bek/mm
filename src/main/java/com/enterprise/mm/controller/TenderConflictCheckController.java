package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.TenderConflictResolutionRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.TenderConflictCheckResponse;
import com.enterprise.mm.payload.TenderConflictCheckUpdateRequest;
import com.enterprise.mm.payload.TenderConflictCheckCreateRequest;
import com.enterprise.mm.service.TenderConflictCheckService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.enterprise.mm.payload.TenderConflictCheckSearchRequest;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/tender/conflict-checks")
@RequiredArgsConstructor
public class TenderConflictCheckController {
    private final TenderConflictCheckService service;

    @GetMapping
    public ApiResponse<PageResponse<TenderConflictCheckResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
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
        @RequestParam(required = false) java.util.UUID rfqId,
        @RequestParam(required = false) java.util.UUID quotationId,
        @RequestParam(required = false) java.util.UUID commissionMemberId,
        @RequestParam(required = false) java.util.UUID relatedPartyId,
        @RequestParam(required = false) Boolean conflictFound,
        @RequestParam(required = false) java.util.List<String> conflictType,
        @RequestParam(required = false) java.util.List<String> severity,
        @RequestParam(required = false) String details,
        @RequestParam(required = false) String mitigationAction,
        @RequestParam(required = false) Boolean resolved,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime checkedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime checkedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        TenderConflictCheckSearchRequest request = new TenderConflictCheckSearchRequest(search, status, supplierId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, rfqId, quotationId, commissionMemberId, relatedPartyId, conflictFound, conflictType, severity, details, mitigationAction, resolved, checkedAtFrom, checkedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TenderConflictCheckResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenderConflictCheckResponse> create(@Valid @RequestBody TenderConflictCheckCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TenderConflictCheckResponse> update(@PathVariable UUID id, @Valid @RequestBody TenderConflictCheckUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<TenderConflictCheckResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<TenderConflictCheckResponse> resolve(@PathVariable UUID id, @RequestBody(required = false) TenderConflictResolutionRequest request) {
        return ApiResponse.ok(service.resolve(id, request));
    }

}

