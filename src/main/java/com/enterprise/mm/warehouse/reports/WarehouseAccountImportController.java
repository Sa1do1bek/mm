package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.payload.ApiResponse;
import jakarta.validation.Valid;
import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.enterprise.mm.warehouse.reports.WarehouseAccountImportDtos.*;

@RestController @RequestMapping("/api/migration/warehouse-accounts") @RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
public class WarehouseAccountImportController {
    private final WarehouseAccountImportService service;
    private final ObjectMapper mapper;
    static final int MAX_REQUEST_BYTES=1024*1024;
    @GetMapping("/capabilities") public ApiResponse<Map<String,Object>> capabilities() {
        service.scope(TenantContext.currentTenant(),CompanyContext.currentCompany());
        return ApiResponse.ok(Map.of("contractVersion",1,"maximumRows",500,"operationalStockEffects",false,"accountBalances",true,
            "requestEncodings",List.of("identity","gzip"),"maximumExpandedRequestBytes",MAX_REQUEST_BYTES));
    }
    @PostMapping("/runs") public ApiResponse<Map<String,Object>> start(@Valid @RequestBody Start body) {
        return ApiResponse.ok(service.start(TenantContext.currentTenant(),CompanyContext.currentCompany(),body));
    }
    @GetMapping("/runs/{id}") public ApiResponse<Map<String,Object>> status(@PathVariable UUID id) {
        return ApiResponse.ok(service.status(TenantContext.currentTenant(),CompanyContext.currentCompany(),id));
    }
    @PostMapping(value="/runs/{id}/chunks",consumes="application/json") public ApiResponse<Map<String,Object>> chunk(@PathVariable UUID id,@Valid @RequestBody Chunk body) {
        return ApiResponse.ok(service.chunk(TenantContext.currentTenant(),CompanyContext.currentCompany(),id,body));
    }
    @PostMapping(value="/runs/{id}/chunks",consumes="application/gzip") public ApiResponse<Map<String,Object>> compressedChunk(@PathVariable UUID id,@RequestBody byte[] request) {
        if(request.length>MAX_REQUEST_BYTES)throw new BadRequestException("Compressed warehouse account request exceeds limit");
        Chunk body;
        try(var input=new GZIPInputStream(new ByteArrayInputStream(request))) {
            byte[] expanded=input.readNBytes(MAX_REQUEST_BYTES+1);
            if(expanded.length>MAX_REQUEST_BYTES)throw new BadRequestException("Expanded warehouse account request exceeds limit");
            body=mapper.readValue(expanded,Chunk.class);
        }catch(IOException ex){throw new BadRequestException("Invalid compressed warehouse account request");}
        return ApiResponse.ok(service.chunk(TenantContext.currentTenant(),CompanyContext.currentCompany(),id,body));
    }
    @PostMapping("/runs/{id}/complete") public ApiResponse<Map<String,Object>> complete(@PathVariable UUID id) {
        return ApiResponse.ok(service.complete(TenantContext.currentTenant(),CompanyContext.currentCompany(),id));
    }
    @GetMapping("/runs/{id}/proof") public ApiResponse<Map<String,Object>> proof(@PathVariable UUID id,
        @RequestParam(defaultValue="0") long after,@RequestParam String previousChain) {
        return ApiResponse.ok(service.proof(TenantContext.currentTenant(),CompanyContext.currentCompany(),id,after,previousChain));
    }
}
