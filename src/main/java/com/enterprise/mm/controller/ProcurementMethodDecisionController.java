package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProcurementMethodDecisionCreateRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionRejectRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionResponse;
import com.enterprise.mm.payload.ProcurementMethodDecisionSearchRequest;
import com.enterprise.mm.payload.ProcurementMethodDecisionUpdateRequest;
import com.enterprise.mm.service.ProcurementMethodDecisionService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/method-decisions")
@RequiredArgsConstructor
public class ProcurementMethodDecisionController {
    private final ProcurementMethodDecisionService service;

    @GetMapping("/name-preview")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<java.util.Map<String, Object>> namePreview(@RequestParam UUID purchaseRequestId) {
        return ApiResponse.ok(java.util.Map.of("purchaseRequestId", purchaseRequestId, "name", service.previewName(purchaseRequestId)));
    }

    @GetMapping
    public ApiResponse<PageResponse<ProcurementMethodDecisionResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String decisionNumber,
        @RequestParam(required = false) UUID purchaseRequestId,
        @RequestParam(required = false) List<String> selectedMethod,
        @RequestParam(required = false) List<String> currencyCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        var request = new ProcurementMethodDecisionSearchRequest(search, status, dateFrom, dateTo, includeDeleted,
            decisionNumber, purchaseRequestId, selectedMethod, currencyCode);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcurementMethodDecisionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementMethodDecisionResponse> create(@Valid @RequestBody ProcurementMethodDecisionCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementMethodDecisionResponse> update(@PathVariable UUID id, @Valid @RequestBody ProcurementMethodDecisionUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementMethodDecisionResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementMethodDecisionResponse> approve(@PathVariable UUID id) {
        return ApiResponse.ok(service.approve(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementMethodDecisionResponse> reject(
        @PathVariable UUID id,
        @RequestBody ProcurementMethodDecisionRejectRequest request
    ) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ProcurementMethodDecisionResponse> revise(@PathVariable UUID id) {
        return ApiResponse.ok(service.revise(id));
    }
}
