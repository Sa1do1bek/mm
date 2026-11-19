package com.enterprise.mm.controller.inventoryfinance;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.LandedCostAllocationResponse;
import com.enterprise.mm.payload.LandedCostAllocationUpdateRequest;
import com.enterprise.mm.payload.LandedCostAllocationCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.LandedCostAllocationSearchRequest;
import com.enterprise.mm.service.LandedCostAllocationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/inventory-finance/landed-cost-allocations")
@RequiredArgsConstructor
public class LandedCostAllocationController {
    private final LandedCostAllocationService service;

    @GetMapping
    public ApiResponse<PageResponse<LandedCostAllocationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
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
        LandedCostAllocationSearchRequest request = new LandedCostAllocationSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<LandedCostAllocationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LandedCostAllocationResponse> create(@Valid @RequestBody LandedCostAllocationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<LandedCostAllocationResponse> update(@PathVariable UUID id, @Valid @RequestBody LandedCostAllocationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<LandedCostAllocationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<LandedCostAllocationResponse> post(@PathVariable UUID id) {
        return ApiResponse.ok(service.post(id));
    }
}

