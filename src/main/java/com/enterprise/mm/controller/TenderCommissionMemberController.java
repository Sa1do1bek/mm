package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.TenderCommissionMemberResponse;
import com.enterprise.mm.payload.TenderCommissionMemberUpdateRequest;
import com.enterprise.mm.payload.TenderCommissionMemberCreateRequest;
import com.enterprise.mm.service.TenderCommissionMemberService;
import jakarta.validation.Valid;
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
import com.enterprise.mm.payload.TenderCommissionMemberSearchRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/tender/commission-members")
@RequiredArgsConstructor
public class TenderCommissionMemberController {
    private final TenderCommissionMemberService service;

    @GetMapping
    public ApiResponse<PageResponse<TenderCommissionMemberResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        @RequestParam(required = false) java.util.UUID employeeId,
        @RequestParam(required = false) String memberName,
        @RequestParam(required = false) java.util.List<String> memberRole,
        @RequestParam(required = false) java.math.BigDecimal votingWeightMin,
        @RequestParam(required = false) java.math.BigDecimal votingWeightMax,
        @RequestParam(required = false) Boolean conflictDeclarationSigned,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime declaredAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime declaredAtTo,
        @RequestParam(required = false) Boolean active,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        TenderCommissionMemberSearchRequest request = new TenderCommissionMemberSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, rfqId, employeeId, memberName, memberRole, votingWeightMin, votingWeightMax, conflictDeclarationSigned, declaredAtFrom, declaredAtTo, active);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TenderCommissionMemberResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenderCommissionMemberResponse> create(@Valid @RequestBody TenderCommissionMemberCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TenderCommissionMemberResponse> update(@PathVariable UUID id, @Valid @RequestBody TenderCommissionMemberUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<TenderCommissionMemberResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

