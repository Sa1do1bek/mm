package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ImportDeliveryResponse;
import com.enterprise.mm.payload.ImportDeliveryUpdateRequest;
import com.enterprise.mm.payload.ImportDeliveryCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ImportDeliverySearchRequest;
import com.enterprise.mm.service.ImportDeliveryService;
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
@RequestMapping("/api/import-deliveries")
@RequiredArgsConstructor
public class ImportDeliveryController {
    private final ImportDeliveryService service;

    @GetMapping
    public ApiResponse<PageResponse<ImportDeliveryResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID supplierId,
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
        ImportDeliverySearchRequest request = new ImportDeliverySearchRequest(search, status, supplierId, purchaseOrderId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ImportDeliveryResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportDeliveryResponse> create(@Valid @RequestBody ImportDeliveryCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ImportDeliveryResponse> update(@PathVariable UUID id, @Valid @RequestBody ImportDeliveryUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ImportDeliveryResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/book")
    public ApiResponse<ImportDeliveryResponse> book(@PathVariable UUID id) {
        return ApiResponse.ok(service.book(id));
    }

    @PostMapping("/{id}/start-transit")
    public ApiResponse<ImportDeliveryResponse> startTransit(@PathVariable UUID id) {
        return ApiResponse.ok(service.startTransit(id));
    }

    @PostMapping("/{id}/arrive-customs")
    public ApiResponse<ImportDeliveryResponse> arriveCustoms(@PathVariable UUID id) {
        return ApiResponse.ok(service.arriveCustoms(id));
    }

    @PostMapping("/{id}/clear-customs")
    public ApiResponse<ImportDeliveryResponse> clearCustoms(@PathVariable UUID id) {
        return ApiResponse.ok(service.clearCustoms(id));
    }

    @PostMapping("/{id}/deliver")
    public ApiResponse<ImportDeliveryResponse> deliver(@PathVariable UUID id) {
        return ApiResponse.ok(service.deliver(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<ImportDeliveryResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ImportDeliveryResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/{id}/recalculate")
    public ApiResponse<ImportDeliveryResponse> recalculate(@PathVariable UUID id) {
        return ApiResponse.ok(service.recalculate(id));
    }

}

