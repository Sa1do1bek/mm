package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.EImzoDocumentSignatureRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.EImzoDocumentSignatureResponse;
import com.enterprise.mm.service.EImzoDocumentSignatureService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/e-imzo/documents")
@RequiredArgsConstructor
public class EImzoDocumentController {
    private final EImzoDocumentSignatureService service;

    @PostMapping("/verify")
    public ApiResponse<EImzoDocumentSignatureResponse> verify(@RequestBody EImzoDocumentSignatureRequest request) {
        return ApiResponse.ok(service.verify(request));
    }

    @PostMapping("/sign")
    public ApiResponse<EImzoDocumentSignatureResponse> sign(@RequestBody EImzoDocumentSignatureRequest request) {
        return ApiResponse.ok(service.sign(request));
    }
}

