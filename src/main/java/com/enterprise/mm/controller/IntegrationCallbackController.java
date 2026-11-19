package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCallbackResponse;
import com.enterprise.mm.service.IntegrationSettingService;
import com.enterprise.mm.service.TreasuryCallbackAuthenticityPolicy;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/callbacks")
@RequiredArgsConstructor
public class IntegrationCallbackController {
    private final IntegrationSettingService service;
    private final TreasuryCallbackAuthenticityPolicy treasuryCallbackAuthenticityPolicy;

    @PostMapping("/{system}")
    @PreAuthorize("hasAnyRole('ADMIN','INTEGRATION_OPERATOR')")
    public ApiResponse<IntegrationCallbackResponse> callback(
        @PathVariable String system,
        @RequestBody(required = false) IntegrationCallbackRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestHeader(value = "X-MM-Timestamp", required = false) String timestamp,
        @RequestHeader(value = "X-MM-Signature", required = false) String signature
    ) {
        ExternalIntegrationSystem integrationSystem = ExternalIntegrationSystem.from(system);
        treasuryCallbackAuthenticityPolicy.requireAuthentic(
            integrationSystem, request, idempotencyKey, timestamp, signature
        );
        return ApiResponse.ok(service.acceptCallback(integrationSystem, request, idempotencyKey));
    }
}
