package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DeliveryScheduleResponse;
import com.enterprise.mm.payload.DeliveryScheduleUpdateRequest;
import com.enterprise.mm.payload.DeliveryScheduleCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.DeliveryScheduleSearchRequest;
import com.enterprise.mm.service.DeliveryScheduleService;
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

@RestController
@RequestMapping("/api/delivery-schedules")
@RequiredArgsConstructor
public class DeliveryScheduleController {
    private final DeliveryScheduleService service;

    @GetMapping
    public ApiResponse<PageResponse<DeliveryScheduleResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID contractId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        DeliveryScheduleSearchRequest request = new DeliveryScheduleSearchRequest(search, status, contractId, purchaseOrderId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeliveryScheduleResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeliveryScheduleResponse> create(@Valid @RequestBody DeliveryScheduleCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DeliveryScheduleResponse> update(@PathVariable UUID id, @Valid @RequestBody DeliveryScheduleUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<DeliveryScheduleResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/control-overdue")
    public ApiResponse<Integer> controlOverdue() {
        return ApiResponse.ok(service.controlOverdue());
    }

    @PostMapping("/{id}/control")
    public ApiResponse<DeliveryScheduleResponse> control(@PathVariable UUID id) {
        return ApiResponse.ok(service.control(id));
    }

}

