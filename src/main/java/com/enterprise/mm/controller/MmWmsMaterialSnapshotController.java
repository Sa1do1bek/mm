package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.MmErpMaterialSnapshotPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Queues an integrity-checked MM material snapshot for staged WMS activation. */
@RestController
@RequestMapping("/api/integrations/wms/material-snapshots")
@RequiredArgsConstructor
public class MmWmsMaterialSnapshotController {

    private final MmErpMaterialSnapshotPublisher publisher;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')")
    public ApiResponse<MmErpMaterialSnapshotPublisher.SnapshotQueueResult> queue(
        @RequestParam(required = false) Integer chunkSize
    ) {
        return ApiResponse.ok(publisher.queueForWms(chunkSize));
    }
}
