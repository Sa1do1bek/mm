package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.MaterialStockAccountResolutionService;
import com.enterprise.mm.service.MaterialStockAccountRecommendationService;
import com.enterprise.mm.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/materials/{materialId}/manual-receipt-stock-account")
@RequiredArgsConstructor
public class MaterialStockAccountController {
    private final MaterialStockAccountResolutionService service;
    private final MaterialStockAccountRecommendationService recommendations;
    private final AuditService audit;

    public record FallbackRequest(@NotNull UUID accountId, @NotNull Long expectedMaterialVersion,
                                  @NotNull String expectedEvidenceFingerprint, UUID recommendationJobId) {}

    @GetMapping("/resolution")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialStockAccountResolutionService.Resolution> resolution(@PathVariable UUID materialId) {
        return ApiResponse.ok(service.get(materialId));
    }

    @GetMapping("/evidence")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<PageResponse<MaterialStockAccountResolutionService.Evidence>> evidence(
        @PathVariable UUID materialId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.evidence(materialId, page, size));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    @Transactional
    public ApiResponse<MaterialStockAccountResolutionService.Resolution> saveFallback(
        @PathVariable UUID materialId, @Valid @RequestBody FallbackRequest request) {
        var current = service.get(materialId);
        if (request.recommendationJobId() != null)
            recommendations.verifyApplied(request.recommendationJobId(), materialId, request.accountId(), current);
        var saved = service.configureFallback(materialId, request.accountId(),
            request.expectedMaterialVersion(), request.expectedEvidenceFingerprint());
        if (request.recommendationJobId() != null)
            audit.logInCurrentTransaction("Material", materialId, "AI_MANUAL_RECEIPT_FALLBACK",
                materialId.toString(), current.configuredAccount(), request.recommendationJobId());
        return ApiResponse.ok(saved);
    }
}
