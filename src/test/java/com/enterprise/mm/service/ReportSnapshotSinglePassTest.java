package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.payload.ReportFilter;
import com.enterprise.mm.payload.ReportDtos.QueueContractDto;
import com.enterprise.mm.payload.ReportDtos.ReportsSnapshotDto;
import com.enterprise.mm.enums.NonMovingStockStatus;
import com.enterprise.mm.entity.InventoryAccountingSettings;
import com.enterprise.mm.payload.ReportDtos.ReportMetricHealth;
import com.enterprise.mm.repository.InventoryCostLayerRepository;
import com.enterprise.mm.repository.InventoryAccountingSettingsRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockCostMovementRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ReportSnapshotSinglePassTest {

    @AfterEach
    void clearRequestContext() {
        SecurityContextHolder.clearContext();
        com.enterprise.mm.common.TenantContext.clear();
        com.enterprise.mm.common.CompanyContext.clear();
    }

    @Test
    void snapshotUsesBoundedAggregatesAndDoesNotLoadUnusedCollections() {
        RepositoryProbe probe = new RepositoryProbe();
        ReportService service = service(probe);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "global-admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        ReportsSnapshotDto snapshot;
        try {
            snapshot = service.snapshot(new ReportFilter(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
                null
            ));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(probe.calls(
            StockBalanceRepository.class,
            "findByTenantIdAndCompanyIdAndIsDeletedFalse"
        )).isZero();
        assertThat(probe.calls(
            StockMovementRepository.class,
            "findByTenantIdAndCompanyIdAndStatusInAndMovementTypeInAndPostedAtAfterAndIsDeletedFalse"
        )).isZero();
        assertThat(probe.calls(
            StockMovementRepository.class,
            "findPostedPurchaseOrderReceipts"
        )).isEqualTo(1);
        assertThat(probe.calls(
            PurchaseRequestRepository.class,
            "findByTenantIdAndCompanyIdAndIsDeletedFalse"
        )).isZero();
        assertThat(probe.calls(
            PurchaseOrderRepository.class,
            "findByTenantIdAndCompanyIdAndIsDeletedFalse"
        )).isZero();
        assertThat(probe.calls(
            PurchaseRequestRepository.class,
            "findReportWindow"
        )).isEqualTo(1);
        assertThat(probe.calls(
            PurchaseOrderRepository.class,
            "findReportWindow"
        )).isEqualTo(1);
        assertThat(probe.pageSize(PurchaseRequestRepository.class, "findReportWindow")).isEqualTo(50_001);
        assertThat(probe.pageSize(PurchaseOrderRepository.class, "findReportWindow")).isEqualTo(50_001);
        assertThat(probe.pageSize(StockMovementRepository.class, "findPostedPurchaseOrderReceipts")).isEqualTo(50_001);
        assertThat(probe.calls(
            StockBalanceRepository.class,
            "findReportMaterialValues"
        )).isEqualTo(1);
        assertThat(probe.calls(
            StockMovementRepository.class,
            "sumMonthlyIssueByMaterialIds"
        )).isZero();
        assertThat(probe.calls(
            MaterialRepository.class,
            "countVisibleCatalogMaterials"
        )).isEqualTo(1);
        assertThat(probe.calls(
            MaterialRepository.class,
            "countByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse"
        )).isZero();
        assertThat(snapshot.metadata().metricScopes())
            .containsKeys("planFact", "abc", "supplierRating", "overdueDeliveriesList");
        assertThat(queue(snapshot, "materials").statuses()).containsExactly("ACTIVE");
        assertThat(queue(snapshot, "materials").drilldownUrl())
            .isEqualTo("/app/materials?status=ACTIVE&companyScope=CURRENT_COMPANY");
        assertThat(queue(snapshot, "suppliers").statuses()).isEmpty();
        assertThat(queue(snapshot, "suppliers").drilldownUrl())
            .isEqualTo("/app/suppliers?queue=ALL&companyScope=CURRENT_COMPANY");
        List<String> nonClosedStatuses = Stream.of(NonMovingStockStatus.values())
            .filter(status -> status != NonMovingStockStatus.CLOSED)
            .map(Enum::name)
            .toList();
        assertThat(queue(snapshot, "non-moving-positions").statuses())
            .containsExactlyElementsOf(nonClosedStatuses);
        assertThat(queue(snapshot, "non-moving-positions").drilldownUrl()).isEqualTo(
            "/app/non-moving-stock-dashboard?mode=detected&status="
                + String.join(",", nonClosedStatuses)
                + "&companyScope=CURRENT_COMPANY");
        assertThat(queue(snapshot, "blocked-suppliers").drilldownUrl())
            .isEqualTo("/app/suppliers?queue=BLACKLIST_UNBLOCK&companyScope=CURRENT_COMPANY");
        assertThat(queue(snapshot, "overdue-deliveries").drilldownUrl())
            .contains("tab=registry", "status=SENT,PARTIALLY_RECEIVED", "expectedDeliveryDateTo=",
                "companyScope=CURRENT_COMPANY");
        assertThat(snapshot.queueContracts()).allSatisfy(queue ->
            assertThat(queue.drilldownUrl()).contains("companyScope=CURRENT_COMPANY"));
        assertThat(snapshot.dashboard().blockedSuppliers()).isEqualTo(7);
        assertThat(snapshot.metadata().companyId()).isEqualTo(com.enterprise.mm.common.CompanyContext.DEFAULT_COMPANY_ID);
        assertThat(snapshot.metadata().scopeLabel()).isEqualTo("CURRENT_COMPANY");
    }

    private QueueContractDto queue(ReportsSnapshotDto snapshot, String key) {
        List<QueueContractDto> matches = snapshot.queueContracts().stream()
            .filter(queue -> key.equals(queue.queueKey()))
            .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    @Test
    void snapshotWireContractDoesNotExposeUnusedRawCollections() {
        assertThat(Arrays.stream(ReportsSnapshotDto.class.getRecordComponents())
            .map(component -> component.getName()))
            .doesNotContain("stock", "movements", "turnover", "abcXyzMatrix");
    }

    @Test
    void selectedProcurementCurrencyNeverRelabelsBaseCurrencyStockValues() throws Exception {
        RepositoryProbe probe = new RepositoryProbe();
        ReportService service = service(probe);
        ReportFilter filter = new ReportFilter(
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 6, 30),
            "usd"
        );

        ReportsSnapshotDto snapshot = service.snapshot(filter);

        assertThat(snapshot.metadata().displayCurrency()).isEqualTo("USD");
        assertThat(snapshot.metadata().baseCurrencyCode()).isEqualTo("UZS");

        byte[] bytes = service.analyticsExcel(filter);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var context = workbook.getSheet("Report context");
            assertThat(context.getRow(1).getCell(1).getStringCellValue()).isEqualTo("2026-02-01");
            assertThat(context.getRow(2).getCell(1).getStringCellValue()).isEqualTo("2026-06-30");
            assertThat(context.getRow(3).getCell(0).getStringCellValue())
                .isEqualTo("Selected procurement currency (no conversion)");
            assertThat(context.getRow(3).getCell(1).getStringCellValue()).isEqualTo("USD");
            assertThat(context.getRow(4).getCell(0).getStringCellValue())
                .isEqualTo("Stock valuation base currency");
            assertThat(context.getRow(4).getCell(1).getStringCellValue()).isEqualTo("UZS");
            assertThat(workbook.getSheet("Dashboard").getRow(7).getCell(0).getStringCellValue())
                .isEqualTo("Stock value (UZS)");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(
            new ByteArrayInputStream(service.stockBalancesExcel(filter)))) {
            var context = workbook.getSheet("Report context");
            assertThat(context.getRow(3).getCell(0).getStringCellValue())
                .isEqualTo("Selected procurement currency (not applied to stock values)");
            assertThat(context.getRow(3).getCell(1).getStringCellValue()).isEqualTo("USD");
            assertThat(context.getRow(4).getCell(0).getStringCellValue())
                .isEqualTo("Stock valuation base currency");
            assertThat(context.getRow(4).getCell(1).getStringCellValue()).isEqualTo("UZS");
            assertThat(workbook.getSheet("Stock balances").getRow(0).getCell(9).getStringCellValue())
                .isEqualTo("Total value (UZS)");
        }

        try (PdfReader reader = new PdfReader(service.dashboardPdf(filter))) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains(
                "Selected procurement currency (no conversion): USD",
                "Stock valuation base currency: UZS",
                "Total stock value (UZS):",
                "Procurement savings (USD):"
            );
        }
    }

    @Test
    void missingAccountingSettingsPublishesAnExplicitDegradedUzsFallback() {
        ReportsSnapshotDto snapshot = service(new RepositoryProbe()).snapshot(
            ReportFilter.defaultPeriod());

        assertThat(snapshot.metadata().baseCurrencyCode()).isEqualTo("UZS");
        assertThat(snapshot.metadata().health()).isEqualTo(ReportMetricHealth.PARTIAL);
        assertThat(snapshot.metadata().sourceHealthMessages())
            .anySatisfy(message -> assertThat(message)
                .contains("Inventory accounting settings", "UZS fallback"));
    }

    @Test
    void configuredBaseCurrencyDoesNotDegradeAnOtherwiseHealthySnapshot() {
        RepositoryProbe probe = new RepositoryProbe().withBaseCurrency("uzs");

        ReportsSnapshotDto snapshot = service(probe).snapshot(ReportFilter.defaultPeriod());

        assertThat(snapshot.metadata().baseCurrencyCode()).isEqualTo("UZS");
        assertThat(snapshot.metadata().health()).isEqualTo(ReportMetricHealth.READY);
        assertThat(snapshot.metadata().sourceHealthMessages())
            .noneSatisfy(message -> assertThat(message).contains("UZS fallback"));
    }

    private ReportService service(RepositoryProbe probe) {
        return new ReportService(
            probe.repository(StockBalanceRepository.class),
            probe.repository(SupplierRepository.class),
            probe.repository(MaterialRepository.class),
            probe.repository(WarehouseRepository.class),
            probe.repository(MaterialGroupRepository.class),
            probe.repository(StockBatchRepository.class),
            probe.repository(PurchaseRequestRepository.class),
            probe.repository(PurchaseOrderRepository.class),
            probe.repository(StockMovementRepository.class),
            probe.repository(InventoryCostLayerRepository.class),
            probe.repository(StockCostMovementRepository.class),
            probe.repository(NonMovingStockRepository.class),
            probe.repository(NonMovingStockCaseLineRepository.class),
            probe.repository(ReferenceMappingRepository.class),
            probe.repository(InventoryAccountingSettingsRepository.class),
            () -> 7L
        );
    }

    private static final class RepositoryProbe {
        private final Map<String, Integer> calls = new HashMap<>();
        private final Map<String, Integer> pageSizes = new HashMap<>();
        private InventoryAccountingSettings accountingSettings;

        RepositoryProbe withBaseCurrency(String baseCurrencyCode) {
            accountingSettings = new InventoryAccountingSettings();
            accountingSettings.setBaseCurrencyCode(baseCurrencyCode);
            return this;
        }

        @SuppressWarnings("unchecked")
        <T> T repository(Class<T> type) {
            return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "Probe<" + type.getSimpleName() + ">";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    calls.merge(key(type, method.getName()), 1, Integer::sum);
                    if (args != null) {
                        for (Object arg : args) {
                            if (arg instanceof Pageable pageable && pageable.isPaged()) {
                                pageSizes.put(key(type, method.getName()), pageable.getPageSize());
                            }
                        }
                    }
                    Class<?> returnType = method.getReturnType();
                    if (List.class.isAssignableFrom(returnType)) {
                        return List.of();
                    }
                    if (Optional.class.isAssignableFrom(returnType)) {
                        if (type == InventoryAccountingSettingsRepository.class
                            && "findByTenantIdAndCompanyIdAndIsDeletedFalse".equals(method.getName())) {
                            return Optional.ofNullable(accountingSettings);
                        }
                        return Optional.empty();
                    }
                    if (Page.class.isAssignableFrom(returnType)) {
                        return Page.empty();
                    }
                    if (BigDecimal.class.isAssignableFrom(returnType)) {
                        return BigDecimal.ZERO;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == short.class) {
                        return (short) 0;
                    }
                    if (returnType == boolean.class) {
                        return false;
                    }
                    return null;
                }
            );
        }

        int calls(Class<?> type, String method) {
            return calls.getOrDefault(key(type, method), 0);
        }

        int pageSize(Class<?> type, String method) {
            return pageSizes.getOrDefault(key(type, method), 0);
        }

        private String key(Class<?> type, String method) {
            return type.getName() + "#" + method;
        }
    }
}
