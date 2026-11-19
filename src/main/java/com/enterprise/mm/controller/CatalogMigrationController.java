package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkRequest;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkResponse;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.service.CatalogMigrationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration/catalogs")
@RequiredArgsConstructor
public class CatalogMigrationController {
    private final CatalogMigrationService service;

    @PostMapping("/materials")
    @PreAuthorize("hasAnyRole('ADMIN', 'MM_MANAGER')")
    public ApiResponse<MaterialCatalogChunkResponse> materials(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenantId,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID companyId,
        @Valid @RequestBody MaterialCatalogChunkRequest request
    ) {
        return ApiResponse.created(service.importMaterials(tenantId, companyId, request));
    }
}
