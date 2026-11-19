package com.enterprise.mm.controller;

import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.MmAiClient;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-analysis-jobs")
@RequiredArgsConstructor
public class AiAnalysisJobController {
    private final AiAnalysisJobService service;
    private final MmAiClient mmAiClient;

    @GetMapping("/diagnostics")
    public ApiResponse<JsonNode> diagnostics() {
        return ApiResponse.ok(mmAiClient.diagnostics());
    }

    @GetMapping("/{jobId}")
    public ApiResponse<AiAnalysisJobResponse> get(@PathVariable UUID jobId) {
        return ApiResponse.ok(service.getJob(jobId));
    }

    @GetMapping("/latest")
    public ApiResponse<AiAnalysisJobResponse> latest(
        @RequestParam AiAnalysisType analysisType,
        @RequestParam String entityType,
        @RequestParam(required = false) UUID entityId
    ) {
        return ApiResponse.ok(service.latest(analysisType, entityType, entityId));
    }
}
