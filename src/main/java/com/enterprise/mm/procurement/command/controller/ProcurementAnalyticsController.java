package com.enterprise.mm.procurement.command.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.CycleTimeResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.DailyFlowResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.OrderStatusResponse;
import com.enterprise.mm.procurement.command.payload.ProcurementAnalyticsDtos.TopSuppliersResponse;
import com.enterprise.mm.procurement.command.service.ProcurementAnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement-command/analytics")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','FINANCE_CONTROLLER','AUDITOR','VIEWER')")
public class ProcurementAnalyticsController {

    private final ProcurementAnalyticsService analyticsService;

    public ProcurementAnalyticsController(ProcurementAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/daily-flow")
    public ApiResponse<DailyFlowResponse> dailyFlow(
        @RequestParam(defaultValue = "30") int days
    ) {
        return ApiResponse.ok(analyticsService.dailyFlow(days));
    }

    @GetMapping("/cycle-times")
    public ApiResponse<CycleTimeResponse> cycleTimes(
        @RequestParam(defaultValue = "90") int days
    ) {
        return ApiResponse.ok(analyticsService.cycleTimes(days));
    }

    @GetMapping("/top-suppliers")
    public ApiResponse<TopSuppliersResponse> topSuppliers(
        @RequestParam(defaultValue = "5") int limit
    ) {
        return ApiResponse.ok(analyticsService.topSuppliers(limit));
    }

    @GetMapping("/order-status")
    public ApiResponse<OrderStatusResponse> orderStatuses(
        @RequestParam(defaultValue = "90") int days
    ) {
        return ApiResponse.ok(analyticsService.orderStatuses(days));
    }
}
