package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.WarehouseLayoutDtos.*;
import com.enterprise.mm.service.WarehouseLayoutService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/layout")
public class WarehouseLayoutController {
    private final WarehouseLayoutService service;
    private final com.enterprise.mm.service.StockBalanceService balances;
    @GetMapping
    public ApiResponse<Response> get(@PathVariable UUID warehouseId) { return ApiResponse.ok(service.get(warehouseId)); }
    @GetMapping("/stock")
    public ApiResponse<com.enterprise.mm.payload.WarehouseLayoutStockDtos.Snapshot> stock(
        @PathVariable UUID warehouseId, @RequestParam(required = false) String search) {
        service.get(warehouseId); // Same company, tenant and assigned warehouse boundary as the plan.
        return ApiResponse.ok(balances.layoutLocations(warehouseId, search));
    }
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<Response> update(@PathVariable UUID warehouseId, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(warehouseId, request));
    }
}
