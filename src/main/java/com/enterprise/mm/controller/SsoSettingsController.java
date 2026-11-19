package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.SsoSettingsDto;
import com.enterprise.mm.service.SsoSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/sso")
@RequiredArgsConstructor
public class SsoSettingsController {
    private final SsoSettingsService service;

    @GetMapping
    public ApiResponse<SsoSettingsDto> get() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping
    public ApiResponse<SsoSettingsDto> save(@RequestBody SsoSettingsDto dto) {
        return ApiResponse.ok(service.save(dto));
    }

    @PostMapping("/test")
    public ApiResponse<String> test() {
        return ApiResponse.ok(service.test());
    }
}

