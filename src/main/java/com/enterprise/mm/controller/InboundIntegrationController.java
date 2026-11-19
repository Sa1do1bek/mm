package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncRequest;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSyncResult;
import com.enterprise.mm.payload.InboundIntegrationDtos.InboundSystemSyncResult;
import com.enterprise.mm.service.InboundIntegrationSyncService;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/inbound")
@RequiredArgsConstructor
public class InboundIntegrationController {
    private final InboundIntegrationSyncService service;

    @PostMapping("/sync-all")
    public ApiResponse<InboundSyncResult> syncAll(@RequestBody(required = false) InboundSyncRequest request) {
        return ApiResponse.ok(service.syncAll(request));
    }

    @PostMapping("/{system}/sync")
    public ApiResponse<InboundSystemSyncResult> syncSystem(@PathVariable String system, @RequestBody(required = false) InboundSyncRequest request) {
        return ApiResponse.ok(service.syncSystem(ExternalIntegrationSystem.from(system), request));
    }
}

