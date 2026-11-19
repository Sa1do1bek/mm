package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.enums.AssetNumberRegistrySource;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.FixedAssetBulkCommissionRequest;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FixedAssetBulkCommissioningServiceTest {
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final AssetNumberRegistryRepository registryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetManagementSettingsRepository settingsRepository = org.mockito.Mockito.mock(AssetManagementSettingsRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final EmployeeRepository employeeRepository = org.mockito.Mockito.mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final AttachmentRepository attachmentRepository = org.mockito.Mockito.mock(AttachmentRepository.class);
    private final IdempotencyRecordRepository idempotencyRepository = org.mockito.Mockito.mock(IdempotencyRecordRepository.class);
    private final List<FixedAsset> assets = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<AssetNumberRegistry> registryRows = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();
    private final List<IdempotencyRecord> idempotencyRows = new ArrayList<>();

    private FixedAssetService service;
    private UUID companyId;
    private Employee activeEmployee;
    private Employee inactiveEmployee;
    private Department department;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-bulk-commission-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        activeEmployee = employee(UUID.randomUUID(), EmployeeStatus.ACTIVE);
        inactiveEmployee = employee(UUID.randomUUID(), EmployeeStatus.DISMISSED);
        department = department(UUID.randomUUID());
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

        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> assets.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(eventRepository.save(any(AssetEvent.class))).thenAnswer(invocation -> {
            AssetEvent event = assign(invocation.getArgument(0));
            events.add(event);
            return event;
        });
        lenient().when(registryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> registryRows.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getNormalizedNumber()))
                .findFirst());
        lenient().when(registryRepository.save(any(AssetNumberRegistry.class))).thenAnswer(invocation -> save(registryRows, invocation.getArgument(0)));
        lenient().when(employeeRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Optional.ofNullable(invocation.getArgument(2).equals(activeEmployee.getId()) ? activeEmployee
                : invocation.getArgument(2).equals(inactiveEmployee.getId()) ? inactiveEmployee : null));
        lenient().when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Optional.ofNullable(invocation.getArgument(0).equals(department.getId()) ? department : null));
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
    void twoValidAssetsBothCommissionAtomically() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        var response = service.bulkCommission(request(row(first, "FA-0001"), row(second, "FA-0002")), "bulk-ok");

        assertThat(response.assets()).hasSize(2);
        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(second.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(first.getInventoryNumber()).isEqualTo("FA-0001");
        assertThat(second.getInventoryNumber()).isEqualTo("FA-0002");
        assertThat(events).extracting(AssetEvent::getEventType)
            .containsExactly(
                "ASSET_INVENTORY_NUMBER_ASSIGNED",
                "ASSET_COMMISSIONED",
                "ASSET_INVENTORY_NUMBER_ASSIGNED",
                "ASSET_COMMISSIONED"
            );
    }

    @Test
    void oneInvalidRowPreventsAllCommissioning() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        assertThatThrownBy(() -> service.bulkCommission(request(row(first, "FA-0001"), rowWithoutEvidence(second)), "bulk-invalid"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(second.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(first.getInventoryNumber()).isNull();
        assertThat(second.getInventoryNumber()).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    void versionConflictOnOneRowPreventsAllCommissioning() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        assertThatThrownBy(() -> service.bulkCommission(
            request(row(first, "FA-0001"), row(second.getId(), 99L, "FA-0002")),
            "bulk-version"
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(second.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(events).isEmpty();
    }

    @Test
    void duplicateInventoryNumberPreventsAllCommissioning() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        assertThatThrownBy(() -> service.bulkCommission(request(row(first, "FA-0001"), row(second, " fa-0001 ")), "bulk-dup"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));

        assertThat(first.getInventoryNumber()).isNull();
        assertThat(second.getInventoryNumber()).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    void inactiveEmployeePreventsAllCommissioning() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        assertThatThrownBy(() -> service.bulkCommission(request(row(first, "FA-0001"), row(second, "FA-0002", inactiveEmployee.getId())), "bulk-employee"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(second.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(events).isEmpty();
    }

    @Test
    void invalidEvidenceIdStringIsReportedAsRowValidationBlocker() {
        FixedAsset first = addAsset("FA-A", null);
        String json = """
            {
              "rows": [{
                "assetId": "%s",
                "expectedVersion": %d,
                "inventoryNumber": "FA-0001",
                "responsibleEmployeeId": "%s",
                "departmentId": "%s",
                "operatingLocation": "Line 4",
                "commissioningDate": "%s",
                "evidenceIds": ["111"],
                "reason": "ready"
              }]
            }
            """.formatted(
                first.getId(),
                first.getVersion(),
                activeEmployee.getId(),
                department.getId(),
                LocalDate.now()
            );

        assertThatThrownBy(() -> service.bulkCommission(
            new ObjectMapper().findAndRegisterModules().readValue(json, FixedAssetBulkCommissionRequest.class),
            "bulk-invalid-evidence"
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED");
                assertThat(String.valueOf(business.getContext()))
                    .contains("evidenceIds")
                    .contains("Expected a UUID");
            });

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(events).isEmpty();
    }

    @Test
    void unattachedEvidenceUuidIsReportedAsRowValidationBlocker() {
        FixedAsset first = addAsset("FA-A", null);
        UUID missingAttachmentId = UUID.randomUUID();

        assertThatThrownBy(() -> service.bulkCommission(
            request(row(first, "FA-0001", activeEmployee.getId(), List.of(missingAttachmentId.toString()))),
            "bulk-missing-evidence"
        ))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> {
                BusinessException business = (BusinessException) error;
                assertThat(business.getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED");
                assertThat(String.valueOf(business.getContext()))
                    .contains("evidenceIds")
                    .contains("Commissioning evidence must be uploaded on this fixed asset");
            });

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(events).isEmpty();
    }

    @Test
    void activeTransferPreventsAllCommissioning() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);
        second.setActiveTransferId(UUID.randomUUID());

        assertThatThrownBy(() -> service.bulkCommission(request(row(first, "FA-0001"), row(second, "FA-0002")), "bulk-transfer"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_BULK_COMMISSION_BLOCKED"));

        assertThat(first.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(second.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(events).isEmpty();
    }

    @Test
    void sameIdempotencyKeyAndSameRequestReplaysWithoutAdditionalEvents() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);
        FixedAssetBulkCommissionRequest request = request(row(first, "FA-0001"), row(second, "FA-0002"));

        var firstResponse = service.bulkCommission(request, "bulk-replay");
        var replayedResponse = service.bulkCommission(request, "bulk-replay");

        assertThat(firstResponse.assets()).hasSize(2);
        assertThat(replayedResponse.assets()).hasSize(2);
        assertThat(events).hasSize(4);
    }

    @Test
    void sameIdempotencyKeyAndDifferentRequestConflicts() {
        FixedAsset first = addAsset("FA-A", null);
        FixedAsset second = addAsset("FA-B", null);

        service.bulkCommission(request(row(first, "FA-0001"), row(second, "FA-0002")), "bulk-conflict");

        assertThatThrownBy(() -> service.bulkCommission(request(row(first, "FA-0001"), row(second, "FA-0003")), "bulk-conflict"))
            .isInstanceOf(ConflictException.class);
    }

    private FixedAsset addAsset(String code, String inventoryNumber) {
        FixedAsset asset = new FixedAsset();
        asset.setId(UUID.randomUUID());
        asset.setTenantId(TenantContext.currentTenant());
        asset.setCompanyId(companyId);
        asset.setCode(code);
        asset.setName(code);
        asset.setStatus("ACTIVE");
        asset.setVersion(0L);
        asset.setMaterialId(UUID.randomUUID());
        asset.setInventoryNumber(inventoryNumber);
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setDepartmentId(department.getId());
        assets.add(asset);
        return asset;
    }

    private FixedAssetBulkCommissionRequest request(FixedAssetBulkCommissionRequest.Row... rows) {
        return new FixedAssetBulkCommissionRequest(List.of(rows));
    }

    private FixedAssetBulkCommissionRequest.Row row(FixedAsset asset, String inventoryNumber) {
        return row(asset, inventoryNumber, activeEmployee.getId());
    }

    private FixedAssetBulkCommissionRequest.Row row(FixedAsset asset, String inventoryNumber, UUID employeeId) {
        return row(asset, inventoryNumber, employeeId, List.of(assetEvidence(asset).getId().toString()));
    }

    private FixedAssetBulkCommissionRequest.Row row(
        FixedAsset asset,
        String inventoryNumber,
        UUID employeeId,
        List<String> evidenceIds
    ) {
        return row(asset.getId(), asset.getVersion(), inventoryNumber, employeeId, evidenceIds);
    }

    private FixedAssetBulkCommissionRequest.Row row(UUID assetId, Long expectedVersion, String inventoryNumber) {
        return row(assetId, expectedVersion, inventoryNumber, activeEmployee.getId(), List.of(UUID.randomUUID().toString()));
    }

    private FixedAssetBulkCommissionRequest.Row rowWithoutEvidence(FixedAsset asset) {
        return row(asset.getId(), asset.getVersion(), "FA-NOT-READY", activeEmployee.getId(), List.of());
    }

    private FixedAssetBulkCommissionRequest.Row row(
        UUID assetId,
        Long expectedVersion,
        String inventoryNumber,
        UUID employeeId,
        List<String> evidenceIds
    ) {
        return new FixedAssetBulkCommissionRequest.Row(
            assetId,
            expectedVersion,
            inventoryNumber,
            employeeId,
            department.getId(),
            "Line 4",
            LocalDate.now(),
            evidenceIds,
            "ready"
        );
    }

    private Attachment assetEvidence(FixedAsset asset) {
        Attachment attachment = new Attachment();
        attachment.setId(UUID.randomUUID());
        attachment.setTenantId(TenantContext.currentTenant());
        attachment.setCompanyId(companyId);
        attachment.setCode("ATT-" + attachment.getId().toString().substring(0, 8));
        attachment.setName("commissioning.pdf");
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("FIXED_ASSET");
        attachment.setEntityId(asset.getId());
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

    private Employee employee(UUID id, EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setTenantId(TenantContext.currentTenant());
        employee.setCompanyId(companyId);
        employee.setCode("EMP-" + id.toString().substring(0, 8));
        employee.setName("Employee");
        employee.setStatus("ACTIVE");
        employee.setVersion(0L);
        employee.setEmploymentStatus(status);
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
