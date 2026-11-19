package com.enterprise.mm.controller.inventoryfinance;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationUpdateRequest;
import com.enterprise.mm.payload.InventoryAccountingReconciliationCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryAccountingReconciliationSearchRequest;
import com.enterprise.mm.service.InventoryAccountingReconciliationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/inventory-finance/accounting-reconciliations")
@RequiredArgsConstructor
public class InventoryAccountingReconciliationController {
    private final InventoryAccountingReconciliationService service;

    @GetMapping
    public ApiResponse<PageResponse<InventoryAccountingReconciliationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
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
        InventoryAccountingReconciliationSearchRequest request = new InventoryAccountingReconciliationSearchRequest(search, status, warehouseId, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryAccountingReconciliationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryAccountingReconciliationResponse> create(@Valid @RequestBody InventoryAccountingReconciliationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryAccountingReconciliationResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryAccountingReconciliationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryAccountingReconciliationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/reconcile")
    public ApiResponse<InventoryAccountingReconciliationResponse> reconcile(@PathVariable UUID id) {
        return ApiResponse.ok(service.reconcile(id));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<InventoryAccountingReconciliationResponse> post(@PathVariable UUID id) {
        return ApiResponse.ok(service.post(id));
    }
}

