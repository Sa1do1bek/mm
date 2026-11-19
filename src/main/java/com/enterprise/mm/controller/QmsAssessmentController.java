package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.QmsAssessmentDtos.AddDetailRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.CreateDraftAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentDtos.SupplierAssessmentResponse;
import com.enterprise.mm.payload.QmsAssessmentDtos.UpdateAssessmentRequest;
import com.enterprise.mm.payload.QmsAssessmentCreateWithDetailsRequest;
import com.enterprise.mm.payload.QmsAssessmentApproveRequest;
import com.enterprise.mm.service.QmsAssessmentCommandService;
import com.enterprise.mm.service.QmsAssessmentService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/qms/assessments")
@RequiredArgsConstructor
public class QmsAssessmentController {
    private static final String MUTATION_ROLES = "hasAnyRole('ADMIN','MM_MANAGER','SUPPLIER_MANAGER','PROCUREMENT_MANAGER')";

    private final QmsAssessmentService service;
    private final QmsAssessmentCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierAssessmentResponse>> search(
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false, defaultValue = "false") boolean scoreDesc,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("assessmentDate").descending().and(Sort.by("createdAt").descending()));
        return ApiResponse.ok(PageResponse.from(service.searchAssessments(supplierId, search, status, dateFrom, dateTo, scoreDesc, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierAssessmentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getAssessment(id));
    }

    @PostMapping
    @PreAuthorize(MUTATION_ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierAssessmentResponse> createDraft(@Valid @RequestBody CreateDraftAssessmentRequest request) {
        return ApiResponse.created(service.createDraftAssessment(request));
    }

    @PostMapping("/with-details")
    @PreAuthorize(MUTATION_ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierAssessmentResponse> createWithDetails(
        @Valid @RequestBody QmsAssessmentCreateWithDetailsRequest request
    ) {
        return ApiResponse.created(service.createWithDetails(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<SupplierAssessmentResponse> updateDraft(@PathVariable UUID id, @Valid @RequestBody UpdateAssessmentRequest request) {
        return ApiResponse.ok(service.updateDraftAssessment(id, request));
    }

    @PutMapping("/{id}/details")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<SupplierAssessmentResponse> addOrUpdateDetail(@PathVariable UUID id, @Valid @RequestBody AddDetailRequest request) {
        return ApiResponse.ok(service.addDetail(id, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<SupplierAssessmentResponse> approve(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody QmsAssessmentApproveRequest request
    ) {
        return ApiResponse.ok(commandService.approve(id, request, idempotencyKey));
    }
}
