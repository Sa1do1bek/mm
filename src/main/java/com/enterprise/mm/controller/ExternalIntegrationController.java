package com.enterprise.mm.controller;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSendRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSyncTestRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationSystemDto;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.service.ExternalIntegrationService;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/systems")
@RequiredArgsConstructor
public class ExternalIntegrationController {
    private final ExternalIntegrationService service;

    @GetMapping
    public ApiResponse<PageResponse<IntegrationSystemDto>> list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.listSystems(search, status, dateFrom, dateTo)));
    }

    @GetMapping("/{system}/health")
    public ApiResponse<IntegrationHealthDto> health(@PathVariable String system) {
        return ApiResponse.ok(service.health(ExternalIntegrationSystem.from(system)));
    }

    @PostMapping("/{system}/events")
    public ApiResponse<IntegrationEventResponse> enqueue(
        @PathVariable String system,
        @Valid @RequestBody IntegrationSendRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.enqueue(ExternalIntegrationSystem.from(system), request, idempotencyKey));
    }

    @PostMapping("/{system}/sync-test")
    public ApiResponse<IntegrationEventResponse> syncTest(
        @PathVariable String system,
        @RequestBody(required = false) IntegrationSyncTestRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        IntegrationSyncTestRequest payload = request == null ? new IntegrationSyncTestRequest(null, null, null) : request;
        return ApiResponse.ok(service.syncTest(ExternalIntegrationSystem.from(system), payload, idempotencyKey));
    }
}
