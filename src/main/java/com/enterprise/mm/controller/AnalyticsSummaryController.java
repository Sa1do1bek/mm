package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DashboardIntegrationResponse;
import com.enterprise.mm.service.AnalyticsSummaryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsSummaryController {
    private final AnalyticsSummaryService service;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/dashboard/v1")
    public DashboardIntegrationResponse dashboardV1() {
        Map<String, Object> summary = service.companySummary();
        Object metrics = summary.get("metrics");
        return DashboardIntegrationResponse.fresh(
                "MM",
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                Instant.now(),
                List.of(
                        new DashboardIntegrationResponse.SourceDataset("mm.material-directory.v1", metrics),
                        new DashboardIntegrationResponse.SourceDataset("mm.procurement-performance.v1", summary)
                )
        );
    }
}
