package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.enums.StockRiskSeverity;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.StockRiskAlertResponse;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.Criteria;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.PagedRows;
import com.enterprise.mm.repository.StockRiskAlertQueryRepository.Row;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AccessScope;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockRiskAlertService {
    private static final int DEFAULT_PERIOD_DAYS = 90;
    private static final int MIN_PERIOD_DAYS = 1;
    private static final int MAX_PERIOD_DAYS = 365;
    private static final int HIGH_RISK_DAYS = 7;
    private static final int MEDIUM_RISK_DAYS = 30;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final List<String> CONSUMPTION_MOVEMENT_TYPES = List.of(MovementType.ISSUE.name());

    private final RoleDataScopeService roleDataScopeService;
    private final StockRiskAlertQueryRepository queryRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final WarehouseRepository warehouseRepository;
    private final MaterialRepository materialRepository;

    @Transactional(readOnly = true)
    public Page<StockRiskAlertResponse> search(
            Integer periodDays,
            boolean includeHealthy,
            UUID materialId,
            UUID companyId,
            UUID warehouseId,
            String search,
            List<String> status,
            Pageable pageable
    ) {
        int normalizedPeriodDays = normalizePeriodDays(periodDays);
        Pageable page = normalizePageable(pageable);
        Set<Integer> severityRanks = parseSeverityRanks(status);
        String language = normalizeLanguage();
        RiskScope scope = resolveScope(companyId, roleDataScopeService.availabilityWarehouseId(warehouseId));
        if (scope.companyId() == null) {
            // No company in scope: never fall back to a tenant-wide scan.
            return Page.empty(page);
        }

        // Original direct-ID lookup permits inactive materials and reports missing IDs as 404.
        // Load at most one entity; all calculation/filtering/paging remains in SQL.
        if (materialId != null && scope.warehouseId() == null) {
            materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
                    .filter(material -> scope.companyId().equals(material.getCompanyId()))
                    .orElseThrow(() -> new NotFoundException("Material not found: " + materialId));
        }

        Criteria criteria = new Criteria(
                TenantContext.currentTenant(),
                scope.companyId(),
                scope.warehouseId(),
                materialId,
                likePattern(search),
                severityRanks,
                includeHealthy,
                normalizedPeriodDays,
                LocalDateTime.now().minusDays(normalizedPeriodDays),
                StockMovementStatus.effectiveStatusNames(),
                CONSUMPTION_MOVEMENT_TYPES,
                HIGH_RISK_DAYS,
                MEDIUM_RISK_DAYS
        );

        PagedRows result = queryRepository.findPage(criteria, page);
        if (result.rows().isEmpty()) {
            return new PageImpl<>(List.of(), page, result.total());
        }

        // Units are loaded only for the rows of the current page (<= MAX_PAGE_SIZE ids).
        Map<UUID, UnitOfMeasure> unitsById = unitOfMeasureRepository.findAllById(
                result.rows().stream()
                        .map(Row::unitOfMeasureId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.toMap(UnitOfMeasure::getId, unit -> unit));

        List<StockRiskAlertResponse> content = result.rows().stream()
                .map(row -> toResponse(row, unitsById.get(row.unitOfMeasureId()), scope, language, normalizedPeriodDays))
                .toList();
        return new PageImpl<>(content, page, result.total());
    }

    private StockRiskAlertResponse toResponse(
            Row row,
            UnitOfMeasure unit,
            RiskScope scope,
            String language,
            int periodDays
    ) {
        StockRiskSeverity severity = severityFromRank(row.severityRank());
        return new StockRiskAlertResponse(
                row.materialId(),
                row.materialCode(),
                row.materialName(),
                row.unitOfMeasureId(),
                unit == null ? null : unit.getCode(),
                localizedShortName(unit, language),
                localizedLongName(unit, language),
                scope.companyId(),
                scope.warehouseId(),
                row.available(),
                row.minStock(),
                row.safetyStock(),
                row.reorderPoint(),
                periodDays,
                row.consumption(),
                row.averageDailyUsage(),
                row.daysUntilMinStock(),
                row.daysUntilOutOfStock(),
                severity,
                severity != StockRiskSeverity.OK,
                message(severity, row)
        );
    }

    private String message(StockRiskSeverity severity, Row row) {
        return switch (severity) {
            case CRITICAL -> "Available stock is at or below minimum stock.";
            case HIGH, MEDIUM -> "Material may fall below minimum stock in " + row.daysUntilMinStock()
                    + " days and may be out of stock in " + row.daysUntilOutOfStock() + " days.";
            case LOW -> "Available stock is at or below reorder point.";
            case OK -> row.averageDailyUsage().signum() == 0
                    ? "No consumption was detected during the selected period."
                    : "Stock level is healthy.";
        };
    }

    // ---- request normalization -------------------------------------------------------------

    private Pageable normalizePageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!StockRiskAlertQueryRepository.SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Unsupported sort property: " + order.getProperty()
                        + ". Allowed: " + StockRiskAlertQueryRepository.SORTABLE_PROPERTIES);
            }
        }
        int size = Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
    }

    private Set<Integer> parseSeverityRanks(List<String> status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        Set<Integer> ranks = new HashSet<>();
        for (String value : status) {
            if (value == null) continue;
            try {
                // Preserve the original exact, case-sensitive severity filter.
                ranks.add(severityRank(StockRiskSeverity.valueOf(value)));
            } catch (IllegalArgumentException ignored) {
                // Unknown values matched no rows in the original implementation.
            }
        }
        if (ranks.isEmpty()) ranks.add(-1);
        return ranks;
    }

    private int normalizePeriodDays(Integer periodDays) {
        if (periodDays == null) {
            return DEFAULT_PERIOD_DAYS;
        }
        if (periodDays < MIN_PERIOD_DAYS || periodDays > MAX_PERIOD_DAYS) {
            throw new BadRequestException("periodDays must be between 1 and 365");
        }
        return periodDays;
    }

    /** Escapes LIKE wildcards so user input is always matched literally. */
    private String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private RiskScope resolveScope(UUID requestedCompanyId, UUID warehouseId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = requestedCompanyId == null ? CompanyContext.currentCompany() : requestedCompanyId;
        if (companyId != null && !AccessScope.canAccessCompany(companyId)) {
            throw new NotFoundException("Company not found: " + companyId);
        }
        if (warehouseId == null) {
            return new RiskScope(companyId, null);
        }
        Warehouse warehouse = warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, warehouseId)
                .filter(item -> AccessScope.canAccessCompany(item.getCompanyId()))
                .orElseThrow(() -> new NotFoundException("Warehouse not found: " + warehouseId));
        if (companyId != null && !companyId.equals(warehouse.getCompanyId())) {
            throw new BadRequestException("Warehouse does not belong to selected company");
        }
        return new RiskScope(warehouse.getCompanyId(), warehouse.getId());
    }

    // ---- severity <-> rank (must match the CASE in StockRiskAlertQueryRepository) -----------

    private static StockRiskSeverity severityFromRank(int rank) {
        return switch (rank) {
            case 4 -> StockRiskSeverity.CRITICAL;
            case 3 -> StockRiskSeverity.HIGH;
            case 2 -> StockRiskSeverity.MEDIUM;
            case 1 -> StockRiskSeverity.LOW;
            default -> StockRiskSeverity.OK;
        };
    }

    private static int severityRank(StockRiskSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 4;
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
            case OK -> 0;
        };
    }

    // ---- localization ----------------------------------------------------------------------

    private String normalizeLanguage() {
        String normalized = LocaleContextHolder.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ru")) {
            return "ru";
        }
        if (normalized.startsWith("uz")) {
            return "uz";
        }
        return "en";
    }

    private String localizedShortName(UnitOfMeasure unit, String language) {
        return com.enterprise.mm.common.UnitOfMeasureLabels.localizedShort(unit, Locale.forLanguageTag(language));
    }

    private String localizedLongName(UnitOfMeasure unit, String language) {
        if (unit == null) {
            return null;
        }
        return switch (language) {
            case "ru" -> firstNonBlank(unit.getMeasurementNameLongRu(), unit.getMeasurementNameLong(), unit.getName(), unit.getCode());
            case "uz" -> firstNonBlank(unit.getMeasurementNameLongUz(), unit.getMeasurementNameLong(), unit.getName(), unit.getCode());
            default -> firstNonBlank(unit.getMeasurementNameLong(), unit.getName(), unit.getCode());
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record RiskScope(UUID companyId, UUID warehouseId) {
    }
}
