package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.controller.MaterialReservationController;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialReturnDocument;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.MaterialReturnStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.MaterialUnitStatus;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TransferStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.IssueRequestMapper;
import com.enterprise.mm.mapper.MaterialReturnMapper;
import com.enterprise.mm.mapper.TransferDocumentMapper;
import com.enterprise.mm.payload.IssueRequestIssueRequest;
import com.enterprise.mm.payload.MaterialReturnAcceptRequest;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentItemRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.TransferApproveRequest;
import com.enterprise.mm.payload.TransferDiscrepancyRequest;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialReturnDocumentRepository;
import com.enterprise.mm.repository.MaterialReturnLineRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;

@ExtendWith(MockitoExtension.class)
class WarehouseExecutionSafetyTest {
    @Mock private IssueRequestRepository issueRepository;
    @Mock private IssueRequestLineRepository issueLineRepository;
    private final IssueRequestMapper issueMapper = new IssueRequestMapper();
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private UserRepository userRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private MaterialCatalogGuard materialCatalogGuard;
    @Mock private MaterialReservationService reservationService;
    @Mock private StockMovementDocumentService stockMovementService;
    @Mock private StockBalanceRepository stockBalanceRepository;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer snapshotSerializer;
    @Mock private RoleDataScopeService roleDataScopeService;
    @Mock private MaterialUnitEligibilityService materialUnitEligibilityService;

    @Mock private TransferDocumentRepository transferRepository;
    @Mock private TransferDocumentLineRepository transferLineRepository;
    @Mock private TransferDocumentMapper transferMapper;

    @Mock private MaterialReturnDocumentRepository returnRepository;
    @Mock private MaterialReturnLineRepository returnLineRepository;
    @Mock private MaterialReturnMapper returnMapper;
    @Mock private StockBalanceMutationService stockBalanceMutationService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant("warehouse-execution-safety-test");
        CompanyContext.setCompany(companyId);
        org.mockito.Mockito.lenient()
            .when(roleDataScopeService.canAccessWarehouse(org.mockito.ArgumentMatchers.any(UUID.class)))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(roleDataScopeService.canAccessWarehouse(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class)
            ))
            .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void issueRejectsStaleDocumentVersionBeforePostingStock() {
        UUID id = UUID.randomUUID();
        IssueRequest entity = issueRequest(id, 7L);
        IssueRequestIssueRequest request = new IssueRequestIssueRequest();
        request.setWarehouseId(entity.getWarehouseId());
        request.setExpectedVersion(6L);
        when(issueRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(issueRepository.lockExecution(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));

        IssueRequestService service = issueService();

        assertThatThrownBy(() -> service.issue(id, request, "stale-request"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("version conflict");
        verify(stockMovementService, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void serializedIssueRequiresExactUnitSelectionBeforePostingStock() {
        UUID id = UUID.randomUUID();
        IssueRequest entity = issueRequest(id, 2L);
        IssueRequestLine line = issueLine(id, UUID.randomUUID(), BigDecimal.valueOf(2));
        IssueRequestIssueRequest request = issueRequest(entity, line, BigDecimal.valueOf(2), List.of());
        when(issueRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(issueRepository.lockExecution(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));
        when(issueLineRepository.findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), id
        )).thenReturn(List.of(line));
        when(materialRepository.findByIdAndIsDeletedFalse(line.getMaterialId()))
            .thenReturn(Optional.of(material(line.getMaterialId(), true)));
        when(materialUnitEligibilityService.lockAndValidate(
            org.mockito.ArgumentMatchers.eq(line.getMaterialId()),
            org.mockito.ArgumentMatchers.eq(entity.getWarehouseId()),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(2)),
            org.mockito.ArgumentMatchers.eq(List.of()),
            org.mockito.ArgumentMatchers.eq(MaterialUnitStatus.IN_STOCK)
        )).thenThrow(new BusinessException("SERIAL_UNIT_SELECTION_REQUIRED"));

        assertThatThrownBy(() -> issueService().issue(id, request, "issue-missing-units"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SERIAL_UNIT_SELECTION_REQUIRED");
        verify(stockMovementService, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void serializedIssueGroupsSelectedUnitsByBatchAndMarksIssuedAfterPosting() {
        UUID id = UUID.randomUUID();
        IssueRequest entity = issueRequest(id, 3L);
        UUID batchA = UUID.randomUUID();
        UUID batchB = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        IssueRequestLine line = issueLine(id, UUID.randomUUID(), BigDecimal.valueOf(3));
        MaterialUnit unitA1 = materialUnit(line.getMaterialId(), entity.getWarehouseId(), locationId, batchA);
        MaterialUnit unitA2 = materialUnit(line.getMaterialId(), entity.getWarehouseId(), locationId, batchA);
        MaterialUnit unitB1 = materialUnit(line.getMaterialId(), entity.getWarehouseId(), locationId, batchB);
        List<UUID> unitIds = List.of(unitA1.getId(), unitA2.getId(), unitB1.getId());
        IssueRequestIssueRequest request = issueRequest(entity, line, BigDecimal.valueOf(3), unitIds);
        request.setStorageLocationId(locationId);

        when(issueRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(issueRepository.lockExecution(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));
        when(issueLineRepository.findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), id
        )).thenReturn(List.of(line));
        when(materialRepository.findByIdAndIsDeletedFalse(line.getMaterialId()))
            .thenReturn(Optional.of(material(line.getMaterialId(), true)));
        when(materialUnitEligibilityService.lockAndValidate(
            org.mockito.ArgumentMatchers.eq(line.getMaterialId()),
            org.mockito.ArgumentMatchers.eq(entity.getWarehouseId()),
            org.mockito.ArgumentMatchers.eq(locationId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(BigDecimal.valueOf(3)),
            org.mockito.ArgumentMatchers.eq(unitIds),
            org.mockito.ArgumentMatchers.eq(MaterialUnitStatus.IN_STOCK)
        )).thenReturn(List.of(unitA1, unitA2, unitB1));
        when(stockBalanceRepository.findCompanyBalanceForUpdate(
            org.mockito.ArgumentMatchers.eq(TenantContext.currentTenant()),
            org.mockito.ArgumentMatchers.eq(companyId),
            org.mockito.ArgumentMatchers.eq(line.getMaterialId()),
            org.mockito.ArgumentMatchers.eq(entity.getWarehouseId()),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq(locationId),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.any(UUID.class)
        )).thenReturn(Optional.of(stockBalance(line.getMaterialId(), entity.getWarehouseId(), locationId, batchA)))
            .thenReturn(Optional.of(stockBalance(line.getMaterialId(), entity.getWarehouseId(), locationId, batchB)));

        UUID documentId = UUID.randomUUID();
        StockMovementDocumentResponse created = stockDocument(documentId, 1L, List.of());
        StockMovementDocumentResponse approved = stockDocument(documentId, 2L, List.of());
        StockMovementResponse postedA = postedMovement(batchA, locationId, BigDecimal.valueOf(2));
        StockMovementResponse postedB = postedMovement(batchB, locationId, BigDecimal.ONE);
        StockMovementDocumentResponse posted = stockDocument(documentId, 3L, List.of(postedA, postedB));
        when(stockMovementService.create(org.mockito.ArgumentMatchers.any())).thenReturn(created);
        when(stockMovementService.changeStatus(
            org.mockito.ArgumentMatchers.eq(documentId),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(approved);
        when(stockMovementService.postManagedIssue(
            org.mockito.ArgumentMatchers.eq(documentId),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString()
        )).thenReturn(posted);

        when(issueRepository.save(org.mockito.ArgumentMatchers.any(IssueRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        issueService().issue(id, request, "issue-serialized-units");

        org.mockito.ArgumentCaptor<StockMovementDocumentCreateRequest> documentCaptor =
            org.mockito.ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(stockMovementService).create(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getItems()).hasSize(2);
        assertThat(documentCaptor.getValue().getItems())
            .extracting(StockMovementDocumentItemRequest::getStockBatchId)
            .containsExactly(batchA, batchB);
        assertThat(documentCaptor.getValue().getItems())
            .extracting(StockMovementDocumentItemRequest::getQuantity)
            .containsExactly(BigDecimal.valueOf(2), BigDecimal.ONE);
        verify(materialUnitEligibilityService).markIssued(
            org.mockito.ArgumentMatchers.eq(List.of(unitA1, unitA2)),
            org.mockito.ArgumentMatchers.eq("IssueRequest"),
            org.mockito.ArgumentMatchers.eq(entity.getId()),
            org.mockito.ArgumentMatchers.eq(line.getId()),
            org.mockito.ArgumentMatchers.eq(entity.getWarehouseId()),
            org.mockito.ArgumentMatchers.eq(locationId),
            org.mockito.ArgumentMatchers.isNull()
        );
        verify(materialUnitEligibilityService).markIssued(
            org.mockito.ArgumentMatchers.eq(List.of(unitB1)),
            org.mockito.ArgumentMatchers.eq("IssueRequest"),
            org.mockito.ArgumentMatchers.eq(entity.getId()),
            org.mockito.ArgumentMatchers.eq(line.getId()),
            org.mockito.ArgumentMatchers.eq(entity.getWarehouseId()),
            org.mockito.ArgumentMatchers.eq(locationId),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void returnAcceptanceRejectsStaleDocumentVersionBeforePostingStock() {
        UUID id = UUID.randomUUID();
        MaterialReturnDocument entity = returnDocument(id, 4L);
        MaterialReturnAcceptRequest request = new MaterialReturnAcceptRequest();
        request.setExpectedVersion(3L);
        request.setLines(List.of());
        when(returnRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));

        MaterialReturnService service = returnService();

        assertThatThrownBy(() -> service.accept(id, request, null))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("version conflict");
        verify(stockMovementService, never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void transferCreatorCannotApproveTheirOwnDocument() {
        UUID id = UUID.randomUUID();
        TransferDocument entity = transfer(id, TransferStatus.SUBMITTED, 2L);
        entity.setCreatedBy("warehouse.manager");
        when(transferRepository.findScopedForUpdate(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("warehouse.manager", "n/a", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER")))
        );

        assertThatThrownBy(() -> com.enterprise.mm.service.approval.ApprovedOperationContext.run(
            com.enterprise.mm.service.approval.TransferApproveOperationHandler.MODULE_KEY,
            id,
            () -> transferService().approve(id, new TransferApproveRequest(2L))))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("own transfer");
        verify(transferRepository, never()).save(entity);
    }

    @Test
    void transferApprovalRejectsStaleVersionBeforeChangingStatus() {
        UUID id = UUID.randomUUID();
        TransferDocument entity = transfer(id, TransferStatus.SUBMITTED, 7L);
        entity.setCreatedBy("warehouse.creator");
        when(transferRepository.findScopedForUpdate(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("warehouse.approver", "n/a", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER")))
        );

        assertThatThrownBy(() -> com.enterprise.mm.service.approval.ApprovedOperationContext.run(
            com.enterprise.mm.service.approval.TransferApproveOperationHandler.MODULE_KEY,
            id,
            () -> transferService().approve(id, new TransferApproveRequest(6L))))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("version conflict");
        verify(transferRepository, never()).save(entity);
    }

    @Test
    void transferDiscrepancyRequiresEvidenceAndReason() {
        UUID id = UUID.randomUUID();
        TransferDocument entity = transfer(id, TransferStatus.IN_TRANSIT, 3L);
        TransferDocumentLine line = new TransferDocumentLine();
        line.setId(UUID.randomUUID());
        line.setInTransitQuantity(BigDecimal.TEN);
        line.setDamagedQuantity(BigDecimal.ZERO);
        line.setMissingQuantity(BigDecimal.ZERO);
        when(transferRepository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        when(transferLineRepository.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(), id
        )).thenReturn(List.of(line));

        var request = new TransferDiscrepancyRequest(
            3L,
            List.of(new TransferDiscrepancyRequest.Line(line.getId(), BigDecimal.ONE, BigDecimal.ZERO, " ", null))
        );

        assertThatThrownBy(() -> transferService().recordDiscrepancy(id, request))
            .isInstanceOf(com.enterprise.mm.error.BadRequestException.class)
            .hasMessageContaining("reason and evidence");
        verify(transferLineRepository, never()).save(line);
    }

    @Test
    void transferCannotCloseWhileDiscrepancyOrInTransitQuantityRemains() {
        UUID id = UUID.randomUUID();
        TransferDocument entity = transfer(id, TransferStatus.DISCREPANCY_REVIEW, 5L);
        TransferDocumentLine line = new TransferDocumentLine();
        line.setInTransitQuantity(BigDecimal.ONE);
        line.setDamagedQuantity(BigDecimal.ONE);
        line.setMissingQuantity(BigDecimal.ZERO);
        when(transferRepository.findScopedForUpdate(id, TenantContext.currentTenant(), companyId)).thenReturn(Optional.of(entity));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("warehouse-manager", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MM_MANAGER"))));

        assertThatThrownBy(() -> transferService().close(id, new com.enterprise.mm.payload.TransferShipmentDtos.Action(5L, "Close")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("unresolved");
        verify(transferRepository, never()).save(entity);
    }

    @Test
    void reservationApiHasExplicitReadAndWriteRoleContracts() throws Exception {
        PreAuthorize controllerGuard = MaterialReservationController.class.getAnnotation(PreAuthorize.class);
        assertThat(controllerGuard).isNotNull();
        assertThat(controllerGuard.value()).contains("WAREHOUSE_OPERATOR");
        for (var method : MaterialReservationController.class.getDeclaredMethods()) {
            if (List.of("create", "update", "delete", "restore", "release").contains(method.getName())) {
                PreAuthorize mutationGuard = method.getAnnotation(PreAuthorize.class);
                assertThat(mutationGuard).as(method.getName()).isNotNull();
                assertThat(mutationGuard.value()).as(method.getName()).doesNotContain("VIEWER");
            }
        }
    }

    private IssueRequestService issueService() {
        return new IssueRequestService(
            issueRepository,
            issueLineRepository,
            issueMapper,
            warehouseRepository,
            userRepository,
            materialRepository,
            reservationService,
            stockMovementService,
            stockBalanceRepository,
            passthroughIdempotency(),
            auditService,
            snapshotSerializer,
            roleDataScopeService,
            materialUnitEligibilityService,
            org.mockito.Mockito.mock(com.enterprise.mm.repository.DemandPlanItemRepository.class)
        );
    }

    private TransferDocumentService transferService() {
        var progress = org.mockito.Mockito.mock(com.enterprise.mm.service.transfer.TransferProgressService.class);
        var readiness = new com.enterprise.mm.service.transfer.TransferProgressService.Progress(false, false, false, List.of("UNRESOLVED_TRANSIT"));
        org.mockito.Mockito.lenient().when(progress.get(org.mockito.ArgumentMatchers.any())).thenReturn(readiness);
        org.mockito.Mockito.lenient().when(progress.describe(org.mockito.ArgumentMatchers.any())).thenReturn(new com.enterprise.mm.service.transfer.TransferProgressService.Projection(java.util.Map.of(), readiness, List.of(), 0, null));
        return new TransferDocumentService(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class),
            transferRepository,
            transferLineRepository,
            new TransferDocumentMapper(),
            warehouseRepository,
            materialRepository,
            passthroughIdempotency(),
            auditService,
            snapshotSerializer,
            roleDataScopeService,
            org.mockito.Mockito.mock(TransferPostingService.class),
            org.mockito.Mockito.mock(StockPickingAllocationService.class),
            org.mockito.Mockito.mock(com.enterprise.mm.repository.TransferShipmentRepository.class),
            org.mockito.Mockito.mock(com.enterprise.mm.repository.TransferShipmentAllocationRepository.class),
            org.mockito.Mockito.mock(com.enterprise.mm.repository.TransferAllocationClearanceRepository.class),
            org.mockito.Mockito.mock(com.enterprise.mm.service.transfer.TransferTransitLedgerService.class),
            new com.enterprise.mm.service.transfer.TransferActionPolicy(transferRepository, roleDataScopeService),
            progress,
            materialUnitEligibilityService,
            org.mockito.Mockito.mock(com.enterprise.mm.warehouse.history.IssueSourceTransferGuard.class),
            org.mockito.Mockito.mock(com.enterprise.mm.service.transfer.TransferReceiptService.class)
        );
    }

    private MaterialReturnService returnService() {
        return new MaterialReturnService(
            org.mockito.Mockito.mock(InventoryFreezeGuardService.class), returnRepository,
            returnLineRepository,
            issueLineRepository,
            issueService(),
            warehouseRepository,
            returnMapper,
            stockMovementService,
            stockBalanceRepository,
            stockBalanceMutationService,
            materialCatalogGuard,
            materialRepository,
            passthroughIdempotency(),
            auditService,
            snapshotSerializer,
            roleDataScopeService,
            materialUnitEligibilityService
        );
    }

    private IssueRequest issueRequest(UUID id, long version) {
        IssueRequest entity = new IssueRequest();
        entity.setId(id);
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setStatus(IssueRequestStatus.APPROVED.name());
        entity.setWarehouseId(UUID.randomUUID());
        entity.setVersion(version);
        return entity;
    }

    private IssueRequestLine issueLine(UUID issueRequestId, UUID materialId, BigDecimal approvedQuantity) {
        IssueRequestLine line = new IssueRequestLine();
        line.setId(UUID.randomUUID());
        line.setTenantId(TenantContext.currentTenant());
        line.setCompanyId(companyId);
        line.setIssueRequestId(issueRequestId);
        line.setMaterialId(materialId);
        line.setRequestedQuantity(approvedQuantity);
        line.setApprovedQuantity(approvedQuantity);
        line.setIssuedQuantity(BigDecimal.ZERO);
        line.setUnitCost(BigDecimal.ONE);
        return line;
    }

    private IssueRequestIssueRequest issueRequest(
        IssueRequest entity,
        IssueRequestLine line,
        BigDecimal quantity,
        List<UUID> materialUnitIds
    ) {
        IssueRequestIssueRequest request = new IssueRequestIssueRequest();
        request.setExpectedVersion(entity.getVersion());
        request.setWarehouseId(entity.getWarehouseId());
        IssueRequestIssueRequest.Line requestLine = new IssueRequestIssueRequest.Line();
        requestLine.setIssueRequestLineId(line.getId());
        requestLine.setIssuedQuantity(quantity);
        requestLine.setMaterialUnitIds(materialUnitIds);
        request.setLines(List.of(requestLine));
        return request;
    }

    private Material material(UUID id, boolean serialized) {
        Material material = new Material();
        material.setId(id);
        material.setTenantId(TenantContext.currentTenant());
        material.setCompanyId(companyId);
        material.setMaterialType(MaterialType.TMB);
        material.setSerialTrackingPolicy(serialized ? SerialTrackingPolicy.REQUIRED : SerialTrackingPolicy.NONE);
        return material;
    }

    private MaterialUnit materialUnit(UUID materialId, UUID warehouseId, UUID locationId, UUID batchId) {
        MaterialUnit unit = new MaterialUnit();
        unit.setId(UUID.randomUUID());
        unit.setTenantId(TenantContext.currentTenant());
        unit.setCompanyId(companyId);
        unit.setMaterialId(materialId);
        unit.setWarehouseId(warehouseId);
        unit.setStorageLocationId(locationId);
        unit.setStockBatchId(batchId);
        unit.setUnitStatus(MaterialUnitStatus.IN_STOCK);
        return unit;
    }

    private StockBalance stockBalance(UUID materialId, UUID warehouseId, UUID locationId, UUID batchId) {
        StockBalance balance = new StockBalance();
        balance.setTenantId(TenantContext.currentTenant());
        balance.setCompanyId(companyId);
        balance.setMaterialId(materialId);
        balance.setWarehouseId(warehouseId);
        balance.setStorageLocationId(locationId);
        balance.setStockBatchId(batchId);
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.TEN);
        return balance;
    }

    private StockMovementDocumentResponse stockDocument(UUID id, long version, List<StockMovementResponse> items) {
        StockMovementDocumentResponse response = new StockMovementDocumentResponse();
        response.setId(id);
        response.setVersion(version);
        response.setItems(items);
        return response;
    }

    private StockMovementResponse postedMovement(UUID batchId, UUID locationId, BigDecimal quantity) {
        StockMovementResponse response = new StockMovementResponse();
        response.setId(UUID.randomUUID());
        response.setStockBatchId(batchId);
        response.setStorageLocationId(locationId);
        response.setQuantity(quantity);
        response.setUnitCost(BigDecimal.ONE);
        return response;
    }

    private IdempotencyService passthroughIdempotency() {
        return new IdempotencyService(idempotencyRecordRepository, new ObjectMapper().findAndRegisterModules());
    }

    private MaterialReturnDocument returnDocument(UUID id, long version) {
        MaterialReturnDocument entity = new MaterialReturnDocument();
        entity.setId(id);
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setStatus(MaterialReturnStatus.SUBMITTED.name());
        entity.setWarehouseId(UUID.randomUUID());
        entity.setVersion(version);
        return entity;
    }

    private TransferDocument transfer(UUID id, TransferStatus status, long version) {
        TransferDocument entity = new TransferDocument();
        entity.setId(id);
        entity.setTenantId(TenantContext.currentTenant());
        entity.setCompanyId(companyId);
        entity.setStatus(status.name());
        entity.setSourceWarehouseId(UUID.randomUUID());
        entity.setDestinationWarehouseId(UUID.randomUUID());
        entity.setVersion(version);
        return entity;
    }
}
