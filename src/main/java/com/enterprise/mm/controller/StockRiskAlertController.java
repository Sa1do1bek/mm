package com.enterprise.mm.controller;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.StockRiskAlertResponse;
import com.enterprise.mm.service.StockRiskAlertService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/stock-risk-alerts")
@RequiredArgsConstructor
public class StockRiskAlertController {
    private final StockRiskAlertService service;

    /**
     * Allowed sortBy values: severity, daysUntilMinStock, daysUntilOutOfStock, availableQuantity,
     * averageDailyUsage, materialCode, materialName. Without sortBy the default order is
     * most severe first, then soonest to reach minimum stock, then material code.
     */
    @GetMapping
    public ApiResponse<List<StockRiskAlertResponse>> list(
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(defaultValue = "false") boolean includeHealthy,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(paged(periodDays, includeHealthy, materialId, companyId, orgId,
                warehouseId, search, status, sortBy, sortDir, page, size).data().content());
    }

    @GetMapping("/page")
    public ApiResponse<PageResponse<StockRiskAlertResponse>> paged(
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(defaultValue = "false") boolean includeHealthy,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (companyId != null && orgId != null && !companyId.equals(orgId)) {
            throw new BadRequestException("companyId and orgId must match when both are provided");
        }
        if (page < 0) {
            throw new BadRequestException("page must not be negative");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1");
        }
        UUID effectiveCompanyId = companyId == null ? orgId : companyId;

        Page<StockRiskAlertResponse> result = service.search(
                periodDays,
                includeHealthy,
                materialId,
                effectiveCompanyId,
                warehouseId,
                search,
                status,
                pageable(page, size, sortBy, sortDir)
        );
        return ApiResponse.ok(PageResponse.from(result));
    }

    private Pageable pageable(int page, int size, String sortBy, String sortDir) {
        if (sortBy == null || sortBy.isBlank()) {
            return PageRequest.of(page, size);
        }
        Sort.Direction direction;
        switch (sortDir.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> direction = Sort.Direction.ASC;
            case "desc" -> direction = Sort.Direction.DESC;
            default -> throw new BadRequestException("sortDir must be asc or desc");
        }
        return PageRequest.of(page, size, Sort.by(direction, sortBy.trim()));
    }
}