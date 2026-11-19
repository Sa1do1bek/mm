package com.enterprise.mm.service;

import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.entity.StockValuationHistory;
import com.enterprise.mm.payload.MaterialCommandViewDtos.AllowedModuleKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.LinkTarget;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MarketPriceEvidence;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MetricStatus;
import com.enterprise.mm.payload.MaterialCommandViewDtos.MoneyMetric;
import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationFilter;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationHistoryRow;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationResponse;
import com.enterprise.mm.payload.MaterialCommandViewDtos.ValuationSnapshot;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import com.enterprise.mm.repository.StockValuationHistoryRepository;
import com.enterprise.mm.service.MaterialCommandViewScopeGuard.MaterialScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaterialValuationQueryService {
    public static final int PRICE_STALE_DAYS = 90;

    private final StockValuationHistoryRepository historyRepository;
    private final MaterialMarketPriceRepository marketPriceRepository;
    private final InventoryAccountingSettingsRepository settingsRepository;
    private final Clock materialCommandViewClock;

    public ValuationResponse valuation(MaterialScope scope, ValuationFilter filter, Pageable pageable) {
        return valuation(scope, filter, Set.of(SectionKey.VALUATION, SectionKey.MARKET_PRICE), pageable,
            LocalDate.now(materialCommandViewClock));
    }

    public ValuationResponse valuation(
        MaterialScope scope,
        ValuationFilter filter,
        Set<SectionKey> readableSections,
        Pageable pageable,
        LocalDate asOfDate
    ) {
        Set<SectionKey> sections = readableSections == null ? Set.of() : Set.copyOf(readableSections);
        boolean valuationReadable = sections.contains(SectionKey.VALUATION);
        boolean marketReadable = sections.contains(SectionKey.MARKET_PRICE);
        ValuationFilter effectiveFilter = filter == null ? new ValuationFilter(null, null) : filter;
        UUID warehouseId = scope.warehouseId();
        LocalDateTime dateFrom = effectiveFilter.dateFrom() == null
            ? null
            : effectiveFilter.dateFrom().atStartOfDay();
        LocalDateTime dateToExclusive = effectiveFilter.dateTo() == null
            ? null
            : effectiveFilter.dateTo().plusDays(1).atStartOfDay();
        Page<StockValuationHistory> history = valuationReadable
            ? historyRepository.findMaterial360History(scope.tenantId(), scope.companyId(), scope.materialId(),
                warehouseId, dateFrom, dateToExclusive, bounded(pageable))
            : Page.empty(bounded(pageable));
        String currencyCode = valuationReadable
            ? settingsRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(scope.tenantId(), scope.companyId())
                .map(InventoryAccountingSettings::getBaseCurrencyCode).orElse(null)
            : null;
        StockValuationHistory latest = valuationReadable
            ? historyRepository.findMaterial360Latest(
                scope.tenantId(), scope.companyId(), scope.materialId(), warehouseId).orElse(null)
            : null;

        Page<ValuationHistoryRow> mappedHistory = history.map(item -> mapHistory(item, currencyCode));
        List<MarketPriceEvidence> marketPrices = marketReadable ? marketPriceRepository.findMaterial360Evidence(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                warehouseId,
                asOfDate,
                PageRequest.of(0, 5))
            .stream()
            .map(item -> mapMarketPrice(item, asOfDate))
            .toList() : List.of();
        return new ValuationResponse(
            snapshot(scope, latest, currencyCode, asOfDate, valuationReadable),
            PageResponse.from(mappedHistory),
            marketPrices);
    }

    public Optional<LocalDateTime> latestValuationAt(MaterialScope scope) {
        Page<StockValuationHistory> latest = historyRepository.findMaterial360History(
            scope.tenantId(),
            scope.companyId(),
            scope.materialId(),
            scope.warehouseId(),
            null,
            null,
            PageRequest.of(0, 1));
        return latest.getContent().stream().findFirst().map(StockValuationHistory::getCalculatedAt);
    }

    public Optional<LocalDate> latestMarketPriceDate(MaterialScope scope) {
        return marketPriceRepository.findMaterial360Evidence(
                scope.tenantId(),
                scope.companyId(),
                scope.materialId(),
                scope.warehouseId(),
                LocalDate.now(materialCommandViewClock),
                PageRequest.of(0, 1))
            .stream()
            .findFirst()
            .map(MaterialMarketPrice::getEffectiveDate);
    }

    private ValuationSnapshot snapshot(
        MaterialScope scope,
        StockValuationHistory latest,
        String currencyCode,
        LocalDate asOfDate,
        boolean valuationReadable
    ) {
        if (!valuationReadable) {
            return new ValuationSnapshot(
                null, MoneyMetric.denied(), null, null);
        }
        if (latest == null || currencyCode == null) {
            return new ValuationSnapshot(
                latest == null ? scope.material().getValuationMethod() : latest.getValuationMethod(),
                MoneyMetric.noData(),
                latest == null ? null : latest.getCalculatedAt(),
                latest == null ? null : latest.getValuationBasis());
        }
        boolean stale = latest.getCalculatedAt() != null
            && latest.getCalculatedAt().toLocalDate().isBefore(asOfDate.minusDays(PRICE_STALE_DAYS));
        MoneyMetric stockValue = stale
            ? MoneyMetric.stale(latest.getTotalValue(), currencyCode)
            : MoneyMetric.available(latest.getTotalValue(), currencyCode);
        return new ValuationSnapshot(
            latest.getValuationMethod(),
            stockValue,
            latest.getCalculatedAt(),
            latest.getValuationBasis());
    }

    private ValuationHistoryRow mapHistory(StockValuationHistory history, String currencyCode) {
        return new ValuationHistoryRow(
            history.getId(),
            history.getWarehouseId(),
            history.getWarehouseName(),
            decimal(history.getQuantity()),
            currencyCode == null ? null : decimal(history.getUnitCost()),
            currencyCode == null ? null : decimal(history.getTotalValue()),
            currencyCode,
            history.getValuationMethod(),
            history.getValuationBasis(),
            history.getCalculatedAt(),
            LinkTarget.of(AllowedModuleKey.VALUATION.key(), history.getId(), Map.of()));
    }

    private MarketPriceEvidence mapMarketPrice(MaterialMarketPrice price, LocalDate asOfDate) {
        MetricStatus status = price.getEffectiveDate().isBefore(asOfDate.minusDays(PRICE_STALE_DAYS))
            ? MetricStatus.STALE
            : MetricStatus.AVAILABLE;
        return new MarketPriceEvidence(
            price.getId(),
            decimal(price.getMarketUnitPrice()),
            price.getCurrencyCode(),
            price.getSourceType(),
            price.getSourceReference(),
            price.getEffectiveDate(),
            status,
            LinkTarget.of(AllowedModuleKey.MATERIAL_MARKET_PRICES.key(), price.getId(), Map.of()));
    }

    private Pageable bounded(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, MaterialInventoryQueryService.DEFAULT_PAGE_SIZE);
        }
        return PageRequest.of(
            Math.max(pageable.getPageNumber(), 0),
            Math.min(pageable.getPageSize(), MaterialInventoryQueryService.MAX_PAGE_SIZE));
    }

    private String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
