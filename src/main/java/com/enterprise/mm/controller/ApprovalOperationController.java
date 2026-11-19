package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ApprovalOperationResponse;
import com.enterprise.mm.service.approval.ApprovalOperationSubmissionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval-operations")
@RequiredArgsConstructor
public class ApprovalOperationController {
    private final ApprovalOperationSubmissionService service;

    @GetMapping("/latest")
    public ApiResponse<ApprovalOperationResponse> latest(
        @RequestParam String moduleKey,
        @RequestParam UUID targetId
    ) {
        return ApiResponse.ok(service.latest(moduleKey, targetId).orElse(null));
    }

    @PostMapping("/{id}/retry-apply")
    public ApiResponse<ApprovalOperationResponse> retryApply(@PathVariable UUID id) {
        return ApiResponse.ok(service.retryApply(id));
    }

    @PostMapping("/{id}/resubmit")
    public ApiResponse<ApprovalOperationResponse> resubmit(@PathVariable UUID id) {
        return ApiResponse.ok(service.resubmit(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ApprovalOperationResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

    @PostMapping("/{id}/abandon")
    public ApiResponse<ApprovalOperationResponse> abandonFailed(@PathVariable UUID id) {
        return ApiResponse.ok(service.abandonFailed(id));
    }
}
