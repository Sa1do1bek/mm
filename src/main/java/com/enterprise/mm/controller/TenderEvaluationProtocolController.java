package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolUpdateRequest;
import com.enterprise.mm.payload.TenderEvaluationProtocolCreateRequest;
import com.enterprise.mm.service.TenderEvaluationProtocolService;
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
import com.enterprise.mm.payload.TenderEvaluationProtocolSearchRequest;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/tender/evaluation-protocols")
@RequiredArgsConstructor
public class TenderEvaluationProtocolController {
    private final TenderEvaluationProtocolService service;

    @GetMapping
    public ApiResponse<PageResponse<TenderEvaluationProtocolResponse>> search(
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
        @RequestParam(required = false) String protocolNumber,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime evaluatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime evaluatedAtTo,
        @RequestParam(required = false) java.util.List<String> selectionRule,
        @RequestParam(required = false) java.util.UUID recommendedQuotationId,
        @RequestParam(required = false) java.util.UUID recommendedSupplierId,
        @RequestParam(required = false) String recommendationReason,
        @RequestParam(required = false) String scoringSummary,
        @RequestParam(required = false) String conflictSummary,
        @RequestParam(required = false) String approvedBy,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime approvedAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime postedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime postedAtTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        TenderEvaluationProtocolSearchRequest request = new TenderEvaluationProtocolSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, rfqId, protocolNumber, evaluatedAtFrom, evaluatedAtTo, selectionRule, recommendedQuotationId, recommendedSupplierId, recommendationReason, scoringSummary, conflictSummary, approvedBy, approvedAtFrom, approvedAtTo, postedAtFrom, postedAtTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TenderEvaluationProtocolResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenderEvaluationProtocolResponse> create(@Valid @RequestBody TenderEvaluationProtocolCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TenderEvaluationProtocolResponse> update(@PathVariable UUID id, @Valid @RequestBody TenderEvaluationProtocolUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<TenderEvaluationProtocolResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

