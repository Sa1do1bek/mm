package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.MmErpMaterialSnapshotPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit cutover operation; normal material changes keep using the automatic transactional outbox. */
@RestController
@RequestMapping("/api/integrations/erp/material-snapshots")
@RequiredArgsConstructor
public class MmErpMaterialSnapshotController {

    private final MmErpMaterialSnapshotPublisher publisher;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','INTEGRATION_OPERATOR')")
    public ApiResponse<MmErpMaterialSnapshotPublisher.SnapshotQueueResult> queue(
        @RequestParam(required = false) Integer chunkSize
    ) {
        return ApiResponse.ok(publisher.queueForErp(chunkSize));
    }
}
