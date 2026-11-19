package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAnalyzeRequest;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAnalyzeResponse;
import com.enterprise.mm.service.DocumentAiAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/document-ai")
@RequiredArgsConstructor
public class DocumentAiController {
    private final DocumentAiAnalysisService service;

    @PostMapping("/analyze")
    public ApiResponse<DocumentAiAnalyzeResponse> analyze(@Valid @RequestBody DocumentAiAnalyzeRequest request) {
        return ApiResponse.ok(service.analyze(request));
    }
}

