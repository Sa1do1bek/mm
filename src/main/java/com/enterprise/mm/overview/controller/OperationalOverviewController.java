package com.enterprise.mm.overview.controller;

import com.enterprise.mm.overview.payload.OperationalOverviewDtos.OperationalOverview;
import com.enterprise.mm.overview.service.OperationalOverviewService;
import com.enterprise.mm.payload.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overview")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST',"
    + "'ACCOUNTANT','FINANCE_CONTROLLER','BUDGET_CONTROLLER','SUPPLIER_MANAGER',"
    + "'EXECUTIVE','AUDITOR','VIEWER')")
public class OperationalOverviewController {
    private final OperationalOverviewService service;

    @GetMapping("/operational")
    public ApiResponse<OperationalOverview> operational() {
        return ApiResponse.ok(service.overview());
    }
}
