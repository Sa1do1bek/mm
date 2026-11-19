package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ExactOpeningStockDtos.*;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.service.ExactOpeningStockService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/opening-stock/exact/imports") @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ExactOpeningStockController {
    private final ExactOpeningStockService service;
    @GetMapping("/capabilities")
    public ApiResponse<java.util.Map<String,Object>> capabilities(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company) {
        return ApiResponse.ok(service.capabilities(tenant,company));
    }
    @PostMapping
    public ApiResponse<Status> start(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@Valid @RequestBody StartRequest request) {
        return ApiResponse.created(service.start(tenant,company,request));
    }
    @PostMapping("/{importId}/chunks")
    public ApiResponse<ChunkResult> chunk(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID importId,@Valid @RequestBody ChunkRequest request) {
        return ApiResponse.ok(service.postChunk(tenant,company,importId,request));
    }
    @PostMapping("/{importId}/complete")
    public ApiResponse<Status> complete(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID importId) {
        return ApiResponse.ok(service.complete(tenant,company,importId));
    }
    @GetMapping("/{importId}")
    public ApiResponse<Status> status(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID importId) {
        return ApiResponse.ok(service.status(tenant,company,importId));
    }
}
