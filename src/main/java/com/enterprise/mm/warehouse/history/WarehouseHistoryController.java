package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.payload.ApiResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/warehouse-history") @RequiredArgsConstructor
public class WarehouseHistoryController {
    private final WarehouseHistoryQueryService service;
    @GetMapping public ApiResponse<Map<String,Object>> records(@RequestParam(defaultValue="DOCUMENT") String kind,
        @RequestParam(required=false) String documentType,@RequestParam(required=false) String documentKey,
        @RequestParam(required=false) UUID materialId,@RequestParam(required=false) UUID warehouseId,
        @RequestParam(required=false) String search,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        return ApiResponse.ok(service.records(kind,documentType,documentKey,materialId,warehouseId,search,from,to,page,size));
    }
    @GetMapping("/summary") public ApiResponse<Map<String,Object>> summary() {return ApiResponse.ok(service.summary());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> detail(@PathVariable UUID id) {return ApiResponse.ok(service.detail(id));}
}
