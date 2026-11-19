package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActCreateRequest;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActSearchRequest;
import com.enterprise.mm.payload.ServiceAcceptanceActUpdateRequest;
import com.enterprise.mm.payload.ServiceOptionsResponse;
import com.enterprise.mm.payload.ServiceReviewHistoryResponse;
import com.enterprise.mm.service.ServiceAcceptanceActService;
import com.enterprise.mm.service.ServiceReviewHistoryService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/service-acts")
public class ServiceAcceptanceActController {
    private final ServiceAcceptanceActService service;
    private final ServiceReviewHistoryService historyService;

    public ServiceAcceptanceActController(ServiceAcceptanceActService service, ServiceReviewHistoryService historyService) {
        this.service = service;
        this.historyService = historyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<ServiceAcceptanceActResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID serviceRequestId,
        @RequestParam(required = false) UUID serviceOrderId,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        ServiceAcceptanceActSearchRequest request = new ServiceAcceptanceActSearchRequest(
            search, status, serviceRequestId, serviceOrderId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceAcceptanceActResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> create(@Valid @RequestBody ServiceAcceptanceActCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER')")
    public ApiResponse<ServiceAcceptanceActResponse> update(@PathVariable UUID id, @Valid @RequestBody ServiceAcceptanceActUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ServiceAcceptanceActResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<List<ServiceReviewHistoryResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(historyService.forAct(id));
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<PageResponse<ServiceAcceptanceActResponse>> reviewQueue(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.reviewQueue(pageable)));
    }

    @GetMapping("/finance-queue")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','BUDGET_CONTROLLER')")
    public ApiResponse<PageResponse<ServiceAcceptanceActResponse>> financeQueue(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.financeQueue(pageable)));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceOptionsResponse> options() {
        return ApiResponse.ok(service.options());
    }

    @GetMapping("/prefill")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER')")
    public ApiResponse<com.enterprise.mm.payload.ServiceAcceptanceActPrefillResponse> prefill(
        @RequestParam UUID serviceOrderId
    ) {
        return ApiResponse.ok(service.getPrefillData(serviceOrderId));
    }
}
