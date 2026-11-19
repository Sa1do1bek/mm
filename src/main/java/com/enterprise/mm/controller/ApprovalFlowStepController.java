package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepRequest;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalFlowStepResponse;
import com.enterprise.mm.service.approval.ApprovalFlowAdminService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/approval-flow-steps")
@RequiredArgsConstructor
public class ApprovalFlowStepController {
    private final ApprovalFlowAdminService service;

    @PutMapping("/{id}")
    public ApiResponse<ApprovalFlowStepResponse> update(@PathVariable UUID id, @Valid @RequestBody ApprovalFlowStepRequest request) {
        return ApiResponse.ok(service.updateStep(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.deleteStep(id);
        return ApiResponse.message("soft-deleted");
    }
}
