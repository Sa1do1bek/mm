package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ServiceAcceptanceActResponse;
import com.enterprise.mm.service.ServiceAcceptanceActRepairService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/service-acceptance-workspace")
@RequiredArgsConstructor
public class ServiceAcceptanceActRepairController {
    private final ServiceAcceptanceActRepairService repairService;

    @PostMapping("/acts/{actId}/repair-approval")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ServiceAcceptanceActResponse> repairApproval(@PathVariable UUID actId) {
        return ApiResponse.ok(repairService.repairMissingTechnicalReviewApproval(actId));
    }
}
