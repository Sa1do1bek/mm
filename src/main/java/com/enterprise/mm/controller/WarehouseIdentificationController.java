package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.IdentificationGenerateRequest;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.IdentificationLookupDto;
import com.enterprise.mm.payload.WarehouseIdentificationDtos.WarehouseInventoryLookupDto;
import com.enterprise.mm.service.WarehouseIdentificationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse-identification")
@RequiredArgsConstructor
public class WarehouseIdentificationController {
    private final WarehouseIdentificationService service;

    @GetMapping("/lookup")
    public ApiResponse<IdentificationLookupDto> lookup(@RequestParam String value) {
        return ApiResponse.ok(service.lookup(value));
    }

    @GetMapping("/warehouse")
    public ApiResponse<WarehouseInventoryLookupDto> getWarehouseInventory(@RequestParam UUID warehouseId) {
        return ApiResponse.ok(service.getWarehouseInventory(warehouseId));
    }

    @PostMapping("/{entityType}/{entityId}/generate")
    public ApiResponse<IdentificationLookupDto> generate(
        @PathVariable String entityType,
        @PathVariable UUID entityId,
        @RequestBody(required = false) IdentificationGenerateRequest request
    ) {
        return ApiResponse.ok(service.generate(entityType, entityId, request == null ? null : request.identifierType()));
    }
}
