package com.enterprise.mm.overview.controller;

import com.enterprise.mm.overview.payload.ManagementOverviewDtos.ManagementOverview;
import com.enterprise.mm.overview.service.ManagementOverviewService;
import com.enterprise.mm.payload.ApiResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overview")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','BUDGET_CONTROLLER','PROCUREMENT_MANAGER','SUPPLIER_MANAGER','EXECUTIVE','AUDITOR','VIEWER')")
public class ManagementOverviewController {
    private final ManagementOverviewService service;

    @GetMapping("/management")
    public ApiResponse<ManagementOverview> management(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) String currency
    ) {
        return ApiResponse.ok(service.overview(from, to, currency));
    }
}
