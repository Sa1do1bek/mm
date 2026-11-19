package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetVerification;
import com.enterprise.mm.entity.AssetVerificationLine;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetVerificationDiscrepancyType;
import com.enterprise.mm.enums.AssetVerificationResolutionStatus;
import com.enterprise.mm.enums.AssetVerificationStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetVerificationCreateRequest;
import com.enterprise.mm.payload.AssetVerificationScanRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetVerificationLineRepository;
import com.enterprise.mm.repository.AssetVerificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AssetVerificationOperationalFlowTest {
    private final AssetVerificationRepository verificationRepository = org.mockito.Mockito.mock(AssetVerificationRepository.class);
    private final AssetVerificationLineRepository lineRepository = org.mockito.Mockito.mock(AssetVerificationLineRepository.class);
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final AssetIntegrationEventPublisher assetIntegrationEventPublisher = org.mockito.Mockito.mock(AssetIntegrationEventPublisher.class);
    private final List<AssetVerification> verifications = new ArrayList<>();
    private final List<AssetVerificationLine> lines = new ArrayList<>();
    private final List<FixedAsset> assets = new ArrayList<>();

    private AssetVerificationService service;
    private UUID companyId;
    private FixedAsset asset;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-verification-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        asset = warehouseAsset();
        assets.add(asset);
        service = new AssetVerificationService(
            verificationRepository,
            lineRepository,
            fixedAssetRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            idempotencyService
        );
        ReflectionTestUtils.setField(service, "assetIntegrationEventPublisher", assetIntegrationEventPublisher);

        lenient().when(verificationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> verifications.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
        lenient().when(verificationRepository.save(any(AssetVerification.class)))
            .thenAnswer(invocation -> save(verifications, invocation.getArgument(0)));
        lenient().when(lineRepository.findByTenantIdAndCompanyIdAndAssetVerificationIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> lines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetVerificationId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(lineRepository.save(any(AssetVerificationLine.class)))
            .thenAnswer(invocation -> save(lines, invocation.getArgument(0)));
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, asset.getWarehouseId()
        )).thenReturn(assets);
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, asset.getId()
        )).thenReturn(Optional.of(asset));
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, asset.getInventoryNumber()
        )).thenReturn(Optional.of(asset));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createSnapshotsExpectedWarehouseCustody() {
        var response = service.create(new AssetVerificationCreateRequest(asset.getWarehouseId(), null, "WAREHOUSE"));

        assertThat(response.getStatus()).isEqualTo(AssetVerificationStatus.IN_PROGRESS.name());
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getFixedAssetId()).isEqualTo(asset.getId());
            assertThat(line.getExpectedCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
            assertThat(line.getExpectedWarehouseId()).isEqualTo(asset.getWarehouseId());
            assertThat(line.getDiscrepancyType()).isNull();
        });
    }

    @Test
    void scanRecordsMismatchButDoesNotMutateAssetCustody() {
        AssetVerification verification = createWarehouseVerification();
        UUID observedDepartmentId = UUID.randomUUID();

        service.scan(verification.getId(), new AssetVerificationScanRequest(
            verification.getVersion(), asset.getId(), null, "DEPARTMENT", null, observedDepartmentId, null
        ));

        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
        assertThat(asset.getWarehouseId()).isNotNull();
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getObservedCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
            assertThat(line.getObservedDepartmentId()).isEqualTo(observedDepartmentId);
            assertThat(line.getDiscrepancyType()).isEqualTo(AssetVerificationDiscrepancyType.LOCATION_MISMATCH);
            assertThat(line.getResolutionStatus()).isEqualTo(AssetVerificationResolutionStatus.OPEN);
        });
        verify(assetIntegrationEventPublisher).publish(
            eq(asset),
            eq("ASSET_VERIFICATION_DISCREPANCY_RECORDED"),
            eq(FixedAssetLifecycleStatus.IN_WAREHOUSE),
            eq(FixedAssetCustodyKind.WAREHOUSE),
            eq("ASSET_VERIFICATION"),
            eq(verification.getId()),
            any(),
            eq("system"),
            eq(null)
        );
    }

    @Test
    void completeMarksUnscannedExpectedLinesAsMissingWithoutMovingAssets() {
        AssetVerification verification = createWarehouseVerification();

        service.complete(verification.getId(), new com.enterprise.mm.payload.AssetVerificationCompleteRequest(
            verification.getVersion(), "count done"
        ));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
        assertThat(verification.getVerificationStatus()).isEqualTo(AssetVerificationStatus.COMPLETED);
        assertThat(lines).singleElement().satisfies(line ->
            assertThat(line.getDiscrepancyType()).isEqualTo(AssetVerificationDiscrepancyType.MISSING_SCAN));
    }

    @Test
    void closeOnlyClosesVerificationDocument() {
        AssetVerification verification = createWarehouseVerification();
        service.complete(verification.getId(), new com.enterprise.mm.payload.AssetVerificationCompleteRequest(
            verification.getVersion(), "count done"
        ));

        service.close(verification.getId(), new VersionedAssetCommand(verification.getVersion(), "closed"));

        assertThat(verification.getVerificationStatus()).isEqualTo(AssetVerificationStatus.CLOSED);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
    }

    private AssetVerification createWarehouseVerification() {
        service.create(new AssetVerificationCreateRequest(asset.getWarehouseId(), null, "WAREHOUSE"));
        return verifications.getFirst();
    }

    private FixedAsset warehouseAsset() {
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        asset.setCode("FA-TEST");
        asset.setName("Fixed asset");
        asset.setStatus("ACTIVE");
        asset.setVersion(0L);
        asset.setMaterialId(UUID.randomUUID());
        asset.setInventoryNumber("FA-0001");
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        asset.setCustodyKind(FixedAssetCustodyKind.WAREHOUSE);
        asset.setWarehouseId(UUID.randomUUID());
        return asset;
    }

    private <T extends BusinessEntity> T save(List<T> rows, T row) {
        if (row.getId() == null) {
            row.setId(UUID.randomUUID());
        }
        if (row.getTenantId() == null) {
            row.setTenantId(TenantContext.currentTenant());
        }
        if (row.getCompanyId() == null) {
            row.setCompanyId(companyId);
        }
        if (row.getVersion() == null) {
            row.setVersion(0L);
        }
        rows.removeIf(existing -> existing.getId().equals(row.getId()));
        rows.add(row);
        return row;
    }
}
