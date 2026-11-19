package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AssetAcceptance;
import com.enterprise.mm.entity.AssetAcceptanceLine;
import com.enterprise.mm.entity.AssetAcceptanceUnit;
import com.enterprise.mm.entity.AssetAllocation;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetHandover;
import com.enterprise.mm.entity.AssetHandoverLine;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.AssetManagementSettings;
import com.enterprise.mm.entity.AssetNumberRegistry;
import com.enterprise.mm.entity.AssetVerification;
import com.enterprise.mm.entity.AssetVerificationLine;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.enums.AssetAcceptanceApprovalStatus;
import com.enterprise.mm.enums.AssetAcceptancePostingStatus;
import com.enterprise.mm.enums.AssetAcceptanceUnitQualityDecision;
import com.enterprise.mm.enums.AssetAllocationStatus;
import com.enterprise.mm.enums.AssetHandoverStatus;
import com.enterprise.mm.enums.AssetNumberRegistryStatus;
import com.enterprise.mm.enums.AssetVerificationResolutionStatus;
import com.enterprise.mm.enums.AssetVerificationStatus;
import com.enterprise.mm.enums.EmployeeStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.AssetAcceptanceCreateRequest;
import com.enterprise.mm.payload.AssetAcceptanceLineRequest;
import com.enterprise.mm.payload.AssetAcceptancePostRequest;
import com.enterprise.mm.payload.AssetAcceptanceQualityDecisionRequest;
import com.enterprise.mm.payload.AssetAcceptanceUnitRequest;
import com.enterprise.mm.payload.AssetHandoverCreateRequest;
import com.enterprise.mm.payload.AssetHandoverLineRequest;
import com.enterprise.mm.payload.AssetVerificationCreateRequest;
import com.enterprise.mm.payload.AssetVerificationScanRequest;
import com.enterprise.mm.payload.FixedAssetBulkCommissionRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetAcceptanceLineRepository;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.AssetAllocationRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetHandoverLineRepository;
import com.enterprise.mm.repository.AssetHandoverRepository;
import com.enterprise.mm.repository.AssetManagementSettingsRepository;
import com.enterprise.mm.repository.AssetNumberRegistryRepository;
import com.enterprise.mm.repository.AssetVerificationLineRepository;
import com.enterprise.mm.repository.AssetVerificationRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.ContractInvoiceEvidenceSnapshotRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.EquipmentRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FixedAssetWave2ScenarioTest {
    private final AssetAcceptanceRepository acceptanceRepository = org.mockito.Mockito.mock(AssetAcceptanceRepository.class);
    private final AssetAcceptanceLineRepository acceptanceLineRepository = org.mockito.Mockito.mock(AssetAcceptanceLineRepository.class);
    private final AssetAcceptanceUnitRepository acceptanceUnitRepository = org.mockito.Mockito.mock(AssetAcceptanceUnitRepository.class);
    private final PurchaseOrderRepository purchaseOrderRepository = org.mockito.Mockito.mock(PurchaseOrderRepository.class);
    private final PurchaseOrderItemRepository purchaseOrderItemRepository = org.mockito.Mockito.mock(PurchaseOrderItemRepository.class);
    private final AssetManagementSettingsService settingsService = org.mockito.Mockito.mock(AssetManagementSettingsService.class);
    private final StorageLocationRepository storageLocationRepository = org.mockito.Mockito.mock(StorageLocationRepository.class);
    private final EmployeeRepository employeeRepository = org.mockito.Mockito.mock(EmployeeRepository.class);
    private final ApprovalEngineService approvalEngineService = org.mockito.Mockito.mock(ApprovalEngineService.class);
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final AssetNumberRegistryRepository numberRegistryRepository = org.mockito.Mockito.mock(AssetNumberRegistryRepository.class);
    private final AssetManagementSettingsRepository settingsRepository = org.mockito.Mockito.mock(AssetManagementSettingsRepository.class);
    private final EquipmentRepository equipmentRepository = org.mockito.Mockito.mock(EquipmentRepository.class);
    private final DepartmentRepository departmentRepository = org.mockito.Mockito.mock(DepartmentRepository.class);
    private final AttachmentRepository attachmentRepository = org.mockito.Mockito.mock(AttachmentRepository.class);
    private final AssetHandoverRepository handoverRepository = org.mockito.Mockito.mock(AssetHandoverRepository.class);
    private final AssetHandoverLineRepository handoverLineRepository = org.mockito.Mockito.mock(AssetHandoverLineRepository.class);
    private final AssetAllocationRepository allocationRepository = org.mockito.Mockito.mock(AssetAllocationRepository.class);
    private final AssetVerificationRepository verificationRepository = org.mockito.Mockito.mock(AssetVerificationRepository.class);
    private final AssetVerificationLineRepository verificationLineRepository = org.mockito.Mockito.mock(AssetVerificationLineRepository.class);
    private final IdempotencyRecordRepository idempotencyRepository = org.mockito.Mockito.mock(IdempotencyRecordRepository.class);
    private final ContractInvoiceEvidenceSnapshotRepository evidenceSnapshotRepository = org.mockito.Mockito.mock(ContractInvoiceEvidenceSnapshotRepository.class);
    private final ContractPaymentRepository contractPaymentRepository = org.mockito.Mockito.mock(ContractPaymentRepository.class);
    private final ContractRepository contractRepository = org.mockito.Mockito.mock(ContractRepository.class);
    private final ContractSpecificationRepository contractSpecificationRepository = org.mockito.Mockito.mock(ContractSpecificationRepository.class);
    private final UUID contractId = UUID.randomUUID();

    private final List<AssetAcceptance> acceptances = new ArrayList<>();
    private final List<AssetAcceptanceLine> acceptanceLines = new ArrayList<>();
    private final List<AssetAcceptanceUnit> acceptanceUnits = new ArrayList<>();
    private final List<FixedAsset> fixedAssets = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();
    private final List<AssetNumberRegistry> registryRows = new ArrayList<>();
    private final List<AssetHandover> handovers = new ArrayList<>();
    private final List<AssetHandoverLine> handoverLines = new ArrayList<>();
    private final List<AssetAllocation> allocations = new ArrayList<>();
    private final List<AssetVerification> verifications = new ArrayList<>();
    private final List<AssetVerificationLine> verificationLines = new ArrayList<>();
    private final List<IdempotencyRecord> idempotencyRows = new ArrayList<>();

    private AssetAcceptanceService acceptanceService;
    private AssetHandoverService handoverService;
    private FixedAssetService fixedAssetService;
    private AssetVerificationService verificationService;
    private IdempotencyService idempotencyService;
    private UUID companyId;
    private UUID purchaseOrderId;
    private UUID purchaseOrderItemId;
    private UUID materialId;
    private UUID warehouseId;
    private UUID storageLocationId;
    private UUID receiverId;
    private Employee employee;
    private Department department;
    private PurchaseOrderItem purchaseOrderItem;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("fixed-asset-wave2-scenario");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        purchaseOrderId = UUID.randomUUID();
        purchaseOrderItemId = UUID.randomUUID();
        materialId = UUID.randomUUID();
        warehouseId = UUID.randomUUID();
        storageLocationId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        employee = employee(UUID.randomUUID());
        department = department(UUID.randomUUID());
        purchaseOrderItem = purchaseOrderItem("5");
        idempotencyService = new IdempotencyService(idempotencyRepository, new ObjectMapper().findAndRegisterModules());
        Contract contract = new Contract(); contract.setId(contractId); contract.setTenantId("fixed-asset-wave2-scenario");
        contract.setCompanyId(companyId); contract.setStatus("ACTIVE");
        lenient().when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        ContractSpecification spec = new ContractSpecification();
        spec.setId(UUID.randomUUID());
        spec.setTenantId("fixed-asset-wave2-scenario");
        spec.setCompanyId(companyId);
        spec.setContractId(contractId);
        spec.setLineType(ProcurementLineType.MATERIAL);
        spec.setMaterialId(materialId);
        lenient().when(contractSpecificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse("fixed-asset-wave2-scenario", contractId))
            .thenReturn(List.of(spec));
        lenient().when(contractSpecificationRepository.findByIdAndIsDeletedFalse(any())).thenReturn(Optional.of(spec));

        acceptanceService = new AssetAcceptanceService(
            acceptanceRepository,
            acceptanceLineRepository,
            acceptanceUnitRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            purchaseOrderRepository,
            purchaseOrderItemRepository,
            fixedAssetRepository,
            eventRepository,
            settingsService,
            storageLocationRepository,
            employeeRepository,
            approvalEngineService,
            idempotencyService,
            evidenceSnapshotRepository,
            contractPaymentRepository,
            contractRepository,
            contractSpecificationRepository,
            org.mockito.Mockito.mock(com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService.class)
        );
        ReflectionTestUtils.setField(acceptanceService, "fixedAssetsEnabled", true);
        handoverService = new AssetHandoverService(
            handoverRepository,
            handoverLineRepository,
            allocationRepository,
            fixedAssetRepository,
            eventRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            approvalEngineService,
            idempotencyService,
            contractRepository,
            contractSpecificationRepository,
            org.mockito.Mockito.mock(RoleDataScopeService.class)
        );
        fixedAssetService = new FixedAssetService(
            fixedAssetRepository,
            eventRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            numberRegistryRepository,
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
        ReflectionTestUtils.setField(acceptanceService, "fixedAssetService", fixedAssetService);
        verificationService = new AssetVerificationService(
            verificationRepository,
            verificationLineRepository,
            fixedAssetRepository,
            new AssetFoundationMapper(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class)),
            idempotencyService
        );

        stubAcceptancePersistence();
        stubFixedAssetPersistence();
        stubHandoverPersistence();
        stubVerificationPersistence();
        stubIdempotencyPersistence();
        stubReferenceData();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void canonicalAssetMovesFromPostedAcceptanceThroughCustodyCommissioningReturnAndVerification() {
        acceptanceService.create(acceptanceCreateRequest("5", 5));
        AssetAcceptance acceptance = acceptances.getFirst();
        acceptanceService.recordQualityDecision(
            acceptance.getId(),
            new AssetAcceptanceQualityDecisionRequest(
                acceptance.getVersion(),
                acceptanceUnits.stream()
                    .map(unit -> new AssetAcceptanceQualityDecisionRequest.UnitDecision(unit.getId(), "ACCEPTED", "accepted"))
                    .toList()
            )
        );
        acceptanceService.markSubmittedFromApproval(acceptance.getId());
        acceptanceService.markApprovedFromApproval(acceptance.getId());
        acceptanceService.post(acceptance.getId(), new AssetAcceptancePostRequest(acceptance.getVersion(), false, "post"));
        List<FixedAsset> issuedAssets = List.copyOf(fixedAssets.subList(0, 2));

        handoverService.create(new AssetHandoverCreateRequest(
            "ISSUE", "WAREHOUSE", null, null, warehouseId,
            "DEPARTMENT", employee.getId(), department.getId(), null,
            issuedAssets.stream().map(asset -> new AssetHandoverLineRequest(asset.getId())).toList(), contractId
        ));
        AssetHandover handover = handovers.getFirst();
        handoverService.markSubmittedFromApproval(handover.getId());
        handoverService.markApprovedFromApproval(handover.getId());
        handoverService.dispatch(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "dispatch"));
        assertThat(allocations).hasSize(2).allSatisfy(allocation ->
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.ACTIVE));
        handoverService.acknowledge(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "ack"));

        LocalDate commissioningDate = LocalDate.of(2026, 9, 9);
        fixedAssetService.bulkCommission(new FixedAssetBulkCommissionRequest(List.of(
            bulkCommissionRow(issuedAssets.get(0), "FA-E2E-001", commissioningDate),
            bulkCommissionRow(issuedAssets.get(1), "FA-E2E-002", commissioningDate)
        )));
        FixedAsset returnedAsset = issuedAssets.get(0);
        UUID returnedIdentity = returnedAsset.getId();
        String returnedInventoryNumber = returnedAsset.getInventoryNumber();
        LocalDate returnedFirstCommissionedOn = returnedAsset.getFirstCommissionedOn();

        handoverService.create(new AssetHandoverCreateRequest(
            "RETURN_TO_WAREHOUSE", "DEPARTMENT", employee.getId(), department.getId(), null,
            "WAREHOUSE", null, null, warehouseId,
            List.of(new AssetHandoverLineRequest(returnedAsset.getId()))
        ));
        AssetHandover returnHandover = handovers.get(1);
        handoverService.markSubmittedFromApproval(returnHandover.getId());
        handoverService.markApprovedFromApproval(returnHandover.getId());
        handoverService.dispatch(returnHandover.getId(), new VersionedAssetCommand(returnHandover.getVersion(), "return dispatch"));
        handoverService.acknowledge(returnHandover.getId(), new VersionedAssetCommand(returnHandover.getVersion(), "return ack"));

        verificationService.create(new AssetVerificationCreateRequest(null, department.getId(), "DEPARTMENT"));
        AssetVerification verification = verifications.getFirst();
        FixedAsset departmentAsset = issuedAssets.get(1);
        verificationService.scan(verification.getId(), new AssetVerificationScanRequest(
            verification.getVersion(), departmentAsset.getId(), null, "DEPARTMENT", employee.getId(), department.getId(), null
        ));

        assertThat(fixedAssets).hasSize(5);
        assertThat(fixedAssets)
            .filteredOn(asset -> asset.getCustodyKind() == FixedAssetCustodyKind.WAREHOUSE && warehouseId.equals(asset.getWarehouseId()))
            .hasSize(4);
        assertThat(fixedAssets)
            .filteredOn(asset -> asset.getCustodyKind() == FixedAssetCustodyKind.DEPARTMENT)
            .singleElement()
            .satisfies(asset -> {
                assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
                assertThat(asset.getId()).isEqualTo(departmentAsset.getId());
            });
        assertThat(returnedAsset.getId()).isEqualTo(returnedIdentity);
        assertThat(returnedAsset.getInventoryNumber()).isEqualTo(returnedInventoryNumber);
        assertThat(returnedAsset.getFirstCommissionedOn()).isEqualTo(returnedFirstCommissionedOn);
        assertThat(returnedAsset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        assertThat(returnedAsset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.WAREHOUSE);
        assertThat(returnedAsset.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(acceptance.getApprovalStatus()).isEqualTo(AssetAcceptanceApprovalStatus.APPROVED);
        assertThat(acceptance.getPostingStatus()).isEqualTo(AssetAcceptancePostingStatus.POSTED);
        assertThat(purchaseOrderItem.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(allocations).hasSize(3).allSatisfy(allocation ->
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.RELEASED));
        assertThat(verificationLines).singleElement().satisfies(line -> {
            assertThat(line.getObservedCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
            assertThat(line.getResolutionStatus()).isEqualTo(AssetVerificationResolutionStatus.ACCEPTED);
            assertThat(line.getDiscrepancyType()).isNull();
        });
        assertThat(events).extracting(AssetEvent::getEventType).contains(
            "ASSET_ACCEPTANCE_POSTED",
            "ASSET_HANDOVER_DISPATCHED",
            "ASSET_HANDOVER_ACKNOWLEDGED",
            "ASSET_INVENTORY_NUMBER_ASSIGNED",
            "ASSET_COMMISSIONED"
        );
        assertThat(events).extracting(AssetEvent::getEventType)
            .noneMatch(eventType -> eventType.contains("STOCK") || eventType.contains("GOODS_RECEIPT"));
    }

    @Test
    void partialQualityRequiresHeldUnitResolutionBeforePostingAndDoesNotDuplicateOnRetry() {
        acceptanceService.create(acceptanceCreateRequest("5", 5));
        AssetAcceptance acceptance = acceptances.getFirst();
        List<AssetAcceptanceUnit> originalUnits = List.copyOf(acceptanceUnits);
        acceptanceService.recordQualityDecision(
            acceptance.getId(),
            new AssetAcceptanceQualityDecisionRequest(
                acceptance.getVersion(),
                List.of(
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(originalUnits.get(0).getId(), "ACCEPTED", "accepted"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(originalUnits.get(1).getId(), "ACCEPTED", "accepted"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(originalUnits.get(2).getId(), "ACCEPTED", "accepted"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(originalUnits.get(3).getId(), "HOLD", "inspect"),
                    new AssetAcceptanceQualityDecisionRequest.UnitDecision(originalUnits.get(4).getId(), "REJECTED", "damaged")
                )
            )
        );
        acceptanceService.markSubmittedFromApproval(acceptance.getId());
        acceptanceService.markApprovedFromApproval(acceptance.getId());
        AssetAcceptancePostRequest firstPost = new AssetAcceptancePostRequest(acceptance.getVersion(), false, "partial");
        assertThatThrownBy(() -> acceptanceService.post(acceptance.getId(), firstPost, "partial-post-1"))
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey())
                .isEqualTo("ASSET_ACCEPTANCE_UNRESOLVED_UNITS"));

        assertThat(fixedAssets).isEmpty();
        assertThat(purchaseOrderItem.getReceivedQuantity()).isEqualByComparingTo("0");
        assertThat(purchaseOrderItem.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(purchaseOrderItem.getRejectedQuantity()).isEqualByComparingTo("0");
        assertThat(acceptanceLines.getFirst().getAcceptedQuantity()).isEqualByComparingTo("3");
        assertThat(acceptanceLines.getFirst().getHoldQuantity()).isEqualByComparingTo("1");
        assertThat(acceptanceLines.getFirst().getRejectedQuantity()).isEqualByComparingTo("1");
        assertThat(acceptanceUnits)
            .filteredOn(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.ACCEPTED)
            .hasSize(3)
            .allSatisfy(unit -> assertThat(unit.getPostedFixedAssetId()).isNull());
        assertThat(acceptanceUnits)
            .filteredOn(unit -> unit.getQualityDecision() == AssetAcceptanceUnitQualityDecision.HOLD)
            .singleElement()
            .satisfies(unit -> assertThat(unit.getPostedFixedAssetId()).isNull());

        UUID heldUnitId = originalUnits.get(3).getId();
        acceptance.setPostingStatus(AssetAcceptancePostingStatus.NOT_POSTED);
        acceptanceService.recordQualityDecision(
            acceptance.getId(),
            new AssetAcceptanceQualityDecisionRequest(
                acceptance.getVersion(),
                List.of(new AssetAcceptanceQualityDecisionRequest.UnitDecision(heldUnitId, "ACCEPTED", "held cleared"))
            )
        );
        AssetAcceptancePostRequest secondPost = new AssetAcceptancePostRequest(acceptance.getVersion(), false, "held cleared");
        acceptanceService.post(acceptance.getId(), secondPost, "partial-post-2");
        int eventCountAfterHeldPost = events.size();
        acceptanceService.post(acceptance.getId(), secondPost, "partial-post-2");

        assertThat(fixedAssets).hasSize(4);
        assertThat(fixedAssets).allSatisfy(asset -> {
            assertThat(asset.getInventoryNumber()).startsWith("FA-");
            assertThat(asset.getSerialNumber()).startsWith("MSN-");
        });
        assertThat(purchaseOrderItem.getReceivedQuantity()).isEqualByComparingTo("5");
        assertThat(purchaseOrderItem.getAcceptedQuantity()).isEqualByComparingTo("4");
        assertThat(purchaseOrderItem.getRejectedQuantity()).isEqualByComparingTo("1");
        assertThat(fixedAssets).extracting(FixedAsset::getSourceAcceptanceUnitId).doesNotHaveDuplicates();
        assertThat(events).hasSize(eventCountAfterHeldPost);
        assertThat(events).extracting(AssetEvent::getEventType)
            .contains("ASSET_ACCEPTANCE_POSTED", "ASSET_INVENTORY_NUMBER_ASSIGNED");
        assertThat(events).extracting(AssetEvent::getEventType)
            .noneMatch(eventType -> eventType.contains("STOCK") || eventType.contains("GOODS_RECEIPT"));
    }

    private void stubAcceptancePersistence() {
        lenient().when(acceptanceRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> find(acceptances, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        lenient().when(acceptanceRepository.save(any(AssetAcceptance.class)))
            .thenAnswer(invocation -> save(acceptances, invocation.getArgument(0)));
        lenient().when(acceptanceLineRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> acceptanceLines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetAcceptanceId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(acceptanceLineRepository.save(any(AssetAcceptanceLine.class)))
            .thenAnswer(invocation -> save(acceptanceLines, invocation.getArgument(0)));
        lenient().when(acceptanceUnitRepository.findByTenantIdAndCompanyIdAndAssetAcceptanceLineIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> acceptanceUnits.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetAcceptanceLineId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(acceptanceUnitRepository.save(any(AssetAcceptanceUnit.class)))
            .thenAnswer(invocation -> save(acceptanceUnits, invocation.getArgument(0)));
        lenient().when(purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder()));
        lenient().when(purchaseOrderItemRepository.findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), purchaseOrderId
        )).thenReturn(List.of(purchaseOrderItem));
        lenient().when(purchaseOrderItemRepository.save(any(PurchaseOrderItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(acceptanceLineRepository.sumDeliveredQuantityByPurchaseOrderItem(any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private void stubFixedAssetPersistence() {
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class)))
            .thenAnswer(invocation -> save(fixedAssets, invocation.getArgument(0)));
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> find(fixedAssets, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> fixedAssets.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getWarehouseId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndDepartmentIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> fixedAssets.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getDepartmentId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> fixedAssets.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getInventoryNumber()))
                .filter(row -> !row.isDeleted())
                .findFirst());
        lenient().when(fixedAssetRepository.existsByTenantIdAndCompanyIdAndSourceAcceptanceUnitIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> fixedAssets.stream()
                .anyMatch(row -> invocation.getArgument(0).equals(row.getTenantId())
                    && invocation.getArgument(1).equals(row.getCompanyId())
                    && invocation.getArgument(2).equals(row.getSourceAcceptanceUnitId())
                    && !row.isDeleted()));
        lenient().when(eventRepository.save(any(AssetEvent.class)))
            .thenAnswer(invocation -> save(events, invocation.getArgument(0)));
        lenient().when(numberRegistryRepository.findByTenantIdAndCompanyIdAndNormalizedNumberAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> registryRows.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getNormalizedNumber()))
                .filter(row -> !row.isDeleted())
                .findFirst());
        lenient().when(numberRegistryRepository.save(any(AssetNumberRegistry.class)))
            .thenAnswer(invocation -> save(registryRows, invocation.getArgument(0)));
    }

    private void stubHandoverPersistence() {
        lenient().when(handoverRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> find(handovers, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        lenient().when(handoverRepository.save(any(AssetHandover.class)))
            .thenAnswer(invocation -> save(handovers, invocation.getArgument(0)));
        lenient().when(handoverLineRepository.findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> handoverLines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetHandoverId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(handoverLineRepository.save(any(AssetHandoverLine.class)))
            .thenAnswer(invocation -> save(handoverLines, invocation.getArgument(0)));
        lenient().when(allocationRepository.existsByTenantIdAndCompanyIdAndFixedAssetIdAndStatusAndIsDeletedFalse(any(), any(), any(), any()))
            .thenAnswer(invocation -> allocations.stream()
                .anyMatch(row -> invocation.getArgument(0).equals(row.getTenantId())
                    && invocation.getArgument(1).equals(row.getCompanyId())
                    && invocation.getArgument(2).equals(row.getFixedAssetId())
                    && invocation.getArgument(3).equals(row.getStatus())
                    && !row.isDeleted()));
        lenient().when(allocationRepository.findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> allocations.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetHandoverId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(allocationRepository.save(any(AssetAllocation.class)))
            .thenAnswer(invocation -> save(allocations, invocation.getArgument(0)));
    }

    private void stubVerificationPersistence() {
        lenient().when(verificationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> find(verifications, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        lenient().when(verificationRepository.save(any(AssetVerification.class)))
            .thenAnswer(invocation -> save(verifications, invocation.getArgument(0)));
        lenient().when(verificationLineRepository.findByTenantIdAndCompanyIdAndAssetVerificationIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> verificationLines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetVerificationId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(verificationLineRepository.save(any(AssetVerificationLine.class)))
            .thenAnswer(invocation -> save(verificationLines, invocation.getArgument(0)));
    }

    private void stubIdempotencyPersistence() {
        lenient().when(idempotencyRepository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            any(), any(), any(), any(), any()
        )).thenAnswer(invocation -> idempotencyRows.stream()
            .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
            .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
            .filter(row -> invocation.getArgument(2).equals(row.getOperation()))
            .filter(row -> invocation.getArgument(3).equals(row.getResourceId()))
            .filter(row -> invocation.getArgument(4).equals(row.getIdempotencyKey()))
            .filter(row -> !row.isDeleted())
            .findFirst());
        lenient().when(idempotencyRepository.save(any(IdempotencyRecord.class)))
            .thenAnswer(invocation -> save(idempotencyRows, invocation.getArgument(0)));
    }

    private void stubReferenceData() {
        AssetManagementSettings settings = new AssetManagementSettings();
        settings.setCentralWarehouseId(warehouseId);
        settings.setCentralStorageLocationId(storageLocationId);
        settings.setInventoryNumberPrefix("FA");
        settings.setInventoryNumberNextValue(1L);
        settings.setInventoryNumberPadding(6);
        lenient().when(settingsService.requireConfigured()).thenReturn(settings);
        lenient().when(settingsRepository.findByTenantIdAndCompanyIdForUpdate(TenantContext.currentTenant(), companyId))
            .thenReturn(Optional.of(settings));
        lenient().when(settingsRepository.save(any(AssetManagementSettings.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        StorageLocation location = new StorageLocation();
        location.setId(storageLocationId);
        location.setTenantId(TenantContext.currentTenant());
        location.setCompanyId(companyId);
        location.setWarehouseId(warehouseId);
        lenient().when(storageLocationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, storageLocationId
        )).thenReturn(Optional.of(location));
        lenient().when(employeeRepository.findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of(employee));
        lenient().when(employeeRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Optional.ofNullable(invocation.getArgument(2).equals(employee.getId()) ? employee : null));
        lenient().when(departmentRepository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> Optional.ofNullable(invocation.getArgument(0).equals(department.getId()) ? department : null));
        lenient().when(attachmentRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> attachments.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
    }

    private AssetAcceptanceCreateRequest acceptanceCreateRequest(String quantity, int unitCount) {
        List<AssetAcceptanceUnitRequest> units = new ArrayList<>();
        for (int index = 1; index <= unitCount; index++) {
            units.add(new AssetAcceptanceUnitRequest(
                index,
                "MSN-" + index,
                "FA-RCV-W2-" + index,
                "SN-" + index,
                "TAG-" + index,
                "NEW"
            ));
        }
        return new AssetAcceptanceCreateRequest(
            null, purchaseOrderId, null, null, "DEL-1", warehouseId, storageLocationId, receiverId,
            LocalDate.of(2026, 9, 9), LocalDateTime.of(2026, 9, 9, 10, 0), "UZS", null,
            "delivery note", List.of(new AssetAcceptanceLineRequest(
            purchaseOrderItemId, materialId, "FIXED_ASSET", "PCS", new BigDecimal(quantity), units
        )));
    }

    private FixedAssetBulkCommissionRequest.Row bulkCommissionRow(
        FixedAsset asset,
        String inventoryNumber,
        LocalDate commissioningDate
    ) {
        return new FixedAssetBulkCommissionRequest.Row(
            asset.getId(),
            asset.getVersion(),
            inventoryNumber,
            employee.getId(),
            department.getId(),
            "Line A",
            commissioningDate,
            List.of(assetEvidence(asset).getId().toString()),
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
        attachment.setUploadedAt(LocalDateTime.now());
        attachments.add(attachment);
        return attachment;
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(purchaseOrderId);
        order.setTenantId(TenantContext.currentTenant());
        order.setCompanyId(companyId);
        order.setCode("PO-ASSET");
        order.setName("Fixed asset PO");
        order.setStatus(PurchaseOrderStatus.SENT.name());
        order.setSupplierId(UUID.randomUUID());
        order.setRequestType(RequestType.GOODS);
        order.setCurrencyCode("UZS");
        order.setExchangeRate(BigDecimal.ONE);
        order.setVersion(0L);
        return order;
    }

    private PurchaseOrderItem purchaseOrderItem(String quantity) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(purchaseOrderItemId);
        item.setTenantId(TenantContext.currentTenant());
        item.setCompanyId(companyId);
        item.setPurchaseOrderId(purchaseOrderId);
        item.setCode("10");
        item.setName("Laptop asset");
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterialTypeSnapshot(MaterialType.FIXED_ASSET);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("PCS");
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setReceivedQuantity(BigDecimal.ZERO);
        item.setAcceptedQuantity(BigDecimal.ZERO);
        item.setRejectedQuantity(BigDecimal.ZERO);
        item.setDamagedQuantity(BigDecimal.ZERO);
        return item;
    }

    private Employee employee(UUID id) {
        Employee value = new Employee();
        value.setId(id);
        value.setTenantId(TenantContext.currentTenant());
        value.setCompanyId(companyId);
        value.setCode("EMP-" + id.toString().substring(0, 8));
        value.setName("Employee");
        value.setStatus("ACTIVE");
        value.setVersion(0L);
        value.setEmploymentStatus(EmployeeStatus.ACTIVE);
        return value;
    }

    private Department department(UUID id) {
        Department value = new Department();
        value.setId(id);
        value.setTenantId(TenantContext.currentTenant());
        value.setCompanyId(companyId);
        value.setCode("DEP-" + id.toString().substring(0, 8));
        value.setName("Department");
        value.setStatus("ACTIVE");
        value.setVersion(0L);
        return value;
    }

    private <T extends BusinessEntity> Optional<T> find(List<T> rows, String tenantId, UUID companyId, UUID id) {
        return rows.stream()
            .filter(row -> tenantId.equals(row.getTenantId()))
            .filter(row -> companyId.equals(row.getCompanyId()))
            .filter(row -> id.equals(row.getId()))
            .filter(row -> !row.isDeleted())
            .findFirst();
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
        if (row.getStatus() == null) {
            row.setStatus("ACTIVE");
        }
        if (row instanceof AssetNumberRegistry registry && registry.getRegistryStatus() == null) {
            registry.setRegistryStatus(AssetNumberRegistryStatus.ASSIGNED);
        }
        if (row instanceof AssetHandover handover && handover.getHandoverStatus() == null) {
            handover.setHandoverStatus(AssetHandoverStatus.DRAFT);
        }
        if (row instanceof AssetAcceptance acceptance && acceptance.getApprovalStatus() == null) {
            acceptance.setApprovalStatus(AssetAcceptanceApprovalStatus.DRAFT);
            acceptance.setPostingStatus(AssetAcceptancePostingStatus.NOT_POSTED);
        }
        if (row instanceof AssetVerification verification && verification.getVerificationStatus() == null) {
            verification.setVerificationStatus(AssetVerificationStatus.IN_PROGRESS);
        }
        rows.removeIf(existing -> existing.getId().equals(row.getId()));
        rows.add(row);
        return row;
    }
}
