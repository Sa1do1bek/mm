package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DemandManagementDtos.ReleaseReservationRequest;
import com.enterprise.mm.payload.MaterialReservationResponse;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.CancelRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ConsumeRequest;
import com.enterprise.mm.payload.MaterialReservationCommandDtos.ExpireRequest;
import com.enterprise.mm.payload.MaterialReservationEventResponse;
import com.enterprise.mm.payload.MaterialReservationUpdateRequest;
import com.enterprise.mm.payload.MaterialReservationCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.MaterialReservationSearchRequest;
import com.enterprise.mm.service.MaterialReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/material-reservations")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class MaterialReservationController {
    private final MaterialReservationService service;

    @GetMapping
    public ApiResponse<PageResponse<MaterialReservationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) java.util.UUID referenceId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        MaterialReservationSearchRequest request = new MaterialReservationSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted, referenceId);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialReservationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialReservationResponse> create(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody MaterialReservationCreateRequest request
    ) {
        return ApiResponse.created(service.create(request, idempotencyKey));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialReservationResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialReservationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<MaterialReservationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialReservationResponse> release(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ReleaseReservationRequest request
    ) {
        return ApiResponse.ok(service.release(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/consume")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialReservationResponse> consume(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ConsumeRequest request
    ) {
        return ApiResponse.ok(service.consume(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<MaterialReservationResponse> cancel(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody CancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/expire")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<MaterialReservationResponse> expire(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ExpireRequest request
    ) {
        return ApiResponse.ok(service.expire(id, request, idempotencyKey));
    }

    @PostMapping("/expire-due")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Integer> expireDue(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(service.expireDueCurrentScope(limit));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<MaterialReservationEventResponse>> events(@PathVariable UUID id) {
        return ApiResponse.ok(service.events(id));
    }

}
