package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ReportDtos.AbcReportDto;
import com.enterprise.mm.payload.ReportDtos.AbcXyzMatrixReportDto;
import com.enterprise.mm.payload.ReportDtos.DashboardReportDto;
import com.enterprise.mm.payload.ReportDtos.InventoryValuationDashboardDto;
import com.enterprise.mm.payload.ReportDtos.InventoryValuationReportDto;
import com.enterprise.mm.payload.ReportDtos.MovementReportDto;
import com.enterprise.mm.payload.ReportDtos.NonMovingReportDto;
import com.enterprise.mm.payload.ReportDtos.OverdueDeliveryReportDto;
import com.enterprise.mm.payload.ReportDtos.PlanFactReportDto;
import com.enterprise.mm.payload.ReportDtos.PlanFactCurrencyBucketDto;
import com.enterprise.mm.payload.ReportDtos.QueueContractDto;
import com.enterprise.mm.payload.ReportDtos.ReportMetadataDto;
import com.enterprise.mm.payload.ReportDtos.ReportMetricHealth;
import com.enterprise.mm.payload.ReportDtos.ReportsSnapshotDto;
import com.enterprise.mm.payload.ReportDtos.SavingsCurrencyBucketDto;
import com.enterprise.mm.payload.ReportDtos.StockReportDto;
import com.enterprise.mm.payload.ReportDtos.SupplierRatingReportDto;
import com.enterprise.mm.payload.ReportDtos.TurnoverReportDto;
import com.enterprise.mm.payload.ReportDtos.XyzReportDto;
import com.enterprise.mm.payload.ReportFilter;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.report.DashboardPdfRenderer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReportService {
    private static final int SNAPSHOT_LIST_LIMIT = 100;
    private static final int MAX_REPORT_FACT_ROWS = 50_000;
    private static final List<String> OPEN_PR_STATUSES = List.of(
            PurchaseRequestStatus.DRAFT.name(),
            PurchaseRequestStatus.APPROVAL_PENDING.name(),
            PurchaseRequestStatus.APPROVED.name()
    );
    private static final List<String> OPEN_PO_STATUSES = List.of(
            PurchaseOrderStatus.DRAFT.name(),
            PurchaseOrderStatus.APPROVAL_PENDING.name(),
            PurchaseOrderStatus.APPROVED.name(),
            PurchaseOrderStatus.SENT.name(),
            PurchaseOrderStatus.PARTIALLY_RECEIVED.name()
    );
    private static final List<String> COMPARABLE_SAVINGS_PR_STATUSES = List.of(
            PurchaseRequestStatus.APPROVED.name(),
            PurchaseRequestStatus.RFQ_CREATED.name(),
            PurchaseRequestStatus.PARTIALLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name(),
            PurchaseRequestStatus.PO_CREATED.name(),
            PurchaseRequestStatus.CONVERTED_TO_PO.name()
    );
    private static final List<String> COMPARABLE_SAVINGS_PO_STATUSES = List.of(
            PurchaseOrderStatus.APPROVED.name(),
            PurchaseOrderStatus.SENT.name(),
            PurchaseOrderStatus.PARTIALLY_COMPLETED.name(),
            PurchaseOrderStatus.COMPLETED.name(),
            PurchaseOrderStatus.PARTIALLY_RECEIVED.name(),
            PurchaseOrderStatus.RECEIVED.name(),
            PurchaseOrderStatus.CLOSED.name()
    );
    private static final List<MovementType> STOCK_ISSUE_TYPES = List.of(
            MovementType.ISSUE,
            MovementType.WRITE_OFF
    );

    private final StockBalanceRepository stockBalanceRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final MaterialGroupRepository materialGroupRepository;
    private final StockBatchRepository stockBatchRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryCostLayerRepository inventoryCostLayerRepository;
    private final StockCostMovementRepository stockCostMovementRepository;
    private final NonMovingStockRepository nonMovingStockRepository;
    private final NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository;
    private final ReferenceMappingRepository referenceMappingRepository;
    private final InventoryAccountingSettingsRepository inventoryAccountingSettingsRepository;
    private final ReportSupplierQueueCounter supplierQueueCounter;

    public static List<String> openPurchaseRequestStatuses() {
        return OPEN_PR_STATUSES;
    }

    public static List<String> openPurchaseOrderStatuses() {
        return OPEN_PO_STATUSES;
    }

    public DashboardReportDto dashboard() {
        return dashboard(savings());
    }

    private DashboardReportDto dashboard(BigDecimal procurementSavings) {
        return dashboard(procurementSavings, currentTotalStockValue(), overdueDeliveryCount());
    }

    private DashboardReportDto dashboard(
        BigDecimal procurementSavings,
        BigDecimal totalStockValue,
        long overdueDeliveryCount
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        BigDecimal openPurchaseRequestsAmount = nvl(
            purchaseRequestRepository.sumTotalAmountByTenantAndCompanyAndStatusIn(
                tenantId, companyId, OPEN_PR_STATUSES));
        BigDecimal openPurchaseOrdersAmount = nvl(
            purchaseOrderRepository.sumTotalAmountByTenantAndCompanyAndStatusIn(
                tenantId, companyId, OPEN_PO_STATUSES));
        BigDecimal nonMovingStockValue = nvl(
            stockBalanceRepository.sumOpenNonMovingStockValueByTenantAndCompany(
                tenantId, companyId));
        BigDecimal overdueDeliveriesAmount = nvl(
            purchaseOrderRepository.sumTotalAmountByTenantAndCompanyAndStatusInAndExpectedDeliveryDateBefore(
                tenantId,
                companyId,
                List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name()),
                LocalDate.now()));
        return new DashboardReportDto(
                activeMaterials(),
                totalStockValue,
                activeSuppliers(),
                BigDecimal.ZERO,
                purchaseRequestRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(
                    tenantId, companyId, OPEN_PR_STATUSES),
                openPurchaseRequestsAmount,
                purchaseOrderRepository.countByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(
                    tenantId, companyId, OPEN_PO_STATUSES),
                openPurchaseOrdersAmount,
                supplierQueueCounter.blacklistUnblockCountForCurrentCompany(),
                BigDecimal.ZERO,
                nonMovingStockRepository.countByTenantIdAndCompanyIdAndStatusNotAndIsDeletedFalse(
                    tenantId, companyId, "CLOSED"),
                nonMovingStockValue,
                overdueDeliveryCount,
                overdueDeliveriesAmount,
                totalStockValue,
                procurementSavings
        );
    }

    private BigDecimal currentTotalStockValue() {
        return nvl(stockBalanceRepository.sumReportStockValue(
            TenantContext.currentTenant(),
            reportCompanyId()
        ));
    }

    private long overdueDeliveryCount() {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        List<String> statuses = List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        LocalDate before = LocalDate.now();
        return purchaseOrderRepository.countByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
            tenantId, companyId, statuses, before);
    }

    public PlanFactReportDto planFact() {
        return planFact(ReportFilter.defaultPeriod());
    }

    public PlanFactReportDto planFact(ReportFilter filter) {
        PlanFactCalculation calculation = calculatePlanFact(
            scopedPurchaseRequests(filter),
            scopedPurchaseOrders(filter),
            postedPurchaseOrderReceipts(filter),
            filter
        );
        return selectPlanFact(calculation.buckets(), filter.displayCurrency());
    }

    public BigDecimal savings() {
        return savings(ReportFilter.defaultPeriod());
    }

    public BigDecimal savings(ReportFilter filter) {
        return selectSavings(
            calculateSavingsByCurrency(scopedPurchaseRequests(filter), scopedPurchaseOrders(filter), filter),
            filter.displayCurrency()
        );
    }

    public List<SavingsCurrencyBucketDto> savingsByCurrency(ReportFilter filter) {
        return calculateSavingsByCurrency(
            scopedPurchaseRequests(filter), scopedPurchaseOrders(filter), filter).entrySet().stream()
            .map(entry -> new SavingsCurrencyBucketDto(
                entry.getKey(),
                entry.getValue(),
                "For approved requests created in the period: positive baseline difference after all comparable committed orders through period end."
            ))
            .toList();
    }

    public List<SupplierRatingReportDto> supplierRating() {
        String tenantId = TenantContext.currentTenant();
        return mapSupplierRating(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId()));
    }

    private List<SupplierRatingReportDto> supplierRatingForSnapshot() {
        String tenantId = TenantContext.currentTenant();
        PageRequest limit = PageRequest.of(
            0,
            SNAPSHOT_LIST_LIMIT,
            Sort.by(Sort.Order.desc("rating"), Sort.Order.asc("code"))
        );
        return mapSupplierRating(supplierRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId(), limit));
    }

    private List<SupplierRatingReportDto> mapSupplierRating(List<Supplier> suppliers) {
        return suppliers.stream()
            .sorted(Comparator.comparing(Supplier::getRating, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(supplier -> new SupplierRatingReportDto(
                supplier.getId(),
                supplier.getCode(),
                LocalizedText.forCurrentLocale(supplier.getName()),
                supplier.getStatus(),
                supplier.getCategory(),
                nvl(supplier.getRating())
            ))
            .toList();
    }

    public List<StockReportDto> stock() {
        String tenantId = TenantContext.currentTenant();
        return stockBalanceRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId()).stream()
                .map(balance -> {
                    ReferenceLabel material = materialLabel(balance.getMaterialId());
                    ReferenceLabel warehouse = warehouseLabel(balance.getWarehouseId());
                    return new StockReportDto(
                        balance.getMaterialId(),
                        material.code(),
                        material.name(),
                        balance.getWarehouseId(),
                        warehouse.code(),
                        warehouse.name(),
                        nvl(balance.getQuantity()),
                        nvl(balance.getAvailableQuantity()),
                        nvl(balance.getReservedQuantity()),
                        nvl(balance.getTotalValue())
                    );
                })
                .toList();
    }

    public List<MovementReportDto> movements() {
        String tenantId = TenantContext.currentTenant();
        return stockMovementRepository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenantId, reportCompanyId()).stream()
                .map(movement -> {
                    ReferenceLabel material = materialLabel(movement.getMaterialId());
                    ReferenceLabel warehouse = warehouseLabel(movement.getWarehouseId());
                    return new MovementReportDto(
                        movement.getId(),
                        movement.getMaterialId(),
                        material.code(),
                        material.name(),
                        movement.getWarehouseId(),
                        warehouse.code(),
                        warehouse.name(),
                        movement.getMovementType().name(),
                        movement.getStatus(),
                        nvl(movement.getQuantity()),
                        nvl(movement.getTotalCost())
                    );
                })
                .toList();
    }

    public List<TurnoverReportDto> turnover() {
        return turnover(stock());
    }

    private List<TurnoverReportDto> turnover(List<StockReportDto> stockRows) {
        LocalDateTime from = LocalDateTime.now().minusDays(90);
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Map<UUID, BigDecimal> issued = new HashMap<>();
        stockMovementRepository.findByTenantIdAndCompanyIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(
                tenantId, companyId, StockMovementStatus.effectiveStatusNames(), STOCK_ISSUE_TYPES, from.minusNanos(1)).stream()
                .forEach(movement -> issued.merge(movement.getMaterialId(), nvl(movement.getQuantity()), BigDecimal::add));

        Map<UUID, BigDecimal> available = new HashMap<>();
        Map<UUID, StockReportDto> stockLabels = new HashMap<>();
        stockRows.forEach(row -> available.merge(row.materialId(), row.availableQuantity(), BigDecimal::add));
        stockRows.forEach(row -> stockLabels.putIfAbsent(row.materialId(), row));

        return available.entrySet().stream()
                .map(entry -> {
                    BigDecimal issue = nvl(issued.get(entry.getKey()));
                    BigDecimal stockQty = entry.getValue();
                    BigDecimal ratio = calculateTurnoverRatio(issue, stockQty);
                    StockReportDto stockLabel = stockLabels.get(entry.getKey());
                    return new TurnoverReportDto(
                        entry.getKey(),
                        stockLabel == null ? null : stockLabel.materialCode(),
                        stockLabel == null ? null : stockLabel.materialName(),
                        issue,
                        stockQty,
                        ratio,
                        ratio == null ? ReportMetricHealth.UNAVAILABLE : ReportMetricHealth.READY,
                        ratio == null
                            ? "Unavailable because current available quantity is zero; no infinite or zero substitute is reported."
                            : "Trailing-90-day posted issue and write-off quantity divided by current available quantity."
                    );
                })
                .sorted(Comparator.comparing(
                    TurnoverReportDto::turnoverRatio,
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    static BigDecimal calculateTurnoverRatio(BigDecimal issueQuantity, BigDecimal availableQuantity) {
        BigDecimal denominator = nonNegative(availableQuantity);
        if (denominator.signum() == 0) {
            return null;
        }
        return nonNegative(issueQuantity).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    public List<AbcReportDto> abc() {
        return abc(stock());
    }

    private List<AbcReportDto> abcForSnapshot() {
        List<StockBalanceRepository.ReportMaterialValueProjection> values = stockBalanceRepository
            .findReportMaterialValues(
                TenantContext.currentTenant(),
                reportCompanyId(),
                PageRequest.of(0, SNAPSHOT_LIST_LIMIT)
            );
        Map<UUID, BigDecimal> valueByMaterial = new LinkedHashMap<>();
        Map<UUID, StockBalanceRepository.ReportMaterialValueProjection> labels = new HashMap<>();
        for (StockBalanceRepository.ReportMaterialValueProjection value : values) {
            valueByMaterial.put(value.getMaterialId(), nvl(value.getTotalValue()));
            labels.put(value.getMaterialId(), value);
        }
        return classifyAbc(valueByMaterial).stream()
            .map(row -> {
                StockBalanceRepository.ReportMaterialValueProjection label = labels.get(row.materialId());
                return new AbcReportDto(
                    row.materialId(),
                    label == null ? null : label.getMaterialCode(),
                    label == null ? null : label.getMaterialName(),
                    row.totalValue(),
                    row.cumulativeShare(),
                    row.category()
                );
            })
            .toList();
    }

    private List<AbcReportDto> abc(List<StockReportDto> stockRows) {
        Map<UUID, BigDecimal> valueByMaterial = new HashMap<>();
        Map<UUID, StockReportDto> stockLabels = new HashMap<>();
        stockRows.forEach(row -> valueByMaterial.merge(row.materialId(), row.totalValue(), BigDecimal::add));
        stockRows.forEach(row -> stockLabels.putIfAbsent(row.materialId(), row));
        return classifyAbc(valueByMaterial).stream()
            .map(row -> {
                StockReportDto stockLabel = stockLabels.get(row.materialId());
                return new AbcReportDto(
                    row.materialId(),
                    stockLabel == null ? null : stockLabel.materialCode(),
                    stockLabel == null ? null : stockLabel.materialName(),
                    row.totalValue(),
                    row.cumulativeShare(),
                    row.category()
                );
            })
            .toList();
    }

    public List<XyzReportDto> xyz() {
        return xyz(LocalDate.now());
    }

    private List<XyzReportDto> xyzForSnapshot(LocalDate asOf, List<AbcReportDto> cohort) {
        if (cohort.isEmpty()) {
            return List.of();
        }
        YearMonth periodEnd = YearMonth.from(asOf);
        LocalDateTime periodStart = periodEnd.minusMonths(11).atDay(1).atStartOfDay();
        LocalDateTime periodThrough = asOf.atTime(23, 59, 59, 999_999_999);
        Map<UUID, Map<YearMonth, BigDecimal>> monthly = new HashMap<>();
        stockMovementRepository.sumMonthlyIssueByMaterialIds(
            TenantContext.currentTenant(),
            reportCompanyId(),
            StockMovementStatus.effectiveStatusNames(),
            STOCK_ISSUE_TYPES,
            periodStart,
            periodThrough,
            cohort.stream().map(AbcReportDto::materialId).toList()
        ).forEach(row -> monthly.computeIfAbsent(row.getMaterialId(), ignored -> new HashMap<>())
            .put(
                YearMonth.of(row.getIssueYear(), row.getIssueMonth()),
                nvl(row.getQuantity())
            ));
        return cohort.stream().map(row -> {
            XyzMetrics metrics = calculateXyzMetrics(
                monthly.getOrDefault(row.materialId(), Map.of()),
                periodEnd
            );
            return new XyzReportDto(
                row.materialId(),
                row.materialCode(),
                row.materialName(),
                metrics.averageMonthlyIssue(),
                metrics.variationCoefficient(),
                metrics.category()
            );
        }).toList();
    }

    private List<XyzReportDto> xyz(LocalDate asOf) {
        YearMonth periodEnd = YearMonth.from(asOf);
        Map<UUID, Map<YearMonth, BigDecimal>> monthly = new HashMap<>();
        String tenantId = TenantContext.currentTenant();
        LocalDateTime from = periodEnd.minusMonths(11).atDay(1).atStartOfDay().minusNanos(1);
        LocalDateTime through = asOf.atTime(23, 59, 59, 999_999_999);

        stockMovementRepository.findByTenantIdAndCompanyIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse(
                tenantId, reportCompanyId(), StockMovementStatus.effectiveStatusNames(), STOCK_ISSUE_TYPES, from).stream()
                .filter(movement -> movement.getPostedAt() != null && !movement.getPostedAt().isAfter(through))
                .forEach(movement -> monthly.computeIfAbsent(movement.getMaterialId(), ignored -> new HashMap<>())
                        .merge(YearMonth.from(movement.getPostedAt()), nvl(movement.getQuantity()), BigDecimal::add));

        return monthly.entrySet().stream().map(entry -> {
            XyzMetrics metrics = calculateXyzMetrics(entry.getValue(), periodEnd);
            ReferenceLabel material = materialLabel(entry.getKey());
            return new XyzReportDto(
                entry.getKey(),
                material.code(),
                material.name(),
                metrics.averageMonthlyIssue(),
                metrics.variationCoefficient(),
                metrics.category()
            );
        }).toList();
    }

    public List<AbcXyzMatrixReportDto> abcXyzMatrix() {
        List<StockReportDto> stockRows = stock();
        List<AbcReportDto> abcRows = abc(stockRows);
        List<XyzReportDto> xyzRows = xyz();
        List<TurnoverReportDto> turnoverRows = turnover(stockRows);
        return abcXyzMatrix(abcRows, xyzRows, turnoverRows);
    }

    private List<AbcXyzMatrixReportDto> abcXyzMatrix(
        List<AbcReportDto> abcRows,
        List<XyzReportDto> xyzRows,
        List<TurnoverReportDto> turnoverRows
    ) {
        Map<UUID, AbcReportDto> abcByMaterial = abcRows.stream().collect(Collectors.toMap(AbcReportDto::materialId, row -> row));
        Map<UUID, XyzReportDto> xyzByMaterial = xyzRows.stream().collect(Collectors.toMap(XyzReportDto::materialId, row -> row));
        Map<UUID, TurnoverReportDto> turnoverByMaterial = turnoverRows.stream().collect(Collectors.toMap(TurnoverReportDto::materialId, row -> row));

        return abcByMaterial.entrySet().stream()
                .map(entry -> {
                    UUID materialId = entry.getKey();
                    AbcReportDto abcRow = entry.getValue();
                    XyzReportDto xyzRow = xyzByMaterial.get(materialId);
                    TurnoverReportDto turnoverRow = turnoverByMaterial.get(materialId);
                    String xyzCategory = xyzRow == null ? "Z" : xyzRow.category();
                    return new AbcXyzMatrixReportDto(
                            materialId,
                            abcRow.materialCode(),
                            abcRow.materialName(),
                            abcRow.totalValue(),
                            turnoverRow == null ? null : turnoverRow.turnoverRatio(),
                            abcRow.category(),
                            xyzCategory,
                            abcRow.category() + xyzCategory
                    );
                })
                .sorted(Comparator.comparing(AbcXyzMatrixReportDto::matrixCategory))
                .toList();
    }

    public List<NonMovingReportDto> nonMoving() {
        String tenantId = TenantContext.currentTenant();
        return mapNonMoving(nonMovingStockRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId()));
    }

    private List<NonMovingReportDto> nonMovingForSnapshot() {
        String tenantId = TenantContext.currentTenant();
        PageRequest limit = PageRequest.of(
            0,
            SNAPSHOT_LIST_LIMIT,
            Sort.by(Sort.Order.desc("monthsWithoutMovement"), Sort.Order.desc("createdAt"))
        );
        return mapNonMoving(nonMovingStockRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId(), limit));
    }

    private List<NonMovingReportDto> mapNonMoving(List<com.enterprise.mm.entity.NonMovingStock> stocks) {
        Map<UUID, ReferenceLabel> materials = materialLabels(
            stocks.stream().map(com.enterprise.mm.entity.NonMovingStock::getMaterialId).collect(Collectors.toSet())
        );
        Map<UUID, ReferenceLabel> warehouses = warehouseLabels(
            stocks.stream().map(com.enterprise.mm.entity.NonMovingStock::getWarehouseId).collect(Collectors.toSet())
        );
        return stocks.stream().map(stock -> {
            ReferenceLabel material = materials.getOrDefault(stock.getMaterialId(), ReferenceLabel.empty());
            ReferenceLabel warehouse = warehouses.getOrDefault(stock.getWarehouseId(), ReferenceLabel.empty());
            return new NonMovingReportDto(
                stock.getId(),
                stock.getMaterialId(),
                material.code(),
                material.name(),
                stock.getWarehouseId(),
                warehouse.code(),
                warehouse.name(),
                stock.getMonthsWithoutMovement(),
                stock.getStatus(),
                stock.getPlannedAction()
            );
        }).toList();
    }

    public List<InventoryValuationReportDto> inventoryValuation(UUID warehouseId, UUID materialId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        Map<WarehouseMaterialKey, BigDecimal> impairmentByKey = stockCostMovementRepository
            .aggregateAmountByType(tenantId, companyId, "IMPAIRMENT", warehouseId, materialId)
            .stream()
            .collect(Collectors.toMap(
                row -> new WarehouseMaterialKey(row.getWarehouseId(), row.getMaterialId()),
                row -> nvl(row.getTotalAmount()),
                BigDecimal::add
            ));
        Map<WarehouseMaterialKey, BigDecimal> frozenMoneyByKey = nonMovingStockCaseLineRepository
            .aggregateFrozenMoney(tenantId, companyId, warehouseId, materialId)
            .stream()
            .collect(Collectors.toMap(
                row -> new WarehouseMaterialKey(row.getWarehouseId(), row.getMaterialId()),
                row -> nvl(row.getFrozenMoney()),
                BigDecimal::add
            ));
        return inventoryCostLayerRepository
            .aggregateActiveInventoryValue(tenantId, companyId, warehouseId, materialId)
            .stream()
            .map(row -> {
                BigDecimal quantity = nvl(row.getQuantity());
                BigDecimal totalCost = nvl(row.getTotalCost());
                BigDecimal averageUnitCost = quantity.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(quantity, 4, RoundingMode.HALF_UP);
                WarehouseMaterialKey key = new WarehouseMaterialKey(row.getWarehouseId(), row.getMaterialId());
                ReferenceLabel warehouse = warehouseLabel(row.getWarehouseId());
                ReferenceLabel material = materialLabel(row.getMaterialId());
                ReferenceLabel materialGroup = materialGroupLabel(row.getMaterialGroupId());
                ReferenceLabel stockBatch = stockBatchLabel(row.getStockBatchId());
                return new InventoryValuationReportDto(
                    row.getWarehouseId(),
                    warehouse.code(),
                    warehouse.name(),
                    row.getMaterialId(),
                    material.code(),
                    material.name(),
                    row.getMaterialGroupId(),
                    materialGroup.code(),
                    materialGroup.name(),
                    row.getStockBatchId(),
                    stockBatch.code(),
                    stockBatch.name(),
                    quantity,
                    totalCost,
                    averageUnitCost,
                    impairmentByKey.getOrDefault(key, BigDecimal.ZERO),
                    frozenMoneyByKey.getOrDefault(key, BigDecimal.ZERO)
                );
            })
            .sorted(Comparator.comparing(InventoryValuationReportDto::totalValue).reversed())
            .toList();
    }

    public InventoryValuationDashboardDto inventoryValuationDashboard(UUID warehouseId, UUID materialId) {
        List<InventoryValuationReportDto> rows = inventoryValuation(warehouseId, materialId);
        return new InventoryValuationDashboardDto(
            rows.stream().map(InventoryValuationReportDto::totalValue).reduce(BigDecimal.ZERO, BigDecimal::add),
            rows.stream().map(InventoryValuationReportDto::impairmentReserve).reduce(BigDecimal.ZERO, BigDecimal::add),
            rows.stream().map(InventoryValuationReportDto::frozenMoney).reduce(BigDecimal.ZERO, BigDecimal::add),
            rows.stream().map(InventoryValuationReportDto::materialId).distinct().count(),
            rows.stream().map(InventoryValuationReportDto::stockBatchId).filter(java.util.Objects::nonNull).distinct().count(),
            rows
        );
    }

    public List<OverdueDeliveryReportDto> overdueDeliveries() {
        String tenantId = TenantContext.currentTenant();
        List<String> statuses = List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        LocalDate before = LocalDate.now();

        return mapOverdueDeliveries(
            purchaseOrderRepository.findByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
                tenantId, reportCompanyId(), statuses, before));
    }

    private List<OverdueDeliveryReportDto> overdueDeliveriesForSnapshot() {
        String tenantId = TenantContext.currentTenant();
        List<String> statuses = List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name());
        LocalDate before = LocalDate.now();
        PageRequest limit = PageRequest.of(
            0,
            SNAPSHOT_LIST_LIMIT,
            Sort.by(Sort.Order.asc("expectedDeliveryDate"), Sort.Order.asc("code"))
        );
        return mapOverdueDeliveries(
            purchaseOrderRepository.findByTenantIdAndCompanyIdAndStatusInAndExpectedDeliveryDateBeforeAndIsDeletedFalse(
                tenantId, reportCompanyId(), statuses, before, limit));
    }

    private List<OverdueDeliveryReportDto> mapOverdueDeliveries(List<PurchaseOrder> orders) {
        Map<UUID, ReferenceLabel> suppliers = supplierLabels(
            orders.stream().map(PurchaseOrder::getSupplierId).collect(Collectors.toSet())
        );
        return orders.stream()
            // UX-LIVE-09: demo/test purchase orders must not surface in the overdue-delivery KPI.
            .filter(order -> com.enterprise.mm.common.OperationalDataQuality.isOperationalRecord(
                order.getCode(), order.getName()))
            .map(order -> {
            ReferenceLabel supplier = suppliers.getOrDefault(order.getSupplierId(), ReferenceLabel.empty());
            return new OverdueDeliveryReportDto(
                order.getId(),
                order.getCode(),
                order.getSupplierId(),
                supplier.code(),
                supplier.name(),
                order.getExpectedDeliveryDate(),
                order.getStatus(),
                nvl(order.getTotalAmount())
            );
        }).toList();
    }

    public ReportsSnapshotDto snapshot() {
        return snapshot(ReportFilter.defaultPeriod());
    }

    public ReportsSnapshotDto snapshot(ReportFilter filter) {
        List<PurchaseRequest> requests = scopedPurchaseRequests(filter);
        List<PurchaseOrder> orders = scopedPurchaseOrders(filter);
        List<StockMovement> receiptFacts = postedPurchaseOrderReceipts(filter);
        PlanFactCalculation planFactCalculation = calculatePlanFact(requests, orders, receiptFacts, filter);
        List<PlanFactCurrencyBucketDto> planFactBuckets = planFactCalculation.buckets();
        Map<String, BigDecimal> savingsAmounts = calculateSavingsByCurrency(requests, orders, filter);
        List<SavingsCurrencyBucketDto> savingsBuckets = savingsAmounts.entrySet().stream()
            .map(entry -> new SavingsCurrencyBucketDto(
                entry.getKey(),
                entry.getValue(),
                "For approved requests created in the period: positive baseline difference after all comparable committed orders through period end."
            ))
            .toList();
        PlanFactReportDto selectedPlanFact = selectPlanFact(planFactBuckets, filter.displayCurrency());
        BigDecimal selectedSavings = selectSavings(savingsAmounts, filter.displayCurrency());
        DashboardReportDto filteredDashboard = dashboard(
            selectedSavings,
            currentTotalStockValue(),
            overdueDeliveryCount()
        );
        List<SupplierRatingReportDto> supplierRows = supplierRatingForSnapshot();
        List<AbcReportDto> abcRows = abcForSnapshot();
        List<XyzReportDto> xyzRows = xyzForSnapshot(filter.to(), abcRows);
        List<NonMovingReportDto> nonMovingRows = nonMovingForSnapshot();
        List<OverdueDeliveryReportDto> overdueRows = overdueDeliveriesForSnapshot();

        boolean selectedCurrencyMissing = filter.displayCurrency() != null
            && planFactBuckets.stream().noneMatch(bucket -> filter.displayCurrency().equals(bucket.currencyCode()))
            && !savingsAmounts.containsKey(filter.displayCurrency());
        boolean multipleCurrencies = distinctCurrencies(planFactBuckets, savingsAmounts).size() > 1;
        List<String> sourceMessages = new ArrayList<>();
        sourceMessages.add(
            "All report sources are forced to the current company, including for global administrators; "
                + "no cross-company base-currency total is produced."
        );
        sourceMessages.addAll(planFactCalculation.messages());
        if (multipleCurrencies && filter.displayCurrency() == null) {
            sourceMessages.add("Monetary metrics are separated by currency; no cross-currency total was calculated.");
        }
        if (selectedCurrencyMissing) {
            sourceMessages.add("No comparable monetary facts are available for " + filter.displayCurrency() + ".");
        }
        BaseCurrencyResolution baseCurrency = reportBaseCurrency();
        if (baseCurrency.degraded()) {
            sourceMessages.add(baseCurrency.message());
        } else {
            sourceMessages.add(
                "Stock balance, ABC and valuation amounts are stated in company base currency "
                    + baseCurrency.code() + "; the selected procurement currency does not relabel them."
            );
        }
        ReportMetricHealth health = selectedCurrencyMissing
            || (multipleCurrencies && filter.displayCurrency() == null)
            || planFactCalculation.health() == ReportMetricHealth.PARTIAL
            || baseCurrency.degraded()
            ? ReportMetricHealth.PARTIAL
            : ReportMetricHealth.READY;
        UUID companyId = reportCompanyId();
        String baseCurrencyCode = baseCurrency.code();
        ReportMetadataDto metadata = new ReportMetadataDto(
            Instant.now(),
            filter.from(),
            filter.to(),
            companyId,
            "CURRENT_COMPANY",
            filter.displayCurrency(),
            baseCurrencyCode,
            health,
            List.copyOf(sourceMessages),
            Map.of(
                "openPurchaseRequests", "DRAFT, APPROVAL_PENDING and APPROVED purchase requests.",
                "openPurchaseOrders", "DRAFT, APPROVAL_PENDING, APPROVED, SENT and PARTIALLY_RECEIVED purchase orders.",
                "blockedSuppliers", "Exact current-company BLACKLIST_UNBLOCK governance queue: blocked status, a blocking reason, or a pending unblock request.",
                "abc", "Up to 100 materials by current stock value; class is selected from cumulative share before the current material. A zero-value cohort is UNCLASSIFIED.",
                "xyz", "For the displayed ABC cohort: coefficient of variation across twelve calendar months ending periodTo, including zero-issue months.",
                "turnover", "Trailing-90-day APPROVED/POSTED ISSUE plus WRITE_OFF quantity divided by current available quantity; unavailable when current available quantity is zero.",
                "planFact", "Request creation dates define planned value; order dates define ordered value; received value sums unique POSTED PurchaseOrder RECEIPT facts by postedAt and order currency.",
                "savings", "Approved requests created in-period: one baseline less all comparable committed orders through period end, in the same currency."
            ),
            Map.ofEntries(
                Map.entry("materials", "CURRENT_SNAPSHOT: active GOODS materials, including unclassified records shown in the goods catalog, at generatedAt; selected period does not apply."),
                Map.entry("materialsAmount", "CURRENT_SNAPSHOT: same current stock balance value as totalStockValue, exposed separately for the materials card."),
                Map.entry("suppliers", "CURRENT_SNAPSHOT: active master data at generatedAt; selected period does not apply."),
                Map.entry("suppliersAmount", "CURRENT_SNAPSHOT: currently zero until supplier exposure, obligation, or turnover semantics are selected."),
                Map.entry("openPurchaseRequests", "CURRENT_SNAPSHOT: current workflow statuses at generatedAt; selected period does not apply."),
                Map.entry("openPurchaseRequestsAmount", "CURRENT_SNAPSHOT: totalAmount for open purchase requests at generatedAt; selected period does not apply."),
                Map.entry("openPurchaseOrders", "CURRENT_SNAPSHOT: current workflow statuses at generatedAt; selected period does not apply."),
                Map.entry("openPurchaseOrdersAmount", "CURRENT_SNAPSHOT: totalAmount for open purchase orders at generatedAt; selected period does not apply."),
                Map.entry("blockedSuppliers", "CURRENT_SNAPSHOT: current supplier status at generatedAt; selected period does not apply."),
                Map.entry("blockedSuppliersAmount", "CURRENT_SNAPSHOT: currently zero until supplier exposure, obligation, or turnover semantics are selected."),
                Map.entry("nonMovingPositions", "CURRENT_SNAPSHOT: current open non-moving positions at generatedAt; selected period does not apply."),
                Map.entry("nonMovingStockValue", "CURRENT_SNAPSHOT: current stock balance value for open non-moving material and warehouse positions at generatedAt."),
                Map.entry("overdueDeliveries", "CURRENT_SNAPSHOT: SENT/PARTIALLY_RECEIVED orders due before generatedAt date; selected period does not apply."),
                Map.entry("overdueDeliveriesAmount", "CURRENT_SNAPSHOT: totalAmount for SENT/PARTIALLY_RECEIVED orders due before generatedAt date."),
                Map.entry("totalStockValue", "CURRENT_SNAPSHOT: current stock balance value in "
                    + baseCurrencyCode + " at generatedAt; selected period and procurement currency do not apply."),
                Map.entry("procurementSavings", "SELECTED_PERIOD: request cohort from periodFrom through periodTo, constrained by selected currency."),
                Map.entry("planFact", "SELECTED_PERIOD: request, order and posted receipt facts from periodFrom through periodTo."),
                Map.entry("abc", "CURRENT_SNAPSHOT_SAMPLE: up to 100 highest-value materials at generatedAt."),
                Map.entry("xyz", "ROLLING_WINDOW_SAMPLE: twelve calendar months ending periodTo for the displayed ABC cohort."),
                Map.entry("supplierRating", "CURRENT_SNAPSHOT_SAMPLE: up to 100 suppliers by current rating."),
                Map.entry("nonMoving", "CURRENT_SNAPSHOT_SAMPLE: up to 100 oldest non-moving positions."),
                Map.entry("overdueDeliveriesList", "CURRENT_SNAPSHOT_SAMPLE: up to 100 earliest overdue deliveries.")
            )
        );
        List<QueueContractDto> queueContracts = List.of(
            new QueueContractDto(
                "materials",
                List.of(MaterialStatus.ACTIVE.name()),
                "/app/materials?status=ACTIVE&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "suppliers",
                List.of(),
                "/app/suppliers?queue=ALL&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "open-purchase-requests",
                openPurchaseRequestStatuses(),
                "/app/purchase-requests?status=" + String.join(",", openPurchaseRequestStatuses())
                    + "&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "open-purchase-orders",
                openPurchaseOrderStatuses(),
                "/app/purchase-orders?tab=registry&status=" + String.join(",", openPurchaseOrderStatuses())
                    + "&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "blocked-suppliers",
                List.of("BLOCKED"),
                "/app/suppliers?queue=BLACKLIST_UNBLOCK&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "non-moving-positions",
                nonClosedNonMovingStatuses(),
                "/app/non-moving-stock-dashboard?mode=detected&status="
                    + String.join(",", nonClosedNonMovingStatuses())
                    + "&companyScope=CURRENT_COMPANY"
            ),
            new QueueContractDto(
                "overdue-deliveries",
                List.of(PurchaseOrderStatus.SENT.name(), PurchaseOrderStatus.PARTIALLY_RECEIVED.name()),
                "/app/purchase-orders?tab=registry&status=SENT,PARTIALLY_RECEIVED&expectedDeliveryDateTo="
                    + LocalDate.now().minusDays(1)
                    + "&companyScope=CURRENT_COMPANY"
            )
        );

        return new ReportsSnapshotDto(
                filteredDashboard,
                selectedPlanFact,
                supplierRows,
                abcRows,
                xyzRows,
                nonMovingRows,
                overdueRows,
                metadata,
                planFactBuckets,
                savingsBuckets,
                queueContracts
        );
    }

    public byte[] stockBalancesExcel() {
        return stockBalancesExcel(ReportFilter.defaultPeriod());
    }

    public byte[] stockBalancesExcel(ReportFilter filter) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BaseCurrencyResolution baseCurrency = reportBaseCurrency();
            String baseCurrencyCode = baseCurrency.code();
            writeStockExportContextSheet(
                workbook,
                filter,
                baseCurrency,
                "CURRENT_SNAPSHOT: stock balances are current at export time; selected period is not applied."
            );
            var sheet = workbook.createSheet("Stock balances");
            Row header = sheet.createRow(0);
            List<String> columns = List.of("Material ID", "Material code", "Material name", "Warehouse ID", "Warehouse code", "Warehouse name", "Quantity", "Available", "Reserved", "Total value (" + baseCurrencyCode + ")");
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
            }
            int rowIndex = 1;
            for (StockReportDto balance : stock()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(balance.materialId().toString());
                row.createCell(1).setCellValue(text(balance.materialCode()));
                row.createCell(2).setCellValue(text(balance.materialName()));
                row.createCell(3).setCellValue(balance.warehouseId().toString());
                row.createCell(4).setCellValue(text(balance.warehouseCode()));
                row.createCell(5).setCellValue(text(balance.warehouseName()));
                row.createCell(6).setCellValue(balance.quantity().doubleValue());
                row.createCell(7).setCellValue(balance.availableQuantity().doubleValue());
                row.createCell(8).setCellValue(balance.reservedQuantity().doubleValue());
                row.createCell(9).setCellValue(balance.totalValue().doubleValue());
            }
            autosize(sheet, columns.size());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build Excel report", ex);
        }
    }

    public byte[] supplierRatingExcel() {
        return supplierRatingExcel(ReportFilter.defaultPeriod());
    }

    public byte[] supplierRatingExcel(ReportFilter filter) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeExportContextSheet(
                workbook,
                filter,
                "CURRENT_SNAPSHOT: supplier ratings are current at export time; selected period is not applied."
            );
            var sheet = workbook.createSheet("Supplier rating");
            Row header = sheet.createRow(0);
            List<String> columns = List.of("Code", "Name", "Status", "Category", "Rating");
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
            }
            int rowIndex = 1;
            for (SupplierRatingReportDto supplier : supplierRating()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(supplier.code());
                row.createCell(1).setCellValue(supplier.name());
                row.createCell(2).setCellValue(supplier.status());
                row.createCell(3).setCellValue(supplier.category());
                row.createCell(4).setCellValue(supplier.rating().doubleValue());
            }
            autosize(sheet, columns.size());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build Excel report", ex);
        }
    }

    public byte[] referenceMappingsExcel() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Reference mappings");
            Row header = sheet.createRow(0);
            List<String> columns = List.of(
                    "Reference type",
                    "Reference ID",
                    "Reference code",
                    "Reference name",
                    "External system",
                    "External code",
                    "External name",
                    "Accounting area",
                    "Chart of accounts",
                    "Account",
                    "Subaccount",
                    "Cost center",
                    "Profit center",
                    "Warehouse accounting code",
                    "Tax code",
                    "Currency",
                    "Priority",
                    "Default",
                    "Valid from",
                    "Valid to",
                    "Status"
            );
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
            }

            int rowIndex = 1;
            String tenantId = TenantContext.currentTenant();
            List<ReferenceMapping> mappings = referenceMappingRepository
                .findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByReferenceEntityTypeAscExternalSystemAscAccountingAreaAscPriorityAsc(
                    tenantId, reportCompanyId());

            for (ReferenceMapping mapping : mappings) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(text(mapping.getReferenceEntityType()));
                row.createCell(1).setCellValue(mapping.getReferenceId() == null ? "" : mapping.getReferenceId().toString());
                row.createCell(2).setCellValue(text(mapping.getReferenceCode()));
                row.createCell(3).setCellValue(text(mapping.getReferenceName()));
                row.createCell(4).setCellValue(text(mapping.getExternalSystem()));
                row.createCell(5).setCellValue(text(mapping.getExternalCode()));
                row.createCell(6).setCellValue(text(mapping.getExternalName()));
                row.createCell(7).setCellValue(text(mapping.getAccountingArea()));
                row.createCell(8).setCellValue(text(mapping.getChartOfAccountsCode()));
                row.createCell(9).setCellValue(text(mapping.getAccountCode()));
                row.createCell(10).setCellValue(text(mapping.getSubAccountCode()));
                row.createCell(11).setCellValue(text(mapping.getCostCenter()));
                row.createCell(12).setCellValue(text(mapping.getProfitCenter()));
                row.createCell(13).setCellValue(text(mapping.getWarehouseAccountingCode()));
                row.createCell(14).setCellValue(text(mapping.getTaxCode()));
                row.createCell(15).setCellValue(text(mapping.getCurrencyCode()));
                row.createCell(16).setCellValue(mapping.getPriority());
                row.createCell(17).setCellValue(mapping.isDefaultMapping());
                row.createCell(18).setCellValue(mapping.getValidFrom() == null ? "" : mapping.getValidFrom().toString());
                row.createCell(19).setCellValue(mapping.getValidTo() == null ? "" : mapping.getValidTo().toString());
                row.createCell(20).setCellValue(text(mapping.getStatus()));
            }

            autosize(sheet, columns.size());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build reference mappings Excel", ex);
        }
    }

    public byte[] analyticsExcel() {
        return analyticsExcel(ReportFilter.defaultPeriod());
    }

    public byte[] analyticsExcel(ReportFilter filter) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ReportsSnapshotDto report = snapshot(filter);
            writeMetadataSheet(workbook, report.metadata());
            writeKeyValueSheet(
                workbook, "Dashboard", report.dashboard(), report.metadata().baseCurrencyCode());
            writePlanFactSheet(workbook, report.planFactByCurrency());
            writeAbcSheet(workbook, report.abc());
            writeXyzSheet(workbook, report.xyz());
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build analytics Excel", ex);
        }
    }

    public byte[] dashboardPdf() {
        return dashboardPdf(ReportFilter.defaultPeriod());
    }

    public byte[] dashboardPdf(ReportFilter filter) {
        try {
            return new DashboardPdfRenderer().render(snapshot(filter));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot build PDF report", ex);
        }
    }

    static List<AbcClassification> classifyAbc(Map<UUID, BigDecimal> valueByMaterial) {
        List<Map.Entry<UUID, BigDecimal>> sorted = valueByMaterial.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .map(entry -> Map.entry(entry.getKey(), nonNegative(entry.getValue())))
            .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().toString()))
            .toList();
        BigDecimal total = sorted.stream()
            .map(Map.Entry::getValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AbcClassification> rows = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> entry : sorted) {
            if (total.signum() == 0) {
                rows.add(new AbcClassification(
                    entry.getKey(),
                    entry.getValue(),
                    BigDecimal.ZERO.setScale(4),
                    "UNCLASSIFIED"
                ));
                continue;
            }
            BigDecimal shareBefore = total.signum() == 0
                ? BigDecimal.ZERO
                : cumulative.divide(total, 4, RoundingMode.HALF_UP);
            String category = shareBefore.compareTo(new BigDecimal("0.80")) < 0 ? "A"
                : shareBefore.compareTo(new BigDecimal("0.95")) < 0 ? "B" : "C";
            cumulative = cumulative.add(entry.getValue());
            BigDecimal cumulativeShare = total.signum() == 0
                ? BigDecimal.ZERO.setScale(4)
                : cumulative.divide(total, 4, RoundingMode.HALF_UP);
            rows.add(new AbcClassification(entry.getKey(), entry.getValue(), cumulativeShare, category));
        }
        return List.copyOf(rows);
    }

    static XyzMetrics calculateXyzMetrics(Map<YearMonth, BigDecimal> issueByMonth, YearMonth periodEnd) {
        List<BigDecimal> values = new ArrayList<>(12);
        for (int index = 11; index >= 0; index--) {
            values.add(nonNegative(issueByMonth.get(periodEnd.minusMonths(index))));
        }
        BigDecimal average = values.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        BigDecimal variance = values.stream()
            .map(value -> value.subtract(average).pow(2))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
        BigDecimal coefficient = average.signum() == 0
            ? BigDecimal.ZERO.setScale(4)
            : sqrt(variance).divide(average, 4, RoundingMode.HALF_UP);
        String category = coefficient.compareTo(new BigDecimal("0.10")) <= 0 ? "X"
            : coefficient.compareTo(new BigDecimal("0.25")) <= 0 ? "Y" : "Z";
        return new XyzMetrics(average, coefficient, category);
    }

    static Map<String, BigDecimal> calculateSavingsByCurrency(
        List<PurchaseRequest> requests,
        List<PurchaseOrder> orders,
        ReportFilter filter
    ) {
        Map<UUID, List<PurchaseOrder>> ordersByRequest = orders.stream()
            .filter(order -> order.getPurchaseRequestId() != null)
            .filter(order -> COMPARABLE_SAVINGS_PO_STATUSES.contains(order.getStatus()))
            .filter(order -> filter == null || !effectiveOrderDate(order).isAfter(filter.to()))
            .collect(Collectors.groupingBy(PurchaseOrder::getPurchaseRequestId));
        Map<String, BigDecimal> result = new java.util.TreeMap<>();
        for (PurchaseRequest request : requests) {
            if (request.getId() == null || !COMPARABLE_SAVINGS_PR_STATUSES.contains(request.getStatus())) {
                continue;
            }
            if (filter != null && !inPeriod(request.getCreatedAt(), filter)) {
                continue;
            }
            String requestCurrency = normalizedCurrency(request.getCurrencyCode());
            if (filter != null && filter.displayCurrency() != null
                && !filter.displayCurrency().equals(requestCurrency)) {
                continue;
            }
            List<PurchaseOrder> comparableOrders = ordersByRequest.getOrDefault(request.getId(), List.of());
            if (comparableOrders.isEmpty()
                || comparableOrders.stream().anyMatch(order -> !requestCurrency.equals(normalizedCurrency(order.getCurrencyCode())))) {
                continue;
            }
            BigDecimal committed = comparableOrders.stream()
                .map(PurchaseOrder::getTotalAmount)
                .map(ReportService::nonNegative)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal saving = nonNegative(request.getTotalAmount()).subtract(committed).max(BigDecimal.ZERO);
            result.merge(requestCurrency, saving, BigDecimal::add);
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    static List<PlanFactCurrencyBucketDto> calculatePlanFactByCurrency(
        List<PurchaseRequest> requests,
        List<PurchaseOrder> orders,
        ReportFilter filter
    ) {
        return calculatePlanFact(requests, orders, List.of(), filter).buckets();
    }

    static List<PlanFactCurrencyBucketDto> calculatePlanFactByCurrency(
        List<PurchaseRequest> requests,
        List<PurchaseOrder> orders,
        List<StockMovement> receiptMovements,
        ReportFilter filter
    ) {
        return calculatePlanFact(requests, orders, receiptMovements, filter).buckets();
    }

    private static PlanFactCalculation calculatePlanFact(
        List<PurchaseRequest> requests,
        List<PurchaseOrder> orders,
        List<StockMovement> receiptMovements,
        ReportFilter filter
    ) {
        Map<String, MutablePlanFact> buckets = new java.util.TreeMap<>();
        requests.stream()
            .filter(request -> filter == null || inPeriod(request.getCreatedAt(), filter))
            .filter(request -> !PurchaseRequestStatus.CANCELLED.name().equals(request.getStatus()))
            .filter(request -> !PurchaseRequestStatus.REJECTED.name().equals(request.getStatus()))
            .filter(request -> !PurchaseRequestStatus.RETURNED.name().equals(request.getStatus()))
            .filter(request -> filter == null || filter.displayCurrency() == null
                || filter.displayCurrency().equals(normalizedCurrency(request.getCurrencyCode())))
            .forEach(request -> {
                MutablePlanFact bucket = buckets.computeIfAbsent(
                    normalizedCurrency(request.getCurrencyCode()),
                    ignored -> new MutablePlanFact()
                );
                bucket.planned = bucket.planned.add(nonNegative(request.getTotalAmount()));
            });

        orders.stream()
            .filter(order -> filter == null || inPeriod(effectiveOrderDate(order), filter))
            .filter(order -> !PurchaseOrderStatus.CANCELLED.name().equals(order.getStatus()))
            .filter(order -> !PurchaseOrderStatus.REJECTED.name().equals(order.getStatus()))
            .filter(order -> !PurchaseOrderStatus.RETURNED.name().equals(order.getStatus()))
            .filter(order -> filter == null || filter.displayCurrency() == null
                || filter.displayCurrency().equals(normalizedCurrency(order.getCurrencyCode())))
            .forEach(order -> {
                MutablePlanFact bucket = buckets.computeIfAbsent(
                    normalizedCurrency(order.getCurrencyCode()),
                    ignored -> new MutablePlanFact()
                );
                BigDecimal amount = nonNegative(order.getTotalAmount());
                bucket.ordered = bucket.ordered.add(amount);
            });

        Map<UUID, PurchaseOrder> ordersById = orders.stream()
            .filter(order -> order.getId() != null)
            .collect(Collectors.toMap(PurchaseOrder::getId, order -> order, (left, right) -> left));
        Set<UUID> seenMovementIds = new HashSet<>();
        Set<String> messages = new LinkedHashSet<>();
        for (StockMovement movement : receiptMovements) {
            if (!isPostedPurchaseOrderReceiptInPeriod(movement, filter)) {
                continue;
            }
            if (movement.getId() == null) {
                messages.add("A posted purchase-order receipt without a stable movement id was excluded.");
                continue;
            }
            if (!seenMovementIds.add(movement.getId())) {
                continue;
            }
            PurchaseOrder order = ordersById.get(movement.getReferenceId());
            if (order == null) {
                messages.add("A posted receipt could not be linked to a purchase order and was excluded.");
                continue;
            }
            String currency = normalizedCurrency(order.getCurrencyCode());
            if (filter != null && filter.displayCurrency() != null
                && !filter.displayCurrency().equals(currency)) {
                continue;
            }
            if (movement.getTotalCost() == null || movement.getTotalCost().signum() < 0) {
                messages.add("A posted receipt without a valid monetary cost was excluded.");
                continue;
            }
            BigDecimal exchangeRate = order.getExchangeRate();
            if (exchangeRate == null || exchangeRate.signum() <= 0) {
                messages.add("A posted receipt without a valid purchase-order exchange rate was excluded.");
                continue;
            }
            BigDecimal receiptAmount = movement.getTotalCost().divide(exchangeRate, 4, RoundingMode.HALF_UP);
            MutablePlanFact bucket = buckets.computeIfAbsent(currency, ignored -> new MutablePlanFact());
            bucket.received = bucket.received.add(receiptAmount);
        }

        boolean hasServiceStyleOrders = orders.stream()
            .filter(order -> filter == null || inPeriod(effectiveOrderDate(order), filter))
            .filter(order -> !PurchaseOrderStatus.CANCELLED.name().equals(order.getStatus()))
            .filter(order -> filter == null || filter.displayCurrency() == null
                || filter.displayCurrency().equals(normalizedCurrency(order.getCurrencyCode())))
            .map(PurchaseOrder::getRequestType)
            .filter(Objects::nonNull)
            .anyMatch(RequestType::isServiceStyle);
        if (hasServiceStyleOrders) {
            messages.add("Received value currently includes posted material receipts only; service and works acceptance values are unavailable.");
        }

        ReportMetricHealth receivedHealth = messages.isEmpty()
            ? ReportMetricHealth.READY
            : ReportMetricHealth.PARTIAL;
        String receivedDefinition = "Sum of unique POSTED PurchaseOrder RECEIPT movement totalCost in the selected period, divided by the recorded purchase-order exchange rate.";
        List<String> immutableMessages = List.copyOf(messages);
        List<PlanFactCurrencyBucketDto> result = buckets.entrySet().stream()
            .map(entry -> new PlanFactCurrencyBucketDto(
                entry.getKey(),
                entry.getValue().planned,
                entry.getValue().ordered,
                entry.getValue().received,
                entry.getValue().planned.subtract(entry.getValue().ordered),
                receivedHealth,
                receivedDefinition,
                immutableMessages
            ))
            .toList();
        return new PlanFactCalculation(result, receivedHealth, immutableMessages);
    }

    private PlanFactReportDto selectPlanFact(List<PlanFactCurrencyBucketDto> buckets, String displayCurrency) {
        PlanFactCurrencyBucketDto selected;
        if (displayCurrency != null) {
            selected = buckets.stream()
                .filter(bucket -> displayCurrency.equals(bucket.currencyCode()))
                .findFirst()
                .orElse(null);
        } else if (buckets.size() == 1) {
            selected = buckets.get(0);
        } else if (buckets.isEmpty()) {
            selected = null;
        } else {
            // Prefer the most active single-currency bucket rather than returning an empty
            // plan/fact slice (dashboard and PDF both rely on this primary selection).
            selected = buckets.stream()
                .max(java.util.Comparator.comparing(bucket ->
                    nvl(bucket.plannedAmount())
                        .add(nvl(bucket.orderedAmount()))
                        .add(nvl(bucket.receivedAmount()))))
                .orElse(null);
        }
        if (selected == null) {
            return new PlanFactReportDto(
                null,
                null,
                null,
                null,
                displayCurrency,
                ReportMetricHealth.UNAVAILABLE,
                "Select one currency. Cross-currency plan/fact totals are not calculated."
            );
        }
        return new PlanFactReportDto(
            selected.plannedAmount(),
            selected.orderedAmount(),
            selected.receivedAmount(),
            selected.varianceAmount(),
            selected.currencyCode(),
            selected.receivedHealth(),
            "Request creation dates define planned value; order dates define ordered value; received value uses actual posted receipt facts."
        );
    }

    private BigDecimal selectSavings(Map<String, BigDecimal> savingsByCurrency, String displayCurrency) {
        if (displayCurrency != null) {
            return savingsByCurrency.get(displayCurrency);
        }
        return savingsByCurrency.size() == 1 ? savingsByCurrency.values().iterator().next() : null;
    }

    private List<PurchaseRequest> scopedPurchaseRequests(ReportFilter filter) {
        return requireBoundedReportCohort(
            purchaseRequestRepository.findReportWindow(
                TenantContext.currentTenant(),
                reportCompanyId(),
                filter.from().atStartOfDay(),
                filter.to().atTime(23, 59, 59, 999_999_999),
                PageRequest.of(0, MAX_REPORT_FACT_ROWS + 1)
            ),
            "purchase requests"
        );
    }

    private List<PurchaseOrder> scopedPurchaseOrders(ReportFilter filter) {
        LocalDateTime periodStart = filter.from().atStartOfDay();
        LocalDateTime periodEnd = filter.to().atTime(23, 59, 59, 999_999_999);
        return requireBoundedReportCohort(
            purchaseOrderRepository.findReportWindow(
                TenantContext.currentTenant(),
                reportCompanyId(),
                filter.from(),
                filter.to(),
                periodStart,
                periodEnd,
                PageRequest.of(0, MAX_REPORT_FACT_ROWS + 1)
            ),
            "purchase orders"
        );
    }

    private List<StockMovement> postedPurchaseOrderReceipts(ReportFilter filter) {
        LocalDateTime periodStart = (filter == null ? LocalDate.of(1900, 1, 1) : filter.from()).atStartOfDay();
        LocalDateTime periodEnd = (filter == null ? LocalDate.now() : filter.to())
            .atTime(23, 59, 59, 999_999_999);
        return requireBoundedReportCohort(
            stockMovementRepository.findPostedPurchaseOrderReceipts(
                TenantContext.currentTenant(),
                reportCompanyId(),
                "PurchaseOrder",
                MovementType.RECEIPT,
                StockMovementStatus.POSTED.name(),
                periodStart,
                periodEnd,
                PageRequest.of(0, MAX_REPORT_FACT_ROWS + 1)
            ),
            "posted receipt facts"
        );
    }

    private <T> List<T> requireBoundedReportCohort(List<T> rows, String source) {
        requireWithinFactRowLimit(rows.size(), source);
        return rows;
    }

    static void requireWithinFactRowLimit(int rowCount, String source) {
        if (rowCount > MAX_REPORT_FACT_ROWS) {
            throw new BadRequestException(
                "Report " + source + " cohort exceeds " + MAX_REPORT_FACT_ROWS
                    + " rows; narrow the selected period."
            );
        }
    }

    private static boolean isPostedPurchaseOrderReceiptInPeriod(StockMovement movement, ReportFilter filter) {
        if (movement == null
            || !"PurchaseOrder".equals(movement.getReferenceType())
            || movement.getMovementType() != MovementType.RECEIPT
            || !StockMovementStatus.POSTED.name().equals(movement.getStatus())
            || movement.getPostedAt() == null) {
            return false;
        }
        return filter == null || inPeriod(movement.getPostedAt(), filter);
    }

    private static boolean inPeriod(LocalDateTime value, ReportFilter filter) {
        return value != null && inPeriod(value.toLocalDate(), filter);
    }

    private static boolean inPeriod(LocalDate value, ReportFilter filter) {
        return value != null && !value.isBefore(filter.from()) && !value.isAfter(filter.to());
    }

    private static LocalDate effectiveOrderDate(PurchaseOrder order) {
        if (order.getOrderDate() != null) {
            return order.getOrderDate();
        }
        return order.getCreatedAt() == null ? LocalDate.MIN : order.getCreatedAt().toLocalDate();
    }

    private static String normalizedCurrency(String currencyCode) {
        return currencyCode == null || currencyCode.isBlank()
            ? "UZS"
            : currencyCode.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static List<String> distinctCurrencies(
        List<PlanFactCurrencyBucketDto> planFact,
        Map<String, BigDecimal> savings
    ) {
        return java.util.stream.Stream.concat(
                planFact.stream().map(PlanFactCurrencyBucketDto::currencyCode),
                savings.keySet().stream()
            )
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    static record AbcClassification(
        UUID materialId,
        BigDecimal totalValue,
        BigDecimal cumulativeShare,
        String category
    ) {
    }

    static record XyzMetrics(
        BigDecimal averageMonthlyIssue,
        BigDecimal variationCoefficient,
        String category
    ) {
    }

    private record PlanFactCalculation(
        List<PlanFactCurrencyBucketDto> buckets,
        ReportMetricHealth health,
        List<String> messages
    ) {
    }

    private record BaseCurrencyResolution(
        String code,
        boolean degraded,
        String message
    ) {
    }

    private static final class MutablePlanFact {
        private BigDecimal planned = BigDecimal.ZERO;
        private BigDecimal ordered = BigDecimal.ZERO;
        private BigDecimal received = BigDecimal.ZERO;
    }

    private record ReferenceLabel(String code, String name) {
        static ReferenceLabel empty() {
            return new ReferenceLabel(null, null);
        }
    }

    private Map<UUID, ReferenceLabel> materialLabels(Set<UUID> ids) {
        List<UUID> present = ids.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) return Map.of();
        return materialRepository.findAllById(present).stream()
            .filter(this::visibleReference)
            .collect(Collectors.toMap(
                com.enterprise.mm.entity.Material::getId,
                material -> new ReferenceLabel(
                    firstNonBlank(material.getCode(), material.getArticle()),
                    firstNonBlank(LocalizedText.forCurrentLocale(material.getName()), material.getCode(), material.getArticle())
                )
            ));
    }

    private Map<UUID, ReferenceLabel> warehouseLabels(Set<UUID> ids) {
        List<UUID> present = ids.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) return Map.of();
        return warehouseRepository.findAllById(present).stream()
            .filter(this::visibleReference)
            .collect(Collectors.toMap(
                com.enterprise.mm.entity.Warehouse::getId,
                warehouse -> new ReferenceLabel(
                    firstNonBlank(warehouse.getCode()),
                    firstNonBlank(LocalizedText.forCurrentLocale(warehouse.getName()), warehouse.getCode())
                )
            ));
    }

    private Map<UUID, ReferenceLabel> supplierLabels(Set<UUID> ids) {
        List<UUID> present = ids.stream().filter(Objects::nonNull).toList();
        if (present.isEmpty()) return Map.of();
        return supplierRepository.findAllById(present).stream()
            .filter(this::visibleReference)
            .collect(Collectors.toMap(
                Supplier::getId,
                supplier -> new ReferenceLabel(
                    firstNonBlank(supplier.getCode()),
                    firstNonBlank(
                        LocalizedText.forCurrentLocale(supplier.getName()), supplier.getShortName(), supplier.getLegalName(), supplier.getCode())
                )
            ));
    }

    private boolean visibleReference(com.enterprise.mm.common.BusinessEntity entity) {
        return !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && reportCompanyId().equals(entity.getCompanyId());
    }

    private ReferenceLabel materialLabel(UUID materialId) {
        if (materialId == null) {
            return ReferenceLabel.empty();
        }
        return materialRepository.findByIdAndIsDeletedFalse(materialId)
            .filter(this::visibleReference)
            .map(material -> new ReferenceLabel(
                firstNonBlank(material.getCode(), material.getArticle()),
                firstNonBlank(LocalizedText.forCurrentLocale(material.getName()), material.getCode(), material.getArticle())
            ))
            .orElseGet(ReferenceLabel::empty);
    }

    private ReferenceLabel warehouseLabel(UUID warehouseId) {
        if (warehouseId == null) {
            return ReferenceLabel.empty();
        }
        return warehouseRepository.findByIdAndIsDeletedFalse(warehouseId)
            .filter(this::visibleReference)
            .map(warehouse -> new ReferenceLabel(
                firstNonBlank(warehouse.getCode()),
                firstNonBlank(LocalizedText.forCurrentLocale(warehouse.getName()), warehouse.getCode())
            ))
            .orElseGet(ReferenceLabel::empty);
    }

    private ReferenceLabel supplierLabel(UUID supplierId) {
        if (supplierId == null) {
            return ReferenceLabel.empty();
        }
        return supplierRepository.findByIdAndIsDeletedFalse(supplierId)
            .filter(this::visibleReference)
            .map(supplier -> new ReferenceLabel(
                firstNonBlank(supplier.getCode()),
                firstNonBlank(LocalizedText.forCurrentLocale(supplier.getName()), supplier.getShortName(), supplier.getLegalName(), supplier.getCode())
            ))
            .orElseGet(ReferenceLabel::empty);
    }

    private ReferenceLabel materialGroupLabel(UUID materialGroupId) {
        if (materialGroupId == null) {
            return ReferenceLabel.empty();
        }
        return materialGroupRepository.findByIdAndIsDeletedFalse(materialGroupId)
            .filter(this::visibleReference)
            .map(group -> new ReferenceLabel(
                firstNonBlank(group.getCode()),
                firstNonBlank(LocalizedText.forCurrentLocale(group.getName()), group.getCode())
            ))
            .orElseGet(ReferenceLabel::empty);
    }

    private ReferenceLabel stockBatchLabel(UUID stockBatchId) {
        if (stockBatchId == null) {
            return ReferenceLabel.empty();
        }
        return stockBatchRepository.findByIdAndIsDeletedFalse(stockBatchId)
            .filter(this::visibleReference)
            .map(batch -> new ReferenceLabel(
                firstNonBlank(batch.getCode(), batch.getBatchNumber()),
                firstNonBlank(LocalizedText.forCurrentLocale(batch.getName()), batch.getBatchNumber(), batch.getCode())
            ))
            .orElseGet(ReferenceLabel::empty);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private long activeMaterials() {
        String tenantId = TenantContext.currentTenant();
        return materialRepository.countVisibleCatalogMaterials(
            tenantId,
            reportCompanyId(),
            MaterialStatus.ACTIVE.name(),
            "GOODS",
            true
        );
    }

    private long activeSuppliers() {
        String tenantId = TenantContext.currentTenant();
        return supplierRepository.countByTenantIdAndCompanyIdAndIsDeletedFalse(
            tenantId, reportCompanyId());
    }

    private UUID reportCompanyId() {
        return CompanyContext.currentCompany();
    }

    private BaseCurrencyResolution reportBaseCurrency() {
        return inventoryAccountingSettingsRepository
            .findByTenantIdAndCompanyIdAndIsDeletedFalse(
                TenantContext.currentTenant(), reportCompanyId())
            .map(InventoryAccountingSettings::getBaseCurrencyCode)
            .filter(value -> value != null && value.trim().matches("(?i)[A-Z]{3}"))
            .map(value -> new BaseCurrencyResolution(
                value.trim().toUpperCase(Locale.ROOT), false, null))
            .orElseGet(() -> new BaseCurrencyResolution(
                "UZS",
                true,
                "Inventory accounting settings or a valid base currency are unavailable for the current company; "
                    + "stock values use the documented UZS fallback."
            ));
    }

    private static List<String> nonClosedNonMovingStatuses() {
        return java.util.Arrays.stream(NonMovingStockStatus.values())
            .filter(status -> status != NonMovingStockStatus.CLOSED)
            .map(Enum::name)
            .toList();
    }

    private void writeKeyValueSheet(
        XSSFWorkbook workbook,
        String name,
        DashboardReportDto dashboard,
        String baseCurrencyCode
    ) {
        var sheet = workbook.createSheet(name);
        String[][] rows = {
                {"Materials", String.valueOf(dashboard.materials())},
                {"Suppliers", String.valueOf(dashboard.suppliers())},
                {"Open PR", String.valueOf(dashboard.openPurchaseRequests())},
                {"Open PO", String.valueOf(dashboard.openPurchaseOrders())},
                {"Blocked suppliers", String.valueOf(dashboard.blockedSuppliers())},
                {"Non-moving", String.valueOf(dashboard.nonMovingPositions())},
                {"Overdue", String.valueOf(dashboard.overdueDeliveries())},
                {"Stock value (" + baseCurrencyCode + ")", dashboard.totalStockValue().toPlainString()},
                {"Savings", decimalText(dashboard.procurementSavings())}
        };
        for (int i = 0; i < rows.length; i++) {
            Row row = sheet.createRow(i);
            row.createCell(0).setCellValue(rows[i][0]);
            row.createCell(1).setCellValue(rows[i][1]);
        }
        autosize(sheet, 2);
    }

    private void writePlanFactSheet(XSSFWorkbook workbook, List<PlanFactCurrencyBucketDto> reports) {
        var sheet = workbook.createSheet("Plan fact");
        Row header = sheet.createRow(0);
        List<String> columns = List.of("Currency", "Planned", "Ordered", "Received", "Variance", "Received health");
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(columns.get(i));
        }
        int rowIndex = 1;
        for (PlanFactCurrencyBucketDto report : reports) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(text(report.currencyCode()));
            setDecimalCell(row, 1, report.plannedAmount());
            setDecimalCell(row, 2, report.orderedAmount());
            setDecimalCell(row, 3, report.receivedAmount());
            setDecimalCell(row, 4, report.varianceAmount());
            row.createCell(5).setCellValue(report.receivedHealth().name());
        }
        autosize(sheet, columns.size());
    }

    private void writeAbcSheet(XSSFWorkbook workbook, List<AbcReportDto> reports) {
        var sheet = workbook.createSheet("ABC");
        Row header = sheet.createRow(0);
        List<String> columns = List.of("Material ID", "Material code", "Material name", "Total value", "Cumulative share", "Category");
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(columns.get(i));
        }
        int rowIndex = 1;
        for (AbcReportDto rowDto : reports) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(rowDto.materialId().toString());
            row.createCell(1).setCellValue(text(rowDto.materialCode()));
            row.createCell(2).setCellValue(text(rowDto.materialName()));
            row.createCell(3).setCellValue(rowDto.totalValue().doubleValue());
            row.createCell(4).setCellValue(rowDto.cumulativeShare().doubleValue());
            row.createCell(5).setCellValue(rowDto.category());
        }
        autosize(sheet, columns.size());
    }

    private void writeXyzSheet(XSSFWorkbook workbook, List<XyzReportDto> reports) {
        var sheet = workbook.createSheet("XYZ");
        Row header = sheet.createRow(0);
        List<String> columns = List.of("Material ID", "Material code", "Material name", "Average monthly issue", "Variation coefficient", "Category");
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(columns.get(i));
        }
        int rowIndex = 1;
        for (XyzReportDto rowDto : reports) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(rowDto.materialId().toString());
            row.createCell(1).setCellValue(text(rowDto.materialCode()));
            row.createCell(2).setCellValue(text(rowDto.materialName()));
            row.createCell(3).setCellValue(rowDto.averageMonthlyIssue().doubleValue());
            row.createCell(4).setCellValue(rowDto.variationCoefficient().doubleValue());
            row.createCell(5).setCellValue(rowDto.category());
        }
        autosize(sheet, columns.size());
    }

    private void writeMetadataSheet(XSSFWorkbook workbook, ReportMetadataDto metadata) {
        var sheet = workbook.createSheet("Report context");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Generated at", metadata.generatedAt().toString()));
        rows.add(List.of("Selected period from", metadata.periodFrom().toString()));
        rows.add(List.of("Selected period to", metadata.periodTo().toString()));
        rows.add(List.of("Selected procurement currency (no conversion)", text(metadata.displayCurrency())));
        rows.add(List.of("Stock valuation base currency", text(metadata.baseCurrencyCode())));
        rows.add(List.of("Company scope", text(metadata.scopeLabel())));
        rows.add(List.of("Data health", metadata.health().name()));
        metadata.sourceHealthMessages().forEach(message -> rows.add(List.of("Source health", message)));
        metadata.metricScopes().forEach((metric, scope) -> rows.add(List.of("Scope: " + metric, scope)));
        for (int index = 0; index < rows.size(); index++) {
            Row row = sheet.createRow(index);
            row.createCell(0).setCellValue(rows.get(index).get(0));
            row.createCell(1).setCellValue(rows.get(index).get(1));
        }
        autosize(sheet, 2);
    }

    private void writeExportContextSheet(XSSFWorkbook workbook, ReportFilter filter, String dataScope) {
        var sheet = workbook.createSheet("Report context");
        String[][] rows = {
            {"Generated at", Instant.now().toString()},
            {"Selected period from", filter.from().toString()},
            {"Selected period to", filter.to().toString()},
            {"Currency (no conversion)", text(filter.displayCurrency())},
            {"Data scope", dataScope}
        };
        for (int index = 0; index < rows.length; index++) {
            Row row = sheet.createRow(index);
            row.createCell(0).setCellValue(rows[index][0]);
            row.createCell(1).setCellValue(rows[index][1]);
        }
        autosize(sheet, 2);
    }

    private void writeStockExportContextSheet(
        XSSFWorkbook workbook,
        ReportFilter filter,
        BaseCurrencyResolution baseCurrency,
        String dataScope
    ) {
        var sheet = workbook.createSheet("Report context");
        String[][] rows = {
            {"Generated at", Instant.now().toString()},
            {"Selected period from", filter.from().toString()},
            {"Selected period to", filter.to().toString()},
            {"Selected procurement currency (not applied to stock values)", text(filter.displayCurrency())},
            {"Stock valuation base currency", text(baseCurrency.code())},
            {"Stock valuation currency source", baseCurrency.degraded()
                ? baseCurrency.message() : "Inventory accounting settings"},
            {"Data scope", dataScope}
        };
        for (int index = 0; index < rows.length; index++) {
            Row row = sheet.createRow(index);
            row.createCell(0).setCellValue(rows[index][0]);
            row.createCell(1).setCellValue(rows[index][1]);
        }
        autosize(sheet, 2);
    }

    private void autosize(org.apache.poi.ss.usermodel.Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void setDecimalCell(Row row, int column, BigDecimal value) {
        if (value == null) {
            row.createCell(column).setCellValue("Unavailable: select one currency");
        } else {
            row.createCell(column).setCellValue(value.doubleValue());
        }
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "Unavailable: select one currency" : value.toPlainString();
    }

    private static BigDecimal sqrt(BigDecimal value) {
        return BigDecimal.valueOf(Math.sqrt(value.doubleValue())).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record WarehouseMaterialKey(UUID warehouseId, UUID materialId) {
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
