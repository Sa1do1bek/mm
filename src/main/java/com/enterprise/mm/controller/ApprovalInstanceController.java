package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowPreviewResponse;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval-instances")
@RequiredArgsConstructor
public class ApprovalInstanceController {
    private final ApprovalEngineService service;

    @GetMapping("/current")
    public ApiResponse<ApprovalInstanceResponse> current(@RequestParam String moduleKey, @RequestParam UUID entityId) {
        return ApiResponse.ok(service.currentInstance(moduleKey, entityId));
    }

    @GetMapping("/preview")
    public ApiResponse<ApprovalFlowPreviewResponse> preview(
        @RequestParam String moduleKey,
        @RequestParam(required = false) UUID entityId
    ) {
        return ApiResponse.ok(service.previewFlow(moduleKey, entityId));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<ApprovalHistoryResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(service.history(id));
    }
}
