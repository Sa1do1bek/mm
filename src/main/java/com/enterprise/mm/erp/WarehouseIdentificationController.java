package com.enterprise.mm.erp;

import com.enterprise.mm.payload.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("erpWarehouseIdentificationController")
@RequestMapping("/api/warehouse-identification")
public class WarehouseIdentificationController {
    private final ErpExchangeService service;

    public WarehouseIdentificationController(ErpExchangeService service) {
        this.service = service;
    }

    @PostMapping("/resolve")
    public ApiResponse<java.util.Map<String, Object>> resolve(@RequestBody ScanRequest request) {
        return ApiResponse.ok(service.resolveScan(request.code(), request.operation()));
    }

    public record ScanRequest(String code, String operation) {
    }
}
