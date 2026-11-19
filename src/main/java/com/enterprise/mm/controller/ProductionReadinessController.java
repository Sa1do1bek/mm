package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightReport;
import com.enterprise.mm.service.ProductionReadinessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/preflight")
@RequiredArgsConstructor
public class ProductionReadinessController {
    private final ProductionReadinessService service;

    @GetMapping
    public ApiResponse<ProductionPreflightReport> report(@RequestParam(defaultValue = "false") boolean liveCheck) {
        return ApiResponse.ok(service.report(liveCheck));
    }
}

