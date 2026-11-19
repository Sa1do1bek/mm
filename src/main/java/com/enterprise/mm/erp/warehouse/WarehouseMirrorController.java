package com.enterprise.mm.erp.warehouse;

import com.enterprise.mm.payload.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp-exchange/warehouse-v2")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR') and !@roleDataScopeService.currentScope().warehouseScoped()")
public class WarehouseMirrorController {
    private final WarehouseMirrorService service;
    public WarehouseMirrorController(WarehouseMirrorService service){this.service=service;}
    @GetMapping("/config") public ApiResponse<Map<String,Object>> config(){return ApiResponse.ok(service.config());}
    @GetMapping("/documents") public ApiResponse<List<Map<String,Object>>> documents(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(required=false) String documentType,@RequestParam(required=false) String state){return ApiResponse.ok(service.documents(limit,offset,from,to,documentType,state));}
    @GetMapping("/documents/{id}") public ApiResponse<Map<String,Object>> document(@PathVariable UUID id){return ApiResponse.ok(service.document(id));}
    @GetMapping("/positions") public ApiResponse<List<Map<String,Object>>> positions(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset){return ApiResponse.ok(service.positions(limit,offset));}
    @GetMapping("/coverage") public ApiResponse<Map<String,Object>> coverage(){return ApiResponse.ok(service.coverage());}
    @GetMapping("/history-runs") public ApiResponse<List<Map<String,Object>>> history(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset){return ApiResponse.ok(service.historyRuns(limit,offset));}
    @PostMapping("/history-runs") @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT') and !@roleDataScopeService.currentScope().warehouseScoped()") public ApiResponse<Map<String,Object>> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode request){return ApiResponse.ok(service.createRun(request,key));}
    @PostMapping("/history-runs/{id}/retry") @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT') and !@roleDataScopeService.currentScope().warehouseScoped()") public ApiResponse<Map<String,Object>> retry(@PathVariable UUID id){return ApiResponse.ok(service.retryRun(id));}
    @PostMapping("/history-runs/{id}/cancel") @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT') and !@roleDataScopeService.currentScope().warehouseScoped()") public ApiResponse<Map<String,Object>> cancel(@PathVariable UUID id){return ApiResponse.ok(service.cancelRun(id));}
}
