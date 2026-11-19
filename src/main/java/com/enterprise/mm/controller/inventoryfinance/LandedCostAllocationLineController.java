package com.enterprise.mm.controller.inventoryfinance;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.LandedCostAllocationLineResponse;
import com.enterprise.mm.payload.LandedCostAllocationLineUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationLineCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.LandedCostAllocationLineSearchRequest;
import com.enterprise.mm.service.LandedCostAllocationLineService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/inventory-finance/landed-cost-allocation-lines")
@RequiredArgsConstructor
public class LandedCostAllocationLineController {
    private final LandedCostAllocationLineService service;

    @GetMapping
    public ApiResponse<PageResponse<LandedCostAllocationLineResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
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
        LandedCostAllocationLineSearchRequest request = new LandedCostAllocationLineSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LandedCostAllocationLineResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LandedCostAllocationLineResponse> create(@Valid @RequestBody LandedCostAllocationLineCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LandedCostAllocationLineResponse> update(@PathVariable UUID id, @Valid @RequestBody LandedCostAllocationLineUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<LandedCostAllocationLineResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}

