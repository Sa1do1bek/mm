package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.payload.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/kimyosanoat-issues")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
@RequiredArgsConstructor
public class KimyosanoatIssueController {
    private final KimyosanoatIssueService service;

    @GetMapping
    public ApiResponse<KimyosanoatIssueService.Documents> list(@RequestParam UUID warehouseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(warehouseId, page, size));
    }

    @GetMapping("/{sourceId}/material-candidates")
    public ApiResponse<java.util.List<KimyosanoatIssueService.MaterialCandidate>> materialCandidates(
            @PathVariable long sourceId, @RequestParam UUID warehouseId, @RequestParam String fingerprint) {
        return ApiResponse.ok(service.materialCandidates(warehouseId, sourceId, fingerprint));
    }

    @PostMapping("/{sourceId}/approve")
    public ApiResponse<KimyosanoatIssueService.Approval> approve(@PathVariable long sourceId,
            @RequestBody KimyosanoatIssueService.ApproveRequest request) {
        return ApiResponse.ok(service.approve(sourceId, request));
    }
}
