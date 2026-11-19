package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetInventoryNumberAllocateRequest;
import com.enterprise.mm.payload.AssetInventoryNumberAssignRequest;
import com.enterprise.mm.payload.AssetInventoryNumberCorrectionRequest;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.AssetNumberRegistryRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedAssetInventoryNumberServiceTest {
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final AssetNumberRegistryRepository registryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetManagementSettingsRepository settingsRepository = org.mockito.Mockito.mock(AssetManagementSettingsRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final EmployeeRepository employeeRepository = org.mockito.Mockito.mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final AttachmentRepository attachmentRepository = org.mockito.Mockito.mock(AttachmentRepository.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final List<AssetNumberRegistry> registryRows = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();

    private FixedAssetService service;
    private UUID companyId;
    private UUID assetId;
    private FixedAsset asset;
    private AssetManagementSettings settings;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-numbering-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        assetId = UUID.randomUUID();
        asset = asset(assetId);
        settings = settings();
        service = new FixedAssetService(
            fixedAssetRepository,
            eventRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            registryRepository,
            settingsRepository,
            equipmentRepository,
            employeeRepository,
            departmentRepository,
            attachmentRepository,
            idempotencyService,
            org.mockito.Mockito.mock(com.enterprise.mm.repository.AssetAcceptanceRepository.class),
            org.mockito.Mockito.mock(com.enterprise.mm.repository.AssetHandoverRepository.class),
            org.mockito.Mockito.mock(com.enterprise.mm.repository.AssetOpeningImportRepository.class),
            org.mockito.Mockito.mock(RoleDataScopeService.class)
        );

        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, assetId
        )).thenReturn(Optional.of(asset));
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(eventRepository.save(any(AssetEvent.class))).thenAnswer(invocation -> {
            AssetEvent event = assign(invocation.getArgument(0));
            events.add(event);
            return event;
        });
        lenient().when(registryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(
            any(), any(), any()
        )).thenAnswer(invocation -> registryRows.stream()
            .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
            .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
            .filter(row -> invocation.getArgument(2).equals(row.getNormalizedNumber()))
            .findFirst());
        lenient().when(registryRepository.save(any(AssetNumberRegistry.class))).thenAnswer(invocation -> {
            AssetNumberRegistry row = assign(invocation.getArgument(0));
            registryRows.removeIf(existing -> existing.getId().equals(row.getId()));
            registryRows.add(row);
            return row;
        });
        lenient().when(settingsRepository.findByTenantIdAndCompanyIdForUpdate(TenantContext.currentTenant(), companyId))
            .thenReturn(Optional.of(settings));
        lenient().when(settingsRepository.save(any(AssetManagementSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void manualAssignmentNormalizesNumberCreatesPermanentRegistryAndAppendsEvent() {
        var response = service.assignInventoryNumber(
            assetId,
            new AssetInventoryNumberAssignRequest(asset.getVersion(), " fa-0007 ", "tagged")
        );

        assertThat(response.getInventoryNumber()).isEqualTo("fa-0007");
        assertThat(registryRows).singleElement().satisfies(row -> {
            assertThat(row.getInventoryNumber()).isEqualTo("fa-0007");
            assertThat(row.getNormalizedNumber()).isEqualTo("FA-0007");
            assertThat(row.getFixedAssetId()).isEqualTo(assetId);
            assertThat(row.getRegistryStatus()).isEqualTo(AssetNumberRegistryStatus.ASSIGNED);
            assertThat(row.getSource()).isEqualTo(AssetNumberRegistrySource.MANUAL);
        });
        assertThat(events).singleElement().satisfies(event ->
            assertThat(event.getEventType()).isEqualTo("ASSET_INVENTORY_NUMBER_ASSIGNED"));
    }

    @Test
    void normalizedDuplicateNumberIsRejectedAcrossAssets() {
        UUID otherAssetId = UUID.randomUUID();
        registryRows.add(registry(" FA-0007 ", otherAssetId, AssetNumberRegistryStatus.ASSIGNED));

        assertThatThrownBy(() -> service.assignInventoryNumber(
            assetId,
            new AssetInventoryNumberAssignRequest(asset.getVersion(), "fa-0007", "tagged")
        ))
            .isInstanceOf(ConflictException.class)
            .satisfies(error -> assertThat(((ConflictException) error).getMessageKey())
                .isEqualTo("ASSET_INVENTORY_NUMBER_DUPLICATE"));
    }

    @Test
    void correctedNumberCannotBeReused() {
        registryRows.add(registry("FA-OLD", null, AssetNumberRegistryStatus.CORRECTED));

        assertThatThrownBy(() -> service.assignInventoryNumber(
            assetId,
            new AssetInventoryNumberAssignRequest(asset.getVersion(), "fa-old", "reuse")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_INVENTORY_NUMBER_RETIRED"));
    }

    @Test
    void automaticAllocationUsesLockedSettingsSequenceWithoutMaxPlusOne() {
        List<String> numbers = service.allocateInventoryNumbers(new AssetInventoryNumberAllocateRequest(3, "receipt"));

        assertThat(numbers).containsExactly("FA-000001", "FA-000002", "FA-000003");
        assertThat(settings.getInventoryNumberNextValue()).isEqualTo(4L);
        assertThat(registryRows).hasSize(3).allSatisfy(row -> {
            assertThat(row.getRegistryStatus()).isEqualTo(AssetNumberRegistryStatus.RESERVED);
            assertThat(row.getSource()).isEqualTo(AssetNumberRegistrySource.GENERATED);
            assertThat(row.getFixedAssetId()).isNull();
        });
    }

    @Test
    void correctionRequiresReasonAndPreservesOldRegistryAsCorrected() {
        service.assignInventoryNumber(
            assetId,
            new AssetInventoryNumberAssignRequest(asset.getVersion(), "FA-0009", "tagged")
        );
        assertThatThrownBy(() -> service.correctInventoryNumber(
            assetId,
            new AssetInventoryNumberCorrectionRequest(asset.getVersion(), "FA-0010", " ")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_EVIDENCE_REQUIRED"));

        service.correctInventoryNumber(
            assetId,
            new AssetInventoryNumberCorrectionRequest(asset.getVersion(), "FA-0010", "transposed digit")
        );

        assertThat(asset.getInventoryNumber()).isEqualTo("FA-0010");
        assertThat(registryRows).filteredOn(row -> "FA-0009".equals(row.getNormalizedNumber()))
            .singleElement()
            .satisfies(row -> assertThat(row.getRegistryStatus()).isEqualTo(AssetNumberRegistryStatus.CORRECTED));
        assertThat(registryRows).filteredOn(row -> "FA-0010".equals(row.getNormalizedNumber()))
            .singleElement()
            .satisfies(row -> assertThat(row.getSource()).isEqualTo(AssetNumberRegistrySource.CORRECTION));
        assertThat(events).extracting(AssetEvent::getEventType)
            .containsExactly("ASSET_INVENTORY_NUMBER_ASSIGNED", "ASSET_INVENTORY_NUMBER_CORRECTED");
    }

    private FixedAsset asset(UUID id) {
        FixedAsset asset = new FixedAsset();
        asset.setId(id);
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        asset.setCode("FA-TEST");
        asset.setName("Fixed asset");
        asset.setStatus("ACTIVE");
        asset.setVersion(0L);
        asset.setMaterialId(UUID.randomUUID());
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        asset.setCustodyKind(FixedAssetCustodyKind.WAREHOUSE);
        return asset;
    }

    private AssetManagementSettings settings() {
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setId(UUID.randomUUID());
        settings.setTenantId(TenantContext.currentTenant());
        settings.setCompanyId(companyId);
        settings.setCode("ASSET-SETTINGS");
        settings.setName("Fixed asset settings");
        settings.setInventoryNumberPrefix("FA");
        settings.setInventoryNumberNextValue(1L);
        settings.setInventoryNumberPadding(6);
        settings.setVersion(0L);
        return settings;
    }

    private AssetNumberRegistry registry(String number, UUID fixedAssetId, AssetNumberRegistryStatus status) {
        AssetNumberRegistry row = new AssetNumberRegistry();
        row.setId(UUID.randomUUID());
        row.setTenantId(TenantContext.currentTenant());
        row.setCompanyId(companyId);
        row.setCode("ANR-" + row.getId().toString().substring(0, 8));
        row.setName("Asset number " + number);
        row.setInventoryNumber(number.trim());
        row.setNormalizedNumber(number.trim().toUpperCase());
        row.setFixedAssetId(fixedAssetId);
        row.setSource(AssetNumberRegistrySource.MANUAL);
        row.setRegistryStatus(status);
        return row;
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T assign(T entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.currentTenant());
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(companyId);
        }
        if (entity.getVersion() == null) {
            entity.setVersion(0L);
        }
        return entity;
    }
}
