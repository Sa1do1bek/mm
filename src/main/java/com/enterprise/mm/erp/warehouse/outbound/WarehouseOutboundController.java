package com.enterprise.mm.erp.warehouse.outbound;

import com.enterprise.mm.payload.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp-exchange/warehouse-v2/outbound")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR') and !@roleDataScopeService.currentScope().warehouseScoped()")
public class WarehouseOutboundController {
    private final WarehouseOutboundService service;
    public WarehouseOutboundController(WarehouseOutboundService service){this.service=service;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> list(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset){return ApiResponse.ok(service.list(limit,offset));}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable UUID id){return ApiResponse.ok(service.detail(id));}
    @GetMapping("/coverage") public ApiResponse<Map<String,Object>> coverage(){return ApiResponse.ok(service.coverage());}
    @PostMapping("/reconcile") @PreAuthorize("hasRole('ADMIN') and !@roleDataScopeService.currentScope().warehouseScoped()") public ApiResponse<Map<String,Object>> reconcile(@RequestBody JsonNode request){return ApiResponse.ok(service.reconcile(request));}
    @PostMapping("/{id}/approve-native-command") @PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER') and !@roleDataScopeService.currentScope().warehouseScoped()") public ApiResponse<Map<String,Object>> approve(@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode request){return ApiResponse.ok(service.approve(id,request,key));}
}
