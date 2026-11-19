package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.StockAccountResolver;
import com.enterprise.mm.service.MaterialStockAccountResolutionService;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/stock-account-resolution")
@RequiredArgsConstructor
public class StockAccountResolutionController {
    private final StockAccountResolver resolver;
    private final MaterialStockAccountResolutionService materialAccounts;

    public record AccountRequest(@NotNull UUID accountId) {}
    public record ManualReceiptOptions(StockAccountResolver.Resolution configured, List<StockAccountResolver.Resolution> candidates) {}

    private static StockAccountResolver.Resolution option(MaterialStockAccountResolutionService.Account account, String source) {
        return account == null ? null : new StockAccountResolver.Resolution(account.id(), account.code(), account.name(), null, source);
    }

    @GetMapping("/manual-receipt/materials/{materialId}/options")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<ManualReceiptOptions> manualReceiptOptions(@PathVariable UUID materialId) {
        var resolution = materialAccounts.get(materialId);
        var candidates = resolution.candidateAccounts().stream().map(account -> option(account, "ONE_C")).toList();
        var configured = candidates.isEmpty() ? option(resolution.effectiveAccount(), "MATERIAL") : null;
        return ApiResponse.ok(new ManualReceiptOptions(configured, candidates));
    }

    @GetMapping("/manual-receipt/materials/{materialId}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<StockAccountResolver.Resolution> manualReceiptMaterial(@PathVariable UUID materialId) {
        return ApiResponse.ok(resolver.findManualReceiptAccount(materialId).orElse(null));
    }

    @GetMapping("/manual-receipt/materials/{materialId}/resolution")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','VIEWER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<MaterialStockAccountResolutionService.Resolution> manualReceiptResolution(@PathVariable UUID materialId) {
        return ApiResponse.ok(materialAccounts.get(materialId));
    }

    @PutMapping("/manual-receipt/materials/{materialId}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<StockAccountResolver.Resolution> configureManualReceiptMaterial(
        @PathVariable UUID materialId, @Valid @RequestBody AccountRequest request) {
        return ApiResponse.ok(resolver.configureManualReceiptAccount(materialId, request.accountId()));
    }

    @GetMapping("/materials/{materialId}")
    public ApiResponse<StockAccountResolver.Resolution> material(@PathVariable UUID materialId) {
        return ApiResponse.ok(resolver.resolveForMaterial(materialId));
    }

    @GetMapping("/eligible-accounts")
    public ApiResponse<List<StockAccountResolver.EligibleAccount>> eligibleAccounts() {
        return ApiResponse.ok(resolver.eligibleAccounts());
    }
}
