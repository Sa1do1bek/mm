package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.SmsTestRequest;
import com.enterprise.mm.payload.SmsSettingsDto;
import com.enterprise.mm.service.SmsSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/sms")
@RequiredArgsConstructor
public class SmsSettingsController {
    private final SmsSettingsService service;

    @GetMapping
    public ApiResponse<SmsSettingsDto> get() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping
    public ApiResponse<SmsSettingsDto> save(@RequestBody SmsSettingsDto dto) {
        return ApiResponse.ok(service.save(dto));
    }

    @PostMapping("/test")
    public ApiResponse<String> test(@RequestBody(required = false) SmsTestRequest request) {
        return ApiResponse.ok(service.test(request == null ? null : request.phoneNumber()));
    }
}

