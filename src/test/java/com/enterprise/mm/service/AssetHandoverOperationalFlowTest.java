package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AssetAllocation;
import com.enterprise.mm.entity.AssetEvent;
import com.enterprise.mm.entity.AssetHandover;
import com.enterprise.mm.entity.AssetHandoverLine;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.AssetAllocationStatus;
import com.enterprise.mm.enums.AssetHandoverLineStatus;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.AssetFoundationMapper;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalInstanceResponse;
import com.enterprise.mm.payload.AssetHandoverCreateRequest;
import com.enterprise.mm.payload.AssetHandoverLineRequest;
import com.enterprise.mm.payload.AssetHandoverSearchRequest;
import com.enterprise.mm.payload.VersionedAssetCommand;
import com.enterprise.mm.repository.AssetAllocationRepository;
import com.enterprise.mm.repository.AssetEventRepository;
import com.enterprise.mm.repository.AssetHandoverLineRepository;
import com.enterprise.mm.repository.AssetHandoverRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class AssetHandoverOperationalFlowTest {
    private final AssetHandoverRepository handoverRepository = org.mockito.Mockito.mock(AssetHandoverRepository.class);
    private final AssetHandoverLineRepository lineRepository = org.mockito.Mockito.mock(AssetHandoverLineRepository.class);
    private final AssetAllocationRepository allocationRepository = org.mockito.Mockito.mock(AssetAllocationRepository.class);
    private final FixedAssetRepository fixedAssetRepository = org.mockito.Mockito.mock(FixedAssetRepository.class);
    private final AssetEventRepository eventRepository = org.mockito.Mockito.mock(AssetEventRepository.class);
    private final ApprovalEngineService approvalEngineService = org.mockito.Mockito.mock(ApprovalEngineService.class);
    private final IdempotencyService idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    private final ContractRepository contractRepository = org.mockito.Mockito.mock(ContractRepository.class);
    private final ContractSpecificationRepository contractSpecificationRepository = org.mockito.Mockito.mock(ContractSpecificationRepository.class);
    private final UUID contractId = UUID.randomUUID();
    private final AssetIntegrationEventPublisher assetIntegrationEventPublisher = org.mockito.Mockito.mock(AssetIntegrationEventPublisher.class);

    private final List<AssetHandover> handovers = new ArrayList<>();
    private final List<AssetHandoverLine> lines = new ArrayList<>();
    private final List<AssetAllocation> allocations = new ArrayList<>();
    private final List<AssetEvent> events = new ArrayList<>();
    private AssetHandoverService service;
    private UUID companyId;
    private UUID assetId;
    private FixedAsset asset;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("asset-handover-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        assetId = UUID.randomUUID();
        asset = warehouseAsset(assetId);
        service = new AssetHandoverService(
            handoverRepository,
            lineRepository,
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
        Contract contract = new Contract(); contract.setId(contractId); contract.setTenantId("asset-handover-test");
        contract.setCompanyId(companyId); contract.setStatus("ACTIVE");
        lenient().when(contractRepository.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        ContractSpecification spec = new ContractSpecification();
        spec.setId(UUID.randomUUID());
        spec.setTenantId("asset-handover-test");
        spec.setCompanyId(companyId);
        spec.setContractId(contractId);
        spec.setLineType(ProcurementLineType.MATERIAL);
        spec.setMaterialId(asset.getMaterialId());
        lenient().when(contractSpecificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse("asset-handover-test", contractId))
            .thenReturn(List.of(spec));
        ReflectionTestUtils.setField(service, "assetIntegrationEventPublisher", assetIntegrationEventPublisher);

        lenient().when(handoverRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> handovers.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getId()))
                .findFirst());
        lenient().when(handoverRepository.save(any(AssetHandover.class))).thenAnswer(invocation -> save(handoverRepositoryRows(), invocation.getArgument(0)));
        lenient().when(lineRepository.findByTenantIdAndCompanyIdAndAssetHandoverIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> lines.stream()
                .filter(row -> invocation.getArgument(0).equals(row.getTenantId()))
                .filter(row -> invocation.getArgument(1).equals(row.getCompanyId()))
                .filter(row -> invocation.getArgument(2).equals(row.getAssetHandoverId()))
                .filter(row -> !row.isDeleted())
                .toList());
        lenient().when(lineRepository.save(any(AssetHandoverLine.class))).thenAnswer(invocation -> save(lineRepositoryRows(), invocation.getArgument(0)));
        lenient().when(allocationRepository.existsByTenantIdAndCompanyIdAndFixedAssetIdAndStatusAndIsDeletedFalse(
            any(), any(), any(), any()
        )).thenAnswer(invocation -> allocations.stream()
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
        lenient().when(allocationRepository.save(any(AssetAllocation.class))).thenAnswer(invocation -> save(allocationRepositoryRows(), invocation.getArgument(0)));
        lenient().when(fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, assetId
        )).thenReturn(Optional.of(asset));
        lenient().when(fixedAssetRepository.save(any(FixedAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(eventRepository.save(any(AssetEvent.class))).thenAnswer(invocation -> save(eventRepositoryRows(), invocation.getArgument(0)));
        UUID approvalId = UUID.randomUUID();
        lenient().when(approvalEngineService.start(AssetHandoverService.APPROVAL_MODULE, null)).thenReturn(null);
        lenient().when(approvalEngineService.start(any(), any())).thenReturn(new ApprovalInstanceResponse(
            approvalId, UUID.randomUUID(), "Asset handover approval", AssetHandoverService.APPROVAL_MODULE,
            null, "Asset handover", ApprovalInstanceStatus.PENDING, 1, null, null, List.of()
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void warehouseIssueRequiresAnActiveContractAndStoresIt() {
        var request = new AssetHandoverCreateRequest("ISSUE", "WAREHOUSE", null, null,
            asset.getWarehouseId(), "DEPARTMENT", null, UUID.randomUUID(), null,
            List.of(new AssetHandoverLineRequest(assetId)));
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("Contract is required");
        assertThat(handovers).isEmpty();
        AssetHandover handover = createIssue();
        assertThat(handover.getContractId()).isEqualTo(contractId);
        assertThat(service.get(handover.getId()).getContractId()).isEqualTo(contractId);
    }

    @Test
    void issueSearchScopesTheSelectedWarehouse() {
        UUID warehouseId = UUID.randomUUID();
        when(handoverRepository.search(
            eq(TenantContext.currentTenant()), eq(companyId), eq(null),
            eq(com.enterprise.mm.enums.AssetHandoverType.ISSUE), eq(null), eq(warehouseId), eq(false), eq(null), eq(java.util.Set.of()), eq(true), any()
        )).thenReturn(Page.empty());

        service.search(new AssetHandoverSearchRequest(null, "ISSUE", null, warehouseId), PageRequest.of(0, 20));

        verify(handoverRepository).search(
            eq(TenantContext.currentTenant()), eq(companyId), eq(null),
            eq(com.enterprise.mm.enums.AssetHandoverType.ISSUE), eq(null), eq(warehouseId), eq(false), eq(null), eq(java.util.Set.of()), eq(true), any()
        );
    }

    @Test
    void submitDoesNotAllocateUntilApprovalCallback() {
        AssetHandover handover = createIssue();

        var submitted = service.submit(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "approval"));

        assertThat(submitted.getStatus()).isEqualTo("SUBMITTED");
        assertThat(submitted.getApprovalInstanceId()).isNotNull();
        assertThat(allocations).isEmpty();
    }

    @Test
    void submitRechecksContractAfterDraftWasCreated() {
        AssetHandover handover = createIssue();
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(contractId).orElseThrow();
        contract.setStatus("DRAFT");

        assertThatThrownBy(() -> service.submit(handover.getId(),
            new VersionedAssetCommand(handover.getVersion(), "approval")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Contract must be active");
        org.mockito.Mockito.verifyNoInteractions(approvalEngineService);
    }

    @Test
    void approvalCreatesActiveAllocationAndPreventsAnotherHandoverForSameAsset() {
        AssetHandover handover = createIssue();
        service.markSubmittedFromApproval(handover.getId());
        service.markApprovedFromApproval(handover.getId());

        assertThat(handover.getStatus()).isEqualTo("APPROVED");
        assertThat(allocations).singleElement().satisfies(allocation -> {
            assertThat(allocation.getFixedAssetId()).isEqualTo(assetId);
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.ACTIVE);
            assertThat(allocation.getReleasedAt()).isNull();
        });
        assertThat(lines).singleElement().satisfies(line -> assertThat(line.getAssetAllocationId()).isNotNull());

        assertThatThrownBy(this::createIssue)
            .isInstanceOf(BusinessException.class)
            .satisfies(error -> assertThat(((BusinessException) error).getMessageKey()).isEqualTo("ASSET_ALREADY_ALLOCATED"));
    }

    @Test
    void dispatchKeepsAllocationActiveAndMovesAssetInTransit() {
        AssetHandover handover = approvedIssue();

        service.dispatch(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "dispatch"));

        assertThat(handover.getStatus()).isEqualTo("DISPATCHED");
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.IN_TRANSIT);
        assertThat(asset.getActiveTransferId()).isEqualTo(handover.getId());
        assertThat(allocations).singleElement().satisfies(allocation -> {
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.ACTIVE);
            assertThat(allocation.getReleasedAt()).isNull();
        });
        assertThat(lines).singleElement().satisfies(line -> assertThat(line.getLineStatus()).isEqualTo(AssetHandoverLineStatus.DISPATCHED));
    }

    @Test
    void acknowledgeUpdatesDestinationCustodyAndOnlyThenReleasesAllocation() {
        AssetHandover handover = approvedIssue();
        service.dispatch(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "dispatch"));

        service.acknowledge(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "acknowledged"));

        assertThat(handover.getStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(asset.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING);
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
        assertThat(asset.getDepartmentId()).isEqualTo(handover.getToDepartmentId());
        assertThat(asset.getWarehouseId()).isNull();
        assertThat(asset.getActiveTransferId()).isNull();
        assertThat(allocations).singleElement().satisfies(allocation -> {
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.RELEASED);
            assertThat(allocation.getReleaseReason()).isEqualTo("ACKNOWLEDGED");
            assertThat(allocation.getReleasedAt()).isNotNull();
        });
    }

    @Test
    void exceptionReportedDoesNotReleaseAllocationOrMakeAssetAllocatable() {
        AssetHandover handover = approvedIssue();
        service.dispatch(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "dispatch"));

        service.reportException(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "seal mismatch"));

        assertThat(handover.getStatus()).isEqualTo("EXCEPTION_REPORTED");
        assertThat(asset.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.IN_TRANSIT);
        assertThat(allocations).singleElement().satisfies(allocation ->
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.ACTIVE));
    }

    @Test
    void cancelBeforeDispatchReleasesApprovalAllocation() {
        AssetHandover handover = approvedIssue();

        service.cancel(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "cancelled"));

        assertThat(handover.getStatus()).isEqualTo("CANCELLED");
        assertThat(allocations).singleElement().satisfies(allocation -> {
            assertThat(allocation.getAllocationStatus()).isEqualTo(AssetAllocationStatus.CANCELLED);
            assertThat(allocation.getReleasedAt()).isNotNull();
            assertThat(allocation.getReleaseReason()).isEqualTo("CANCELLED");
        });
    }

    @Test
    void returnToWarehouseAcknowledgementPublishesCanonicalReturnedEvent() {
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setWarehouseId(null);
        UUID fromDepartment = UUID.randomUUID();
        UUID toWarehouse = UUID.randomUUID();
        asset.setDepartmentId(fromDepartment);
        AssetHandover handover = createReturnToWarehouse(fromDepartment, toWarehouse);
        service.markSubmittedFromApproval(handover.getId());
        service.markApprovedFromApproval(handover.getId());
        service.dispatch(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "dispatch"));

        service.acknowledge(handover.getId(), new VersionedAssetCommand(handover.getVersion(), "warehouse return"));

        assertThat(events).extracting(AssetEvent::getEventType)
            .contains("ASSET_HANDOVER_DISPATCHED", "ASSET_RETURNED_TO_WAREHOUSE");
        verify(assetIntegrationEventPublisher).publish(
            eq(asset),
            eq("ASSET_RETURNED_TO_WAREHOUSE"),
            eq(FixedAssetLifecycleStatus.IN_USE),
            eq(FixedAssetCustodyKind.IN_TRANSIT),
            eq("ASSET_HANDOVER"),
            eq(handover.getId()),
            any(),
            eq("system"),
            eq(null)
        );
    }

    @Test
    void legacyUiHandoverTypeAliasesAreAcceptedAndPersistCanonicalTypes() {
        AssetHandover issue = createWarehouseToDepartmentAlias();
        assertThat(issue.getHandoverType().name()).isEqualTo("ISSUE");

        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
        asset.setWarehouseId(null);
        asset.setDepartmentId(UUID.randomUUID());
        handovers.clear();
        lines.clear();

        AssetHandover returnToWarehouse = createDepartmentToWarehouseAlias(asset.getDepartmentId(), UUID.randomUUID());
        assertThat(returnToWarehouse.getHandoverType().name()).isEqualTo("RETURN_TO_WAREHOUSE");
    }

    private AssetHandover approvedIssue() {
        AssetHandover handover = createIssue();
        service.markSubmittedFromApproval(handover.getId());
        service.markApprovedFromApproval(handover.getId());
        return handover;
    }

    private AssetHandover createIssue() {
        UUID departmentId = UUID.randomUUID();
        var response = service.create(new AssetHandoverCreateRequest(
            "ISSUE",
            "WAREHOUSE",
            null,
            null,
            asset.getWarehouseId(),
            "DEPARTMENT",
            null,
            departmentId,
            null,
            List.of(new AssetHandoverLineRequest(assetId)), contractId
        ));
        return handovers.stream()
            .filter(row -> row.getId().equals(response.getId()))
            .findFirst()
            .orElseThrow();
    }

    private AssetHandover createReturnToWarehouse(UUID fromDepartmentId, UUID toWarehouseId) {
        var response = service.create(new AssetHandoverCreateRequest(
            "RETURN_TO_WAREHOUSE",
            "DEPARTMENT",
            null,
            fromDepartmentId,
            null,
            "WAREHOUSE",
            null,
            null,
            toWarehouseId,
            List.of(new AssetHandoverLineRequest(assetId)), contractId
        ));
        return handovers.stream()
            .filter(row -> row.getId().equals(response.getId()))
            .findFirst()
            .orElseThrow();
    }

    private AssetHandover createWarehouseToDepartmentAlias() {
        UUID departmentId = UUID.randomUUID();
        var response = service.create(new AssetHandoverCreateRequest(
            "WAREHOUSE_TO_DEPARTMENT",
            "WAREHOUSE",
            null,
            null,
            asset.getWarehouseId(),
            "DEPARTMENT",
            null,
            departmentId,
            null,
            List.of(new AssetHandoverLineRequest(assetId)), contractId
        ));
        return handovers.stream()
            .filter(row -> row.getId().equals(response.getId()))
            .findFirst()
            .orElseThrow();
    }

    private AssetHandover createDepartmentToWarehouseAlias(UUID fromDepartmentId, UUID toWarehouseId) {
        var response = service.create(new AssetHandoverCreateRequest(
            "DEPARTMENT_TO_WAREHOUSE",
            "DEPARTMENT",
            null,
            fromDepartmentId,
            null,
            "WAREHOUSE",
            null,
            null,
            toWarehouseId,
            List.of(new AssetHandoverLineRequest(assetId))
        ));
        return handovers.stream()
            .filter(row -> row.getId().equals(response.getId()))
            .findFirst()
            .orElseThrow();
    }

    private FixedAsset warehouseAsset(UUID id) {
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
        asset.setWarehouseId(UUID.randomUUID());
        return asset;
    }

    private List<AssetHandover> handoverRepositoryRows() {
        return handovers;
    }

    private List<AssetHandoverLine> lineRepositoryRows() {
        return lines;
    }

    private List<AssetAllocation> allocationRepositoryRows() {
        return allocations;
    }

    private List<AssetEvent> eventRepositoryRows() {
        return events;
    }

    private <T extends BusinessEntity> T save(List<T> rows, T row) {
        assign(row);
        rows.removeIf(existing -> existing.getId().equals(row.getId()));
        rows.add(row);
        return row;
    }

    private <T extends BusinessEntity> void assign(T row) {
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
    }
}
