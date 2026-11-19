package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.VersionedAssetCommand;
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

class FixedAssetLifecycleOperationsTest {
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final AssetNumberRegistryRepository registryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetManagementSettingsRepository settingsRepository = org.mockito.Mockito.mock(AssetManagementSettingsRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final EmployeeRepository employeeRepository = org.mockito.Mockito.mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final AttachmentRepository attachmentRepository = org.mockito.Mockito.mock(AttachmentRepository.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final List<AssetEvent> events = new ArrayList<>();

    private FixedAssetService service;
    private UUID companyId;
    private UUID assetId;
    private FixedAsset asset;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-lifecycle-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        assetId = UUID.randomUUID();
        asset = asset(assetId);
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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void markIdleAndReactivateMoveBetweenInUseAndIdle() {
        service.markIdle(assetId, new VersionedAssetCommand(asset.getVersion(), "standby"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IDLE);

        service.reactivate(assetId, new VersionedAssetCommand(asset.getVersion(), "assigned"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(events).extracting(AssetEvent::getEventType)
            .containsExactly("ASSET_MARKED_IDLE", "ASSET_REACTIVATED");
    }

    @Test
    void repairCycleMovesUnderRepairThenIdle() {
        service.sendToRepair(assetId, new VersionedAssetCommand(asset.getVersion(), "warranty"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.UNDER_REPAIR);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.EXTERNAL);

        service.completeRepair(assetId, new VersionedAssetCommand(asset.getVersion(), "done"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IDLE);
        assertThat(events).extracting(AssetEvent::getEventType)
            .containsExactly("ASSET_SENT_TO_REPAIR", "ASSET_REPAIR_COMPLETED");
    }

    @Test
    void disposeIsTerminalAndBlocksReactivation() {
        service.dispose(assetId, new VersionedAssetCommand(asset.getVersion(), "retired"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.DISPOSED);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.NONE);
        assertThat(asset.getDisposedAt()).isNotNull();

        assertThatThrownBy(() -> service.reactivate(assetId, new VersionedAssetCommand(asset.getVersion(), "undo")))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_INVALID_TRANSITION"));
    }

    @Test
    void returnToSupplierMovesToExternalTerminalStatus() {
        service.returnToSupplier(assetId, new VersionedAssetCommand(asset.getVersion(), "supplier credit"));

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.RETURNED_TO_SUPPLIER);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.EXTERNAL);
        assertThat(events).singleElement().satisfies(event ->
            assertThat(event).satisfies(row -> {
                assertThat(row.getEventType()).isEqualTo("ASSET_RETURNED_TO_SUPPLIER");
                assertThat(row.getSourceDocumentType()).isEqualTo("FIXED_ASSET");
                assertThat(row.getSourceDocumentId()).isEqualTo(assetId);
            }));
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
        asset.setInventoryNumber("FA-0001");
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setDepartmentId(UUID.randomUUID());
        return asset;
    }

    private <T extends BusinessEntity> T assign(T entity) {
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
