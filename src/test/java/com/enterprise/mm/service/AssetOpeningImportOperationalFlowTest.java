package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.AssetOpeningImport;
import com.enterprise.mm.entity.AssetOpeningImportLine;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.AssetOpeningImportStatus;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.AssetOpeningImportCreateRequest;
import com.enterprise.mm.payload.AssetOpeningImportLineRequest;
import com.enterprise.mm.payload.AssetOpeningImportPostRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetNumberRegistryRepository;
import com.enterprise.mm.repository.AssetOpeningImportLineRepository;
import com.enterprise.mm.repository.AssetOpeningImportRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssetOpeningImportOperationalFlowTest {
    private final AssetOpeningImportRepository importRepository = org.mockito.Mockito.mock(AssetOpeningImportRepository.class);
    private final AssetOpeningImportLineRepository lineRepository = org.mockito.Mockito.mock(AssetOpeningImportLineRepository.class);
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetNumberRegistryRepository registryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final MaterialRepository materialRepository = org.mockito.Mockito.mock(MaterialRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final WarehouseRepository warehouseRepository = org.mockito.Mockito.mock(WarehouseRepository.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final ApprovalEngineService approvalEngineService = org.mockito.Mockito.mock(ApprovalEngineService.class);
    private final List<AssetOpeningImport> imports = new ArrayList<>();
    private final List<AssetOpeningImportLine> lines = new ArrayList<>();
    private final List<FixedAsset> assets = new ArrayList<>();
    private final List<AssetNumberRegistry> registryRows = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();

    private AssetOpeningImportService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-opening-import-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        service = new AssetOpeningImportService(
            importRepository,
            lineRepository,
            fixedAssetRepository,
            registryRepository,
            eventRepository,
            materialRepository,
            equipmentRepository,
            departmentRepository,
            warehouseRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            idempotencyService,
            approvalEngineService
        );

        lenient().when(importRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> imports.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
        lenient().when(importRepository.save(any(AssetOpeningImport.class))).thenAnswer(invocation -> save(imports, invocation.getArgument(0)));
        lenient().when(lineRepository.findByTenantIdAndCompanyIdAndAssetOpeningImportIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> lines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetOpeningImportId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(lineRepository.save(any(AssetOpeningImportLine.class))).thenAnswer(invocation -> save(lines, invocation.getArgument(0)));
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> save(assets, invocation.getArgument(0)));
        lenient().when(fixedAssetRepository.existsByTenantIdAndCompanyIdAndEquipmentIdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(false);
        lenient().when(materialRepository.findByIdAndIsDeletedFalse(any()))
            .thenAnswer(invocation -> Optional.of(material(invocation.getArgument(0), companyId, MaterialType.FIXED_ASSET, "ACTIVE")));
        lenient().when(equipmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(2) == null
                ? Optional.empty()
                : Optional.of(equipment(invocation.getArgument(2), invocation.getArgument(1), "ACTIVE")));
        lenient().when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(0) == null
                ? Optional.empty()
                : Optional.of(department(invocation.getArgument(0), invocation.getArgument(2))));
        lenient().when(warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Optional.of(warehouse(invocation.getArgument(2), invocation.getArgument(1))));
        lenient().when(registryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> registryRows.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getNormalizedNumber()))
                .findFirst());
        lenient().when(registryRepository.save(any(AssetNumberRegistry.class)))
            .thenAnswer(invocation -> save(registryRows, invocation.getArgument(0)));
        lenient().when(eventRepository.save(any(AssetEvent.class))).thenAnswer(invocation -> save(events, invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createStoresValidatedOpeningImportLines() {
        var response = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(lineRequest())));

        assertThat(response.getStatus()).isEqualTo(AssetOpeningImportStatus.VALIDATED.name());
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getInventoryNumber()).isEqualTo("FA-OPEN-001");
            assertThat(line.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_WAREHOUSE);
            assertThat(line.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
            assertThat(line.getValidationStatus()).isEqualTo("VALID");
        });
    }

    @Test
    void postRequiresApprovedOpeningImport() {
        var created = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(lineRequest())));
        AssetOpeningImport openingImport = imports.getFirst();

        assertThatThrownBy(() ->
            service.post(created.getId(), new AssetOpeningImportPostRequest(openingImport.getVersion(), "opening balance")))
            .hasMessageContaining("Opening import is not ready to post");
        assertThat(assets).isEmpty();
        assertThat(registryRows).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void submittedAndRejectedOpeningImportsCannotBePosted() {
        var created = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(lineRequest())));
        AssetOpeningImport openingImport = imports.getFirst();

        service.submit(created.getId(), new VersionedAssetCommand(openingImport.getVersion(), "approval required"));
        assertThatThrownBy(() ->
            service.post(created.getId(), new AssetOpeningImportPostRequest(openingImport.getVersion(), "opening balance")))
            .hasMessageContaining("Opening import is not ready to post");

        service.markRejectedFromApproval(openingImport.getId());
        assertThatThrownBy(() ->
            service.post(created.getId(), new AssetOpeningImportPostRequest(openingImport.getVersion(), "opening balance")))
            .hasMessageContaining("Opening import is not ready to post");
        assertThat(assets).isEmpty();
    }

    @Test
    void approvedPostCreatesFixedAssetRegistryAndAppendOnlyEventWithoutStockMutation() {
        var created = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(lineRequest())));
        AssetOpeningImport openingImport = imports.getFirst();

        service.submit(created.getId(), new VersionedAssetCommand(openingImport.getVersion(), "approval required"));
        service.markApprovedFromApproval(openingImport.getId());
        service.post(created.getId(), new AssetOpeningImportPostRequest(openingImport.getVersion(), "opening balance"));

        assertThat(openingImport.getOpeningImportStatus()).isEqualTo(AssetOpeningImportStatus.POSTED);
        assertThat(assets).singleElement().satisfies(asset -> {
            assertThat(asset.getOpeningSourceType()).isEqualTo("ASSET_OPENING_IMPORT");
            assertThat(asset.getOpeningSourceId()).isEqualTo(lines.getFirst().getId());
            assertThat(asset.getInventoryNumber()).isEqualTo("FA-OPEN-001");
            assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        });
        assertThat(registryRows).singleElement().satisfies(row -> {
            assertThat(row.getSource()).isEqualTo(AssetNumberRegistrySource.OPENING_IMPORT);
            assertThat(row.getRegistryStatus()).isEqualTo(AssetNumberRegistryStatus.ASSIGNED);
            assertThat(row.getFixedAssetId()).isEqualTo(assets.getFirst().getId());
        });
        assertThat(events).singleElement().satisfies(event ->
            assertThat(event.getEventType()).isEqualTo("ASSET_OPENING_IMPORT_POSTED"));
    }

    @Test
    void rejectsNonFixedAssetMaterialsDuringValidation() {
        UUID tmbMaterialId = UUID.randomUUID();
        UUID serviceMaterialId = UUID.randomUUID();
        when(materialRepository.findByIdAndIsDeletedFalse(tmbMaterialId))
            .thenReturn(Optional.of(material(tmbMaterialId, companyId, MaterialType.TMB, "ACTIVE")));
        when(materialRepository.findByIdAndIsDeletedFalse(serviceMaterialId))
            .thenReturn(Optional.of(material(serviceMaterialId, companyId, MaterialType.SERVICES, "ACTIVE")));

        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(tmbMaterialId, null, UUID.randomUUID(), null, "FA-TMB")))))
            .hasMessageContaining("FIXED_ASSET");
        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(serviceMaterialId, null, UUID.randomUUID(), null, "FA-SVC")))))
            .hasMessageContaining("FIXED_ASSET");
    }

    @Test
    void rejectsMissingAndCrossCompanyMaterialReferences() {
        UUID missingMaterialId = UUID.randomUUID();
        UUID otherCompanyMaterialId = UUID.randomUUID();
        when(materialRepository.findByIdAndIsDeletedFalse(missingMaterialId)).thenReturn(Optional.empty());
        when(materialRepository.findByIdAndIsDeletedFalse(otherCompanyMaterialId))
            .thenReturn(Optional.of(material(otherCompanyMaterialId, UUID.randomUUID(), MaterialType.FIXED_ASSET, "ACTIVE")));

        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(missingMaterialId, null, UUID.randomUUID(), null, "FA-MISSING")))))
            .hasMessageContaining("Material not found");
        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(otherCompanyMaterialId, null, UUID.randomUUID(), null, "FA-CROSS")))))
            .hasMessageContaining("current company");
    }

    @Test
    void rejectsCrossCompanyAndAlreadyLinkedEquipment() {
        UUID equipmentId = UUID.randomUUID();
        doReturn(Optional.empty()).when(equipmentRepository)
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), org.mockito.ArgumentMatchers.eq(equipmentId));

        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(UUID.randomUUID(), equipmentId, UUID.randomUUID(), null, "FA-EQ-CROSS")))))
            .hasMessageContaining("Equipment not found");

        UUID linkedEquipmentId = UUID.randomUUID();
        doReturn(Optional.of(equipment(linkedEquipmentId, companyId, "ACTIVE"))).when(equipmentRepository)
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), org.mockito.ArgumentMatchers.eq(linkedEquipmentId));
        doReturn(true).when(fixedAssetRepository)
            .existsByTenantIdAndCompanyIdAndEquipmentIdAndIsDeletedFalse(any(), any(), org.mockito.ArgumentMatchers.eq(linkedEquipmentId));

        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(UUID.randomUUID(), linkedEquipmentId, UUID.randomUUID(), null, "FA-EQ-LINKED")))))
            .hasMessageContaining("already linked");
    }

    @Test
    void rejectsInvalidDepartmentAndWarehouseReferences() {
        UUID departmentId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        doReturn(Optional.empty()).when(departmentRepository)
            .findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(org.mockito.ArgumentMatchers.eq(departmentId), any(), any());
        doReturn(Optional.empty()).when(warehouseRepository)
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), org.mockito.ArgumentMatchers.eq(warehouseId));

        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(UUID.randomUUID(), null, null, departmentId, "FA-DEPT",
                "IDLE", "DEPARTMENT")))))
            .hasMessageContaining("Department not found");
        assertThatThrownBy(() -> service.create(new AssetOpeningImportCreateRequest(
            UUID.randomUUID(), List.of(lineRequest(UUID.randomUUID(), null, warehouseId, null, "FA-WH")))))
            .hasMessageContaining("Warehouse not found");
    }

    @Test
    void postingRevalidatesReferencesAndRemainsAtomic() {
        UUID materialId = UUID.randomUUID();
        var created = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(
            lineRequest(materialId, null, UUID.randomUUID(), null, "FA-OPEN-001"),
            lineRequest(UUID.randomUUID(), null, UUID.randomUUID(), null, "FA-OPEN-002")
        )));
        AssetOpeningImport openingImport = imports.getFirst();
        service.submit(created.getId(), new VersionedAssetCommand(openingImport.getVersion(), "approval required"));
        service.markApprovedFromApproval(openingImport.getId());
        when(materialRepository.findByIdAndIsDeletedFalse(materialId))
            .thenReturn(Optional.of(material(materialId, UUID.randomUUID(), MaterialType.FIXED_ASSET, "ACTIVE")));

        assertThatThrownBy(() ->
            service.post(created.getId(), new AssetOpeningImportPostRequest(openingImport.getVersion(), "opening balance")))
            .hasMessageContaining("current company");
        assertThat(assets).isEmpty();
        assertThat(registryRows).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void submitStartsSharedApprovalAndApprovalCallbacksControlOpeningImportStatus() {
        var created = service.create(new AssetOpeningImportCreateRequest(UUID.randomUUID(), List.of(lineRequest())));
        AssetOpeningImport openingImport = imports.getFirst();
        when(approvalEngineService.start("ASSET_OPENING_IMPORT", openingImport.getId()))
            .thenReturn(new ApprovalInstanceResponse(
                UUID.randomUUID(), null, null, "ASSET_OPENING_IMPORT", openingImport.getId(), "Asset opening import",
                ApprovalInstanceStatus.PENDING, 1, null, null, List.of()
            ));

        var submitted = service.submit(
            created.getId(),
            new VersionedAssetCommand(openingImport.getVersion(), "approval required")
        );

        assertThat(submitted.getStatus()).isEqualTo(AssetOpeningImportStatus.SUBMITTED.name());
        verify(approvalEngineService).start("ASSET_OPENING_IMPORT", openingImport.getId());

        service.markApprovedFromApproval(openingImport.getId());
        assertThat(openingImport.getOpeningImportStatus()).isEqualTo(AssetOpeningImportStatus.APPROVED);

        service.markRejectedFromApproval(openingImport.getId());
        assertThat(openingImport.getOpeningImportStatus()).isEqualTo(AssetOpeningImportStatus.REJECTED);
    }

    private AssetOpeningImportLineRequest lineRequest() {
        return lineRequest(UUID.randomUUID(), null, UUID.randomUUID(), null, "FA-OPEN-001");
    }

    private AssetOpeningImportLineRequest lineRequest(
        UUID materialId,
        UUID equipmentId,
        UUID warehouseId,
        UUID departmentId,
        String inventoryNumber
    ) {
        return lineRequest(materialId, equipmentId, warehouseId, departmentId, inventoryNumber, "IN_WAREHOUSE", "WAREHOUSE");
    }

    private AssetOpeningImportLineRequest lineRequest(
        UUID materialId,
        UUID equipmentId,
        UUID warehouseId,
        UUID departmentId,
        String inventoryNumber,
        String lifecycleStatus,
        String custodyKind
    ) {
        return new AssetOpeningImportLineRequest(
            materialId,
            equipmentId,
            inventoryNumber,
            "SN-1",
            lifecycleStatus,
            custodyKind,
            warehouseId,
            departmentId,
            BigDecimal.TEN
        );
    }

    private Material material(UUID id, UUID companyId, MaterialType materialType, String status) {
        Material material = new Material();
        material.setId(id);
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(companyId);
        material.setCode("MAT-" + id.toString().substring(0, 8));
        material.setName("Material " + id);
        material.setStatus(status);
        material.setMaterialType(materialType);
        return material;
    }

    private Equipment equipment(UUID id, UUID companyId, String status) {
        Equipment equipment = new Equipment();
        equipment.setId(id);
        equipment.setTenantId(TenantContext.currentTenant());
        equipment.setCompanyId(companyId);
        equipment.setCode("EQ-" + id.toString().substring(0, 8));
        equipment.setName("Equipment " + id);
        equipment.setStatus(status);
        return equipment;
    }

    private Department department(UUID id, UUID companyId) {
        Department department = new Department();
        department.setId(id);
        department.setTenantId(TenantContext.currentTenant());
        department.setCompanyId(companyId);
        department.setCode("DEP-" + id.toString().substring(0, 8));
        department.setName("Department " + id);
        department.setStatus("ACTIVE");
        return department;
    }

    private Warehouse warehouse(UUID id, UUID companyId) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(id);
        warehouse.setTenantId(TenantContext.currentTenant());
        warehouse.setCompanyId(companyId);
        warehouse.setCode("WH-" + id.toString().substring(0, 8));
        warehouse.setName("Warehouse " + id);
        warehouse.setStatus("ACTIVE");
        return warehouse;
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
