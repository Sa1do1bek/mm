package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BusinessActionDtos.ServiceAcceptRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.payload.ServiceOrderResponse;
import com.enterprise.mm.payload.ServiceOrderUpdateRequest;
import com.enterprise.mm.payload.ServiceOrderCreateRequest;
import com.enterprise.mm.service.ServiceAcceptanceActService;
import com.enterprise.mm.service.ServiceOrderService;
import jakarta.validation.Valid;
import java.util.UUID;
import com.enterprise.mm.common.PaginationUtils;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.enterprise.mm.payload.ServiceOrderSearchRequest;

@RestController
@RequestMapping({"/api/services", "/api/service-orders"})
@RequiredArgsConstructor
public class ServiceOrderController {
    private final ServiceOrderService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<ServiceOrderResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID serviceRequestId,
        @RequestParam(required = false) java.util.UUID contractId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
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
        @RequestParam(required = false) java.util.List<String> serviceType,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMin,
        @RequestParam(required = false) java.math.BigDecimal totalAmountMax,
        @RequestParam(required = false) java.util.List<String> currencyCode,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ServiceOrderSearchRequest request = new ServiceOrderSearchRequest(search, status, supplierId, serviceRequestId, contractId, purchaseOrderId, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, serviceType, totalAmountMin, totalAmountMax, currencyCode);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<ServiceOrderResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceOrderResponse> create(@Valid @RequestBody ServiceOrderCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceOrderResponse> update(@PathVariable UUID id, @Valid @RequestBody ServiceOrderUpdateRequest request) {
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
    public ApiResponse<ServiceOrderResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private final ServiceAcceptanceActService actService;

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<ServiceAcceptanceActResponse> accept(@PathVariable UUID id, @RequestBody(required = false) ServiceAcceptRequest request) {
        return ApiResponse.ok(actService.accept(id, request == null ? null : request.acceptedBy(), request == null ? null : request.commissionNotes()));
    }

}

