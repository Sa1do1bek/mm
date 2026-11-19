package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.MmWmsMaterialBaselinePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, role-protected replay of the MM material master into WMS. */
@RestController
@RequestMapping("/api/integrations/wms/material-baseline")
@RequiredArgsConstructor
public class MmWmsMaterialBaselineController {

    private final MmWmsMaterialBaselinePublisher publisher;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')")
    public ApiResponse<MmWmsMaterialBaselinePublisher.BaselineQueueResult> queue() {
        return ApiResponse.ok(publisher.queueForWms());
    }
}
