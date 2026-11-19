package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.service.ContractMigrationService;
import com.enterprise.mm.service.ContractMaterialLinkMigrationService;
import com.enterprise.mm.entity.ContractMaterialLink;
import java.util.List;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/migration/contracts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ContractMigrationController {
    private final ContractMigrationService service;
    private final ContractMaterialLinkMigrationService materialLinks;

    @PostMapping("/material-links")
    public ApiResponse<MasterChunkResponse> importMaterialLinks(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody MasterChunkRequest request) {
        return ApiResponse.created(materialLinks.importChunk(tenant, company, request));
    }

    @GetMapping("/{specificationId}/material-links")
    public ApiResponse<List<ContractMaterialLink>> materialLinks(@PathVariable UUID specificationId,
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company) {
        return ApiResponse.ok(materialLinks.links(tenant, company, specificationId));
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company) {
        return ApiResponse.ok(service.capabilities(tenant, company));
    }

    @PostMapping("/statuses")
    public ApiResponse<MasterChunkResponse> importStatuses(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody MasterChunkRequest request) {
        return ApiResponse.ok(service.importStatusChunk(tenant, company, request));
    }

    @PostMapping("/lines")
    public ApiResponse<MasterChunkResponse> importLines(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody MasterChunkRequest request) {
        return ApiResponse.created(service.importChunk("lines", tenant, company, request));
    }

    @PostMapping("/{kind:contracts|addendums|specifications}")
    public ApiResponse<MasterChunkResponse> importChunk(@PathVariable String kind,
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @Valid @RequestBody MasterChunkRequest request) {
        return ApiResponse.created(service.importChunk(kind, tenant, company, request));
    }
}
