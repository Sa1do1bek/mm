package com.enterprise.mm.controller.inventoryfinance;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InventoryFxDifferenceResponse;
import com.enterprise.mm.payload.InventoryFxDifferenceUpdateRequest;
import com.enterprise.mm.payload.InventoryFxDifferenceCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.InventoryFxDifferenceSearchRequest;
import com.enterprise.mm.service.InventoryFxDifferenceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/inventory-finance/fx-differences")
@RequiredArgsConstructor
public class InventoryFxDifferenceController {
    private final InventoryFxDifferenceService service;

    @GetMapping
    public ApiResponse<PageResponse<InventoryFxDifferenceResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.UUID supplierId,
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
        InventoryFxDifferenceSearchRequest request = new InventoryFxDifferenceSearchRequest(search, status, warehouseId, supplierId, materialIds, dateFrom, dateTo, includeDeleted, referenceId);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<InventoryFxDifferenceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InventoryFxDifferenceResponse> create(@Valid @RequestBody InventoryFxDifferenceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<InventoryFxDifferenceResponse> update(@PathVariable UUID id, @Valid @RequestBody InventoryFxDifferenceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<InventoryFxDifferenceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/recalculate")
    public ApiResponse<InventoryFxDifferenceResponse> recalculate(@PathVariable UUID id) {
        return ApiResponse.ok(service.recalculate(id));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<InventoryFxDifferenceResponse> post(@PathVariable UUID id) {
        return ApiResponse.ok(service.post(id));
    }
}

