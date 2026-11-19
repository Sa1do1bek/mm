package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.service.CatalogMasterMigrationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/migration/catalogs")
@RequiredArgsConstructor
public class CatalogMasterMigrationController {
    private final CatalogMasterMigrationService service;

    @PostMapping("/{kind:units|categories|warehouses}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MasterChunkResponse> masters(
        @PathVariable String kind,
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenantId,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID companyId,
        @Valid @RequestBody MasterChunkRequest request
    ) {
        return ApiResponse.created(service.importMasters(kind, tenantId, companyId, request));
    }
}
