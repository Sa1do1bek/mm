package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.IssueRequestApproveRequest;
import com.enterprise.mm.payload.IssueRequestCreateRequest;
import com.enterprise.mm.payload.IssueRequestIssueRequest;
import com.enterprise.mm.payload.IssueRequestReserveRequest;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.IssueRequestSearchRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.service.IssueRequestService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/issue-requests")
@RequiredArgsConstructor
public class IssueRequestController {
    private final IssueRequestService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<IssueRequestResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID requesterUserId,
        @RequestParam(required = false) String costObjectType,
        @RequestParam(required = false) UUID costObjectId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        IssueRequestSearchRequest request = new IssueRequestSearchRequest(
            search, status, warehouseId, requesterUserId, costObjectType, costObjectId, dateFrom, dateTo, includeDeleted
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}/lines/{lineId}/stock-positions")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<List<com.enterprise.mm.payload.StockBalanceResponse>> positions(@PathVariable UUID id, @PathVariable UUID lineId) {
        return ApiResponse.ok(service.stockPositions(id, lineId));
    }

    @GetMapping("/demand-sources/{id}")
    @PreAuthorize("hasAnyRole('DEPARTMENT_WORKER','DEPARTMENT_HEAD','MM_MANAGER','ADMIN')")
    public ApiResponse<java.util.Map<String, Object>> demandSource(@PathVariable UUID id) {
        return ApiResponse.ok(service.demandSource(id));
    }

    @GetMapping("/permissions")
    public ApiResponse<java.util.Map<String, Boolean>> permissions() {
        return ApiResponse.ok(service.permissions());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<IssueRequestResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/issue-documents")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<com.enterprise.mm.payload.StockMovementDocumentResponse>> issueDocuments(
        @PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(PageResponse.from(service.issueDocuments(id,
            PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending().and(Sort.by("id").descending())))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<IssueRequestResponse> create(@Valid @RequestBody IssueRequestCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<IssueRequestResponse> update(@PathVariable UUID id,
        @Valid @RequestBody com.enterprise.mm.payload.IssueRequestUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<IssueRequestResponse> submit(@PathVariable UUID id, @RequestBody(required = false) Object ignored) {
        return ApiResponse.ok(service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD')")
    public ApiResponse<IssueRequestResponse> approve(@PathVariable UUID id, @RequestBody(required = false) IssueRequestApproveRequest request) {
        return ApiResponse.ok(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD')")
    public ApiResponse<IssueRequestResponse> reject(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<IssueRequestResponse> reserve(@PathVariable UUID id, @RequestBody(required = false) IssueRequestReserveRequest request) {
        return ApiResponse.ok(service.reserve(id, request));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<IssueRequestResponse> issue(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody IssueRequestIssueRequest request
    ) {
        return ApiResponse.ok(service.issue(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','DEPARTMENT_HEAD')")
    public ApiResponse<IssueRequestResponse> close(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.close(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','DEPARTMENT_HEAD','DEPARTMENT_WORKER','WAREHOUSE_MANAGER')")
    public ApiResponse<IssueRequestResponse> cancel(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }
}
