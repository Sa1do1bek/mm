package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.security.TenantFilter;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.history.WarehouseHistoryDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/migration/warehouse-history") @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class WarehouseHistoryImportController {
    private final WarehouseHistoryProjectionVerification projection;
    private final WarehouseHistoryImportService service;
    @GetMapping("/projection-verification") public ApiResponse<Map<String,Object>> projectionVerification() {return ApiResponse.ok(projection.verify());}
    private final ObjectMapper mapper;
    static final int MAX_REQUEST_BYTES=1024*1024;
    @GetMapping("/capabilities") public ApiResponse<Map<String,Object>> capabilities(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,@RequestHeader(TenantFilter.COMPANY_HEADER) UUID company) {
        service.scope(tenant,company);return ApiResponse.ok(Map.of("contractVersion",1,"maximumRows",500,"historyOnly",true,"stockEffects",false,
            "requestEncodings",List.of("identity","gzip"),"maximumExpandedRequestBytes",MAX_REQUEST_BYTES));
    }
    @PostMapping("/runs") public ApiResponse<Run> start(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@Valid @RequestBody Start request) {return ApiResponse.created(service.start(tenant,company,request));}
    @PostMapping(value="/runs/{id}/chunks",consumes="application/json") public ApiResponse<ChunkResult> chunk(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID id,@Valid @RequestBody Chunk request) {return ApiResponse.created(service.chunk(tenant,company,id,request));}
    @PostMapping(value="/runs/{id}/chunks",consumes="application/gzip") public ApiResponse<ChunkResult> compressedChunk(
        @RequestHeader(TenantFilter.TENANT_HEADER) String tenant,@RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,
        @PathVariable UUID id,@RequestBody byte[] request) {
        if(request.length>MAX_REQUEST_BYTES)throw new BadRequestException("Compressed historical request exceeds limit");
        Chunk chunk;
        try(var input=new GZIPInputStream(new ByteArrayInputStream(request))) {
            byte[] expanded=input.readNBytes(MAX_REQUEST_BYTES+1);
            if(expanded.length>MAX_REQUEST_BYTES)throw new BadRequestException("Expanded historical request exceeds limit");
            chunk=mapper.readValue(expanded,Chunk.class);
        } catch(IOException e) {throw new BadRequestException("Invalid compressed historical request");}
        return ApiResponse.created(service.chunk(tenant,company,id,chunk));
    }
    @PostMapping("/runs/{id}/complete") public ApiResponse<Run> complete(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID id) {return ApiResponse.ok(service.complete(tenant,company,id));}
    @GetMapping("/runs/{id}") public ApiResponse<Run> status(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID id) {return ApiResponse.ok(service.status(tenant,company,id));}
    @GetMapping("/runs/{id}/proof") public ApiResponse<Map<String,Object>> proof(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID id,@RequestParam long after,@RequestParam String chain) {return ApiResponse.ok(service.proof(tenant,company,id,after,chain));}
    @GetMapping("/runs/{id}/chunks") public ApiResponse<List<Map<String,Object>>> chunks(@RequestHeader(TenantFilter.TENANT_HEADER) String tenant,
        @RequestHeader(TenantFilter.COMPANY_HEADER) UUID company,@PathVariable UUID id,@RequestParam(defaultValue="0") long after) {return ApiResponse.ok(service.chunks(tenant,company,id,after));}
}
