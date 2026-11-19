package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.ClassificationReport;
import com.enterprise.mm.payload.ProcurementMethodStabilizationDtos.MethodUsageReport;
import com.enterprise.mm.service.ProcurementMethodStabilizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/method-stabilization")
@RequiredArgsConstructor
public class ProcurementMethodStabilizationController {
    private final ProcurementMethodStabilizationService service;

    @GetMapping("/classification")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','AUDITOR','VIEWER')")
    public ApiResponse<ClassificationReport> classification() {
        return ApiResponse.ok(service.classifyCurrentCompany());
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','AUDITOR','VIEWER')")
    public ApiResponse<MethodUsageReport> usage() {
        return ApiResponse.ok(service.usageCurrentCompany());
    }
}
