package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.*;
import com.enterprise.mm.service.WarehouseReceiptProfileService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}/receipt-profile")
public class WarehouseReceiptProfileController {
    private final WarehouseReceiptProfileService service;
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<Profile> get(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(service.get(warehouseId));
    }
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<Profile> update(@PathVariable UUID warehouseId, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(warehouseId, request));
    }
}
