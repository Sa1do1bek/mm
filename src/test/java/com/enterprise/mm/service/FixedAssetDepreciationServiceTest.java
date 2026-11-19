package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetDepreciationEntry;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetCategory;
import com.enterprise.mm.enums.DepreciationMethod;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetDepreciationRunRequest;
import com.enterprise.mm.payload.AssetDepreciationRunResponse;
import com.enterprise.mm.payload.AssetDepreciationScheduleResponse;
import com.enterprise.mm.payload.FixedAssetAnalyticsSummaryResponse;
import com.enterprise.mm.payload.FixedAssetDepreciationConfigRequest;
import com.enterprise.mm.payload.FixedAssetResponse;
import com.enterprise.mm.payload.FixedAssetRevaluationRequest;
import com.enterprise.mm.repository.AssetDepreciationEntryRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class FixedAssetDepreciationServiceTest {

    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetDepreciationEntryRepository entryRepository = org.mockito.Mockito.mock(AssetDepreciationEntryRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final AssetFoundationMapper mapper = new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class));

    private FixedAssetDepreciationService service;
    private UUID companyId;
    private UUID assetId;
    private FixedAsset asset;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-test-tenant");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);

        assetId = UUID.randomUUID();
        asset = new FixedAsset();
        asset.setId(assetId);
        asset.setTenantId("asset-test-tenant");
        asset.setCompanyId(companyId);
        asset.setName("Generator Caterpillar 500kW");
        asset.setAssetCode("GEN-001");
        asset.setInventoryNumber("FA-000001");
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setCategory(AssetCategory.MACHINERY_EQUIPMENT);
        asset.setDepreciationMethod(DepreciationMethod.STRAIGHT_LINE);
        asset.setAcquisitionValue(new BigDecimal("120000.0000"));
        asset.setSalvageValue(new BigDecimal("0.0000"));
        asset.setAccumulatedDepreciation(BigDecimal.ZERO);
        asset.setCurrentBookValue(new BigDecimal("120000.0000"));
        asset.setUsefulLifeMonths(60);
        asset.setFirstCommissionedOn(LocalDate.of(2026, 1, 1));
        asset.setDepreciationStartDate(LocalDate.of(2026, 1, 1));
        asset.setVersion(0L);

        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            "asset-test-tenant", companyId, assetId
        )).thenReturn(Optional.of(asset));

        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entryRepository.save(any(AssetDepreciationEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new FixedAssetDepreciationService(
            fixedAssetRepository,
            entryRepository,
            eventRepository,
            mapper,
            idempotencyService
        );
    }

    @AfterEach
    void tearDown() {
        CompanyContext.clear();
        TenantContext.clear();
    }

    @Test
    void configureDepreciation_success() {
        FixedAssetDepreciationConfigRequest request = new FixedAssetDepreciationConfigRequest(
            0L,
            AssetCategory.VEHICLE,
            DepreciationMethod.STRAIGHT_LINE,
            36,
            new BigDecimal("5000.00"),
            LocalDate.of(2026, 1, 1),
            "Updated useful life and vehicle category"
        );

        FixedAssetResponse response = service.configureDepreciation(assetId, request, null);

        assertThat(response).isNotNull();
        assertThat(response.getCategory()).isEqualTo("VEHICLE");
        assertThat(response.getUsefulLifeMonths()).isEqualTo(36);
        assertThat(response.getSalvageValue()).isEqualByComparingTo("5000.00");
    }

    @Test
    void revalue_success() {
        FixedAssetRevaluationRequest request = new FixedAssetRevaluationRequest(
            0L,
            new BigDecimal("150000.00"),
            72,
            BigDecimal.ZERO,
            LocalDate.of(2026, 6, 1),
            "Market valuation increase",
            List.of()
        );

        FixedAssetResponse response = service.revalue(assetId, request, null);

        assertThat(response).isNotNull();
        assertThat(response.getAcquisitionValue()).isEqualByComparingTo("150000.00");
        assertThat(response.getCurrentBookValue()).isEqualByComparingTo("150000.00");
        assertThat(response.getUsefulLifeMonths()).isEqualTo(72);
    }

    @Test
    void getDepreciationSchedule_generatesExpectedStraightLinePeriods() {
        AssetDepreciationScheduleResponse schedule = service.getDepreciationSchedule(assetId);

        assertThat(schedule).isNotNull();
        assertThat(schedule.getUsefulLifeMonths()).isEqualTo(60);
        assertThat(schedule.getMonthlyDepreciationAmount()).isEqualByComparingTo("2000.00");
        assertThat(schedule.getScheduleEntries()).hasSize(60);

        // First month check
        var first = schedule.getScheduleEntries().get(0);
        assertThat(first.getPeriodYear()).isEqualTo(2026);
        assertThat(first.getPeriodMonth()).isEqualTo(1);
        assertThat(first.getDepreciationAmount()).isEqualByComparingTo("2000.00");
        assertThat(first.getClosingBookValue()).isEqualByComparingTo("118000.00");
    }

    @Test
    void executeDepreciationRun_postsMonthlyEntries() {
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            "asset-test-tenant", companyId, Pageable.unpaged()
        )).thenReturn(new PageImpl<>(List.of(asset)));

        AssetDepreciationRunRequest request = new AssetDepreciationRunRequest(2026, 9, null, "Monthly run");
        AssetDepreciationRunResponse response = service.executeDepreciationRun(request, null);

        assertThat(response).isNotNull();
        assertThat(response.getProcessedAssetCount()).isEqualTo(1);
        assertThat(response.getTotalDepreciationAmount()).isEqualByComparingTo("2000.00");
        assertThat(response.getEntries()).hasSize(1);
        assertThat(asset.getAccumulatedDepreciation()).isEqualByComparingTo("2000.00");
        assertThat(asset.getCurrentBookValue()).isEqualByComparingTo("118000.00");
    }

    @Test
    void getAnalyticsSummary_aggregatesTotalsAndBreakdowns() {
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
            "asset-test-tenant", companyId, Pageable.unpaged()
        )).thenReturn(new PageImpl<>(List.of(asset)));

        FixedAssetAnalyticsSummaryResponse summary = service.getAnalyticsSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.getTotalCount()).isEqualTo(1);
        assertThat(summary.getInUseCount()).isEqualTo(1);
        assertThat(summary.getTotalAcquisitionValue()).isEqualByComparingTo("120000.00");
        assertThat(summary.getTotalNetBookValue()).isEqualByComparingTo("120000.00");
        assertThat(summary.getCategoryMetrics()).containsKey("MACHINERY_EQUIPMENT");
    }
}
