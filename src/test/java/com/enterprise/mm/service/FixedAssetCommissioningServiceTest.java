package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.Equipment;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.FixedAssetCommissionRequest;
import com.enterprise.mm.payload.FixedAssetEquipmentLinkRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.AssetNumberRegistryRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedAssetCommissioningServiceTest {
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final AssetNumberRegistryRepository registryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetManagementSettingsRepository settingsRepository = org.mockito.Mockito.mock(AssetManagementSettingsRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final EmployeeRepository employeeRepository = org.mockito.Mockito.mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final AttachmentRepository attachmentRepository = org.mockito.Mockito.mock(AttachmentRepository.class);
    private final IdempotencyRecordRepository idempotencyRepository = org.mockito.Mockito.mock(IdempotencyRecordRepository.class);
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();
    private final List<IdempotencyRecord> idempotencyRows = new ArrayList<>();

    private FixedAssetService service;
    private UUID companyId;
    private UUID assetId;
    private FixedAsset asset;
    private Equipment equipment;
    private Department department;
    private Employee employee;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-commissioning-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        assetId = UUID.randomUUID();
        department = department(UUID.randomUUID());
        employee = employee(UUID.randomUUID(), EmployeeStatus.ACTIVE, department);
        asset = asset(assetId);
        equipment = equipment(UUID.randomUUID(), companyId);
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
            new IdempotencyService(idempotencyRepository, new ObjectMapper().findAndRegisterModules()),
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
        lenient().when(equipmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, equipment.getId()
        )).thenReturn(Optional.of(equipment));
        lenient().when(fixedAssetRepository.existsByTenantIdAndCompanyIdAndEquipmentIdAndIdNotAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, equipment.getId(), assetId
        )).thenReturn(false);
        lenient().when(employeeRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Objects.equals(invocation.getArgument(2), employee.getId())
                ? Optional.of(employee)
                : Optional.empty());
        lenient().when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Objects.equals(invocation.getArgument(0), department.getId())
                ? Optional.of(department)
                : Optional.empty());
        lenient().when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> attachments.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
        lenient().when(idempotencyRepository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            any(), any(), any(), any(), any()
        )).thenAnswer(invocation -> idempotencyRows.stream()
            .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
            .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
            .filter(row -> invocation.getArgument(2).equals(row.getOperation()))
            .filter(row -> invocation.getArgument(3).equals(row.getResourceId()))
            .filter(row -> invocation.getArgument(4).equals(row.getIdempotencyKey()))
            .findFirst());
        lenient().when(idempotencyRepository.save(any(IdempotencyRecord.class)))
            .thenAnswer(invocation -> save(idempotencyRows, invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void commissionRequiresInventoryNumber() {
        asset.setInventoryNumber(null);

        assertThatThrownBy(() -> service.commission(assetId, commissionRequest(), "single-missing-number"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));
    }

    @Test
    void commissionMovesIssuedPendingAssetToInUseAndRecordsFirstCommissioning() {
        LocalDate commissionedOn = LocalDate.now().minusDays(1);

        service.commission(assetId, commissionRequest(commissionedOn), "single-ok");

        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(asset.getFirstCommissionedOn()).isEqualTo(commissionedOn);
        assertThat(asset.getFirstCommissionedAt()).isNotNull();
        assertThat(asset.getFirstCommissionedBy()).isEqualTo("system");
        assertThat(asset.getCustodianEmployeeId()).isEqualTo(employee.getId());
        assertThat(asset.getDepartmentId()).isEqualTo(department.getId());
        assertThat(asset.getOperatingLocation()).isEqualTo("Line 4");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("ASSET_COMMISSIONED");
            assertThat(event.getBeforeLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
            assertThat(event.getAfterLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        });
    }

    @Test
    void commissionRequiresIdempotencyKey() {
        assertThatThrownBy(() -> service.commission(assetId, commissionRequest()))
            .isInstanceOf(BadRequestException.class)
            .satisfies(error -> assertThat(((BadRequestException) error).getMessageKey())
                .isEqualTo("ASSET_IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void commissionRejectsMissingCanonicalFields() {
        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, null, department.getId(), "Line 4", LocalDate.now(), List.of(UUID.randomUUID().toString()), "ready"),
            "responsibleEmployeeId");
        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, employee.getId(), null, "Line 4", LocalDate.now(), List.of(UUID.randomUUID().toString()), "ready"),
            "departmentId");
        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, employee.getId(), department.getId(), " ", LocalDate.now(), List.of(UUID.randomUUID().toString()), "ready"),
            "operatingLocation");
        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, employee.getId(), department.getId(), "Line 4", LocalDate.now(), List.of(), "ready"),
            "evidenceIds");
    }

    @Test
    void commissionRejectsInactiveEmployeeWrongDepartmentFutureDateActiveTransferAndVersionConflict() {
        Employee inactive = employee(UUID.randomUUID(), EmployeeStatus.DISMISSED, department);
        doReturn(Optional.of(inactive)).when(employeeRepository)
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), org.mockito.ArgumentMatchers.eq(inactive.getId()));
        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, inactive.getId(), department.getId(), "Line 4", LocalDate.now(), List.of(UUID.randomUUID().toString()), "ready"),
            "responsibleEmployeeId");

        Department otherDepartment = department(UUID.randomUUID());
        employee.setDepartment(otherDepartment);
        assertCommissionBlocked(commissionRequest(), "responsibleEmployeeId");
        employee.setDepartment(department);

        assertCommissionBlocked(new FixedAssetCommissionRequest(
            asset.getVersion(), null, employee.getId(), department.getId(), "Line 4", LocalDate.now().plusDays(1), List.of(UUID.randomUUID().toString()), "ready"),
            "commissioningDate");

        asset.setActiveTransferId(UUID.randomUUID());
        assertCommissionBlocked(commissionRequest(), "activeTransferId");
        asset.setActiveTransferId(null);

        assertCommissionBlocked(new FixedAssetCommissionRequest(
            99L, null, employee.getId(), department.getId(), "Line 4", LocalDate.now(), List.of(UUID.randomUUID().toString()), "ready"),
            "expectedVersion");
    }

    @Test
    void commissioningPreservesFirstCommissionedOnOnIdempotentReplay() {
        LocalDate original = LocalDate.now().minusDays(5);
        asset.setFirstCommissionedOn(original);
        FixedAssetCommissionRequest request = commissionRequest(LocalDate.now());

        service.commission(assetId, request, "single-replay");
        service.commission(assetId, request, "single-replay");

        assertThat(asset.getFirstCommissionedOn()).isEqualTo(original);
        assertThat(events).hasSize(1);
    }

    @Test
    void idempotentReplayRequiresAssetStillInScopeBeforeReturningStoredResponse() {
        FixedAssetCommissionRequest request = commissionRequest();
        service.commission(assetId, request, "single-scope-replay");
        when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, assetId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.commission(assetId, request, "single-scope-replay"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void linkEquipmentRequiresSameCompanyOneToOneRelationship() {
        service.linkEquipment(assetId, new FixedAssetEquipmentLinkRequest(asset.getVersion(), equipment.getId(), "mapped"));

        assertThat(asset.getEquipmentId()).isEqualTo(equipment.getId());
        assertThat(events).singleElement().satisfies(event ->
            assertThat(event.getEventType()).isEqualTo("ASSET_EQUIPMENT_LINKED"));

        UUID otherEquipmentId = UUID.randomUUID();
        lenient().when(equipmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, otherEquipmentId
        )).thenReturn(Optional.of(equipment(otherEquipmentId, companyId)));
        lenient().when(fixedAssetRepository.existsByTenantIdAndCompanyIdAndEquipmentIdAndIdNotAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, otherEquipmentId, assetId
        )).thenReturn(true);

        assertThatThrownBy(() -> service.linkEquipment(
            assetId,
            new FixedAssetEquipmentLinkRequest(asset.getVersion(), otherEquipmentId, "duplicate")
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_EQUIPMENT_ALREADY_LINKED"));
    }

    @Test
    void unlinkEquipmentClearsRelationshipAndAppendsEvent() {
        asset.setEquipmentId(equipment.getId());

        service.unlinkEquipment(assetId, new VersionedAssetCommand(asset.getVersion(), "unmapped"));

        assertThat(asset.getEquipmentId()).isNull();
        assertThat(events).singleElement().satisfies(event ->
            assertThat(event.getEventType()).isEqualTo("ASSET_EQUIPMENT_UNLINKED"));
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
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setDepartmentId(department.getId());
        return asset;
    }

    private FixedAssetCommissionRequest commissionRequest() {
        return commissionRequest(LocalDate.now());
    }

    private FixedAssetCommissionRequest commissionRequest(LocalDate commissioningDate) {
        return new FixedAssetCommissionRequest(
            asset.getVersion(),
            null,
            employee.getId(),
            department.getId(),
            "Line 4",
            commissioningDate,
            List.of(assetEvidence().getId().toString()),
            "installed"
        );
    }

    private void assertCommissionBlocked(FixedAssetCommissionRequest request, String field) {
        assertThatThrownBy(() -> service.commission(assetId, request, UUID.randomUUID().toString()))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED");
                assertThat(String.valueOf(business.getContext())).contains(field);
            });
        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
    }

    private Equipment equipment(UUID id, UUID companyId) {
        Equipment equipment = new Equipment();
        equipment.setId(id);
        equipment.setTenantId(TenantContext.currentTenant());
        equipment.setCompanyId(companyId);
        equipment.setCode("EQ-" + id.toString().substring(0, 8));
        equipment.setName("Equipment");
        equipment.setStatus("ACTIVE");
        equipment.setVersion(0L);
        return equipment;
    }

    private Employee employee(UUID id, EmployeeStatus status, Department department) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setTenantId(TenantContext.currentTenant());
        employee.setCompanyId(companyId);
        employee.setCode("EMP-" + id.toString().substring(0, 8));
        employee.setName("Employee");
        employee.setStatus("ACTIVE");
        employee.setVersion(0L);
        employee.setEmploymentStatus(status);
        employee.setDepartment(department);
        return employee;
    }

    private Department department(UUID id) {
        Department department = new Department();
        department.setId(id);
        department.setTenantId(TenantContext.currentTenant());
        department.setCompanyId(companyId);
        department.setCode("DEP-" + id.toString().substring(0, 8));
        department.setName("Department");
        department.setStatus("ACTIVE");
        department.setVersion(0L);
        return department;
    }

    private Attachment assetEvidence() {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setTenantId(TenantContext.currentTenant());
        attachment.setCompanyId(companyId);
        attachment.setCode("ATT-" + attachment.getId().toString().substring(0, 8));
        attachment.setName("commissioning.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("FIXED_ASSET");
        attachment.setEntityId(assetId);
        attachment.setAttachmentType("COMMISSIONING_EVIDENCE");
        attachment.setOriginalFileName("commissioning.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(10L);
        attachment.setChecksumSha256("0".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey("test");
        attachment.setUploadedAt(java.time.LocalDateTime.now());
        attachments.add(attachment);
        return attachment;
    }

    private <T extends BusinessEntity> T save(List<T> rows, T row) {
        assign(row);
        rows.removeIf(existing -> existing.getId().equals(row.getId()));
        rows.add(row);
        return row;
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
