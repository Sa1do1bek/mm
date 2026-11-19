package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.ServiceExecutionCreateRequest;
import com.enterprise.mm.payload.ServiceExecutionResponse;
import com.enterprise.mm.payload.ServiceExecutionSearchRequest;
import com.enterprise.mm.payload.ServiceExecutionUpdateRequest;
import com.enterprise.mm.service.ServiceExecutionService;
import com.enterprise.mm.service.ServiceExecutionPurchaseOrderService;
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
@RequestMapping("/api/service-executions")
@RequiredArgsConstructor
public class ServiceExecutionController {
    private final ServiceExecutionService service;
    private final ServiceExecutionPurchaseOrderService purchaseOrderService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<ServiceExecutionResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID serviceRequestId,
        @RequestParam(required = false) UUID serviceOrderId,
        @RequestParam(required = false) UUID responsibleUserId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartTo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(new ServiceExecutionSearchRequest(search, status, serviceRequestId, serviceOrderId, responsibleUserId, plannedStartFrom, plannedStartTo, plannedEndFrom, plannedEndTo, false, includeDeleted), pageable)));
    }

    @GetMapping("/overdue")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<ServiceExecutionResponse>> overdue(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID serviceRequestId,
        @RequestParam(required = false) UUID serviceOrderId,
        @RequestParam(required = false) UUID responsibleUserId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedStartTo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate plannedEndTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("plannedEndDate").ascending().and(Sort.by("createdAt").descending()));
        return ApiResponse.ok(PageResponse.from(service.overdue(
            new ServiceExecutionSearchRequest(search, status, serviceRequestId, serviceOrderId, responsibleUserId, plannedStartFrom, plannedStartTo, plannedEndFrom, plannedEndTo, true, false),
            pageable
        )));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceExecutionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> create(@Valid @RequestBody ServiceExecutionCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping("/from-purchase-order/{purchaseOrderId}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> createFromPurchaseOrder(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(purchaseOrderService.createFromPurchaseOrder(purchaseOrderId));
    }

    @PostMapping("/from-purchase-order/{purchaseOrderId}/repair-lineage")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<Integer> repairPurchaseOrderLineage(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(purchaseOrderService.repairPurchaseOrderLineage(purchaseOrderId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> update(@PathVariable UUID id, @Valid @RequestBody ServiceExecutionUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> start(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.start(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> submit(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.submit(id, request));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> accept(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.accept(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceExecutionResponse> reject(@PathVariable UUID id, @RequestBody(required = false) ServiceActionRequest request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ServiceExecutionResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}
