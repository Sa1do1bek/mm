package com.enterprise.mm.controller;

import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.MmAiClient;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/materials/{materialId}/ai/stock-account")
@RequiredArgsConstructor
public class MaterialStockAccountRecommendationController {
    private final AiAnalysisJobService jobs;
    private final MmAiClient client;
    public record StartRequest(UUID draftId) {}

    @GetMapping("/availability")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER')")
    public ApiResponse<Map<String, Boolean>> availability(@PathVariable UUID materialId) {
        return ApiResponse.ok(Map.of("enabled", client.isConfigured()));
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<AiAnalysisJobResponse> start(@PathVariable UUID materialId,
        @RequestBody(required = false) StartRequest request,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        return ApiResponse.ok(jobs.startMaterialStockAccountJob(materialId,
            request == null ? null : request.draftId(), language));
    }
}
