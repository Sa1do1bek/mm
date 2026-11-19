package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eReport;
import com.enterprise.mm.payload.ErpIntegrationE2eDtos.ErpIntegrationE2eRequest;
import com.enterprise.mm.service.ErpIntegrationE2eService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/erp-e2e")
@RequiredArgsConstructor
public class ErpIntegrationE2eController {
    private final ErpIntegrationE2eService service;

    @GetMapping
    public ApiResponse<ErpIntegrationE2eReport> report() {
        return ApiResponse.ok(service.run(new ErpIntegrationE2eRequest(null, null, false, false, true, false, 50)));
    }

    @PostMapping("/run")
    public ApiResponse<ErpIntegrationE2eReport> run(@RequestBody(required = false) ErpIntegrationE2eRequest request) {
        return ApiResponse.ok(service.run(request));
    }
}

