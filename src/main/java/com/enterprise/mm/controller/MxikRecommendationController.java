package com.enterprise.mm.controller;

import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.AiAnalysisJobService.MxikBatchRequest;
import com.enterprise.mm.service.MmAiClient;
import com.enterprise.mm.service.MxikReconciliationService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MxikRecommendationController {
    private final MxikReconciliationService reconciliation;
    private final AiAnalysisJobService jobs;
    private final MmAiClient aiClient;

    @GetMapping("/api/mxik-reconciliation/ai/availability")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER')")
    public ApiResponse<Map<String, Boolean>> availability() {
        return ApiResponse.ok(Map.of("enabled", aiClient.isConfigured()));
    }

    @PostMapping("/api/materials/{materialId}/ai/mxik-match/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<AiAnalysisJobResponse> startOne(@PathVariable UUID materialId,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        reconciliation.validateMaterialAccess(materialId);
        return ApiResponse.ok(jobs.startMxikMatchJob(materialId, language));
    }

    @PostMapping("/api/mxik-reconciliation/ai/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER')")
    public ApiResponse<AiAnalysisJobResponse> startBatch(@RequestBody MxikBatchRequest request,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        if (request != null && "SELECTED".equals(request.scope()) && request.materialIds() != null
            && request.materialIds().size() <= AiAnalysisJobService.MAX_MXIK_BATCH_SIZE
            && request.materialIds().stream().noneMatch(java.util.Objects::isNull)) {
            request.materialIds().forEach(reconciliation::validateMaterialAccess);
        }
        return ApiResponse.ok(jobs.startMxikMatchBatchJob(request, language));
    }
}
