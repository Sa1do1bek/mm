package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CodeGenerationResponse;
import com.enterprise.mm.service.CodeGeneratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/codes")
@RequiredArgsConstructor
public class CodeController {
    private final CodeGeneratorService service;

    @GetMapping("/next")
    public ApiResponse<CodeGenerationResponse> nextCode(
        @RequestParam String entityType
    ) {
        return ApiResponse.ok(service.generateNextCode(entityType));
    }
}
