package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.service.OperationalMasterMigrationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/migration/operational-masters")
@RequiredArgsConstructor
public class OperationalMasterMigrationController {
    private final OperationalMasterMigrationService service;

    @GetMapping("/capabilities")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<java.util.Map<String, Object>> capabilities(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company) {
        return ApiResponse.ok(service.capabilities(tenant, company));
    }

    @PostMapping("/{kind:suppliers|departments|positions|employees}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MasterChunkResponse> masters(@PathVariable String kind,
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody MasterChunkRequest request) {
        return ApiResponse.created(service.importMasters(kind, tenant, company, request));
    }

    @PostMapping("/supplier-names/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<com.enterprise.mm.payload.SupplierResponse> repairSupplierName(@PathVariable UUID id,
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody SupplierNameRepairRequest request) {
        return ApiResponse.ok(service.repairSupplierName(id, tenant, company, request));
    }
}
