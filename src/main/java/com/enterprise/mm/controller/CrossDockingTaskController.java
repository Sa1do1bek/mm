package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CrossDockingTaskResponse;
import com.enterprise.mm.payload.CrossDockingTaskUpdateRequest;
import com.enterprise.mm.payload.CrossDockingTaskCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.WmsDtos.CrossDockingActionRequest;
import com.enterprise.mm.payload.CrossDockingTaskSearchRequest;
import com.enterprise.mm.service.CrossDockingTaskService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
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
@RequestMapping("/api/wms/cross-docking-tasks")
@RequiredArgsConstructor
public class CrossDockingTaskController {
    private final CrossDockingTaskService service;

    @GetMapping
    public ApiResponse<PageResponse<CrossDockingTaskResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.UUID supplierId,
        @RequestParam(required = false) java.util.UUID purchaseOrderId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
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
        CrossDockingTaskSearchRequest request = new CrossDockingTaskSearchRequest(search, status, warehouseId, supplierId, purchaseOrderId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CrossDockingTaskResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CrossDockingTaskResponse> create(@Valid @RequestBody CrossDockingTaskCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CrossDockingTaskResponse> update(@PathVariable UUID id, @Valid @RequestBody CrossDockingTaskUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<CrossDockingTaskResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/receive-inbound")
    public ApiResponse<CrossDockingTaskResponse> receiveInbound(@PathVariable UUID id, @RequestBody(required = false) CrossDockingActionRequest request) {
        return ApiResponse.ok(service.receiveInbound(id, request));
    }

    @PostMapping("/{id}/stage")
    public ApiResponse<CrossDockingTaskResponse> stage(@PathVariable UUID id, @RequestBody CrossDockingActionRequest request) {
        return ApiResponse.ok(service.stage(id, request));
    }

    @PostMapping("/{id}/allocate-outbound")
    public ApiResponse<CrossDockingTaskResponse> allocateOutbound(@PathVariable UUID id, @RequestBody(required = false) CrossDockingActionRequest request) {
        return ApiResponse.ok(service.allocateOutbound(id, request));
    }

    @PostMapping("/{id}/ship")
    public ApiResponse<CrossDockingTaskResponse> ship(@PathVariable UUID id, @RequestBody(required = false) CrossDockingActionRequest request) {
        return ApiResponse.ok(service.ship(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CrossDockingTaskResponse> cancel(@PathVariable UUID id, @RequestBody(required = false) CrossDockingActionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }

}

