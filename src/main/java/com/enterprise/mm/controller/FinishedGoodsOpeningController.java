package com.enterprise.mm.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.FinishedGoodsOpeningMigrationService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration/finished-goods-opening")
public class FinishedGoodsOpeningController {
    private static final UUID COMPANY = UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f");
    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 10, 8);
    private final FinishedGoodsOpeningMigrationService service;
    public FinishedGoodsOpeningController(FinishedGoodsOpeningMigrationService service) {
        this.service = service;
    }

    public record ApplyRequest(UUID warehouseId, String previewHash, String idempotencyKey,
                               Boolean acceptUnreconciled1cMovements) {}

    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')")
    public ApiResponse<FinishedGoodsOpeningMigrationService.Preview> preview(@RequestParam UUID warehouseId) {
        checkCompany();
        return ApiResponse.ok(service.preview(SNAPSHOT, warehouseId));
    }

    @PostMapping("/apply")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<FinishedGoodsOpeningMigrationService.Result> apply(@RequestBody ApplyRequest request) {
        checkCompany();
        if (request == null || !Boolean.TRUE.equals(request.acceptUnreconciled1cMovements()))
            throw new ConflictException("Explicit acceptance of unavailable later 1C movements is required");
        return ApiResponse.ok(service.apply(SNAPSHOT, request.warehouseId(), request.previewHash(), request.idempotencyKey()));
    }

    private void checkCompany() {
        if (!COMPANY.equals(CompanyContext.currentCompany()))
            throw new BadRequestException("This migration is only authorized for the Navoiyazot snapshot");
    }
}
