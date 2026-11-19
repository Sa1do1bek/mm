package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeRequest;
import com.enterprise.mm.payload.PriceAiDtos.PriceAiAnalyzeResponse;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.PriceAiAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/price-ai")
@RequiredArgsConstructor
public class PriceAiController {
    private final PriceAiAnalysisService service;
    private final AiAnalysisJobService aiAnalysisJobService;

    @PostMapping("/analyze")
    public ApiResponse<PriceAiAnalyzeResponse> analyze(@Valid @RequestBody PriceAiAnalyzeRequest request) {
        return ApiResponse.ok(service.analyze(request));
    }

    @PostMapping("/analyze/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> analyzeAsync(
        @Valid @RequestBody PriceAiAnalyzeRequest request,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        return ApiResponse.ok(aiAnalysisJobService.startPriceJob(request, language));
    }
}
