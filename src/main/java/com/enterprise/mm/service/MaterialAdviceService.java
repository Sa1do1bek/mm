package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialAdviceStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.payload.MaterialAdviceDto;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Service
public class MaterialAdviceService {
    private static final int DEFAULT_PERIOD_DAYS = 90;
    private static final int MIN_PERIOD_DAYS = 1;
    private static final int MAX_PERIOD_DAYS = 365;
    private static final int WATCH_BUFFER_DAYS = 7;
    private static final List<MovementType> CONSUMPTION_TYPES = List.of(MovementType.ISSUE, MovementType.WRITE_OFF);

    private final StockBalanceRepository stockBalanceRepository;
    private final StockMovementRepository stockMovementRepository;

    public MaterialAdviceService(StockBalanceRepository stockBalanceRepository,
                                 StockMovementRepository stockMovementRepository) {
        this.stockBalanceRepository = stockBalanceRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public Page<MaterialResponse> enrich(Page<MaterialResponse> page, Integer periodDays, UUID warehouseId) {
        if (page == null || !page.hasContent()) {
            return page;
        }
        int normalizedPeriodDays = normalizePeriodDays(periodDays);
        List<UUID> materialIds = page.getContent().stream()
            .map(MaterialResponse::getId)
            .toList();

        Map<UUID, BigDecimal> availableByMaterial = stockBalanceRepository.sumAvailableQuantityByMaterialIds(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId,
            materialIds
        ).stream().collect(Collectors.toMap(
            StockBalanceRepository.MaterialQuantityProjection::getMaterialId,
            item -> nvl(item.getQuantity())
        ));

        Map<UUID, BigDecimal> consumptionByMaterial = stockMovementRepository.sumConsumptionQuantityByMaterialIds(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            warehouseId,
            StockMovementStatus.effectiveStatusNames(),
            CONSUMPTION_TYPES,
            LocalDateTime.now().minusDays(normalizedPeriodDays),
            materialIds
        ).stream().collect(Collectors.toMap(
            StockMovementRepository.MaterialQuantityProjection::getMaterialId,
            item -> nvl(item.getQuantity())
        ));

        page.getContent().forEach(material -> material.setAdvice(calculate(
            material,
            normalizedPeriodDays,
            warehouseId,
            availableByMaterial.getOrDefault(material.getId(), BigDecimal.ZERO),
            consumptionByMaterial.getOrDefault(material.getId(), BigDecimal.ZERO)
        )));
        return page;
    }

    private MaterialAdviceDto calculate(
        MaterialResponse material,
        int periodDays,
        UUID warehouseId,
        BigDecimal availableQuantity,
        BigDecimal consumptionQuantity
    ) {
        BigDecimal minStock = nvl(material.getMinStock());
        Integer leadTimeDays = material.getReplenishmentLeadTimeDays();
        BigDecimal averageDailyUsage = consumptionQuantity.signum() <= 0
            ? BigDecimal.ZERO
            : consumptionQuantity.divide(BigDecimal.valueOf(periodDays), 4, RoundingMode.HALF_UP);
        Integer daysUntilMinStock = daysUntilMinStock(availableQuantity, minStock, averageDailyUsage);
        Integer leadTimeGapDays = leadTimeDays == null || daysUntilMinStock == null
            ? null
            : leadTimeDays - daysUntilMinStock;
        MaterialAdviceStatus status = status(minStock, leadTimeDays, availableQuantity, averageDailyUsage, daysUntilMinStock);

        return new MaterialAdviceDto(
            warehouseId,
            availableQuantity,
            minStock,
            leadTimeDays,
            periodDays,
            consumptionQuantity,
            averageDailyUsage,
            daysUntilMinStock,
            leadTimeGapDays,
            status.name()
        );
    }

    private MaterialAdviceStatus status(
        BigDecimal minStock,
        Integer leadTimeDays,
        BigDecimal availableQuantity,
        BigDecimal averageDailyUsage,
        Integer daysUntilMinStock
    ) {
        if (minStock.signum() <= 0) {
            return MaterialAdviceStatus.NO_MIN_STOCK;
        }
        if (leadTimeDays == null) {
            return MaterialAdviceStatus.NO_LEAD_TIME;
        }
        if (availableQuantity.compareTo(minStock) <= 0) {
            return MaterialAdviceStatus.CRITICAL;
        }
        if (averageDailyUsage.signum() <= 0 || daysUntilMinStock == null) {
            return MaterialAdviceStatus.NO_USAGE;
        }
        if (daysUntilMinStock < leadTimeDays) {
            return MaterialAdviceStatus.URGENT;
        }
        if (daysUntilMinStock <= leadTimeDays + WATCH_BUFFER_DAYS) {
            return MaterialAdviceStatus.WATCH;
        }
        return MaterialAdviceStatus.OK;
    }

    private Integer daysUntilMinStock(BigDecimal availableQuantity, BigDecimal minStock, BigDecimal averageDailyUsage) {
        BigDecimal quantityUntilMin = availableQuantity.subtract(minStock);
        if (quantityUntilMin.signum() <= 0) {
            return 0;
        }
        if (averageDailyUsage.signum() <= 0) {
            return null;
        }
        return quantityUntilMin.divide(averageDailyUsage, 0, RoundingMode.CEILING).intValue();
    }

    private int normalizePeriodDays(Integer periodDays) {
        if (periodDays == null) {
            return DEFAULT_PERIOD_DAYS;
        }
        if (periodDays < MIN_PERIOD_DAYS || periodDays > MAX_PERIOD_DAYS) {
            throw new com.enterprise.mm.error.BadRequestException("advicePeriodDays must be between 1 and 365");
        }
        return periodDays;
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
