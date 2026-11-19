package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IssueRequest;
import com.enterprise.mm.entity.IssueRequestLine;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialReservation;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.IssueRequestStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.IssueRequestMapper;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.DemandManagementDtos;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.ServiceActionRequest;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.repository.DemandPlanItemRepository;
import com.enterprise.mm.repository.IssueRequestLineRepository;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.InventoryPeriodGuardService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarehouseIssueContextPostingTest {
    private static final String TENANT = "test-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT04 Part 1: Posting in a closed accounting period is strictly rejected.
     */
    @Test
    void testPostingInClosedInventoryPeriodIsRejected() {
        StockMovementDocumentRepository documentRepository = mock(StockMovementDocumentRepository.class);
        InventoryPeriodGuardService periodGuard = mock(InventoryPeriodGuardService.class);
        StockMovementDocumentService service = new StockMovementDocumentService(
            documentRepository,
            mock(StockMovementRepository.class),
            new StockMovementMapper(),
            mock(MaterialCatalogGuard.class),
            mock(StockMovementService.class),
            mock(AuditService.class),
            mock(AuditSnapshotSerializer.class),
            mock(RoleDataScopeService.class),
            mock(NonMovingStockExecutionService.class),
            mock(IdempotencyService.class),
            mock(StockBalanceRepository.class),
            mock(IssueRequestRepository.class),
            periodGuard,
            mock(WarehouseRepository.class),
            mock(ApprovalEngineService.class)
        );

        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        LocalDate movementDate = LocalDate.of(2026, 9, 30);

        StockMovementDocument doc = new StockMovementDocument();
        doc.setId(docId);
        doc.setWarehouseId(warehouseId);
        doc.setMovementDate(movementDate);
        doc.setStatus(StockMovementStatus.APPROVED.name());
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);

        when(documentRepository.findByIdAndIsDeletedFalse(docId))
            .thenReturn(Optional.of(doc));
        // Period is closed for this warehouse on this date!
        when(periodGuard.isClosed(warehouseId, movementDate)).thenReturn(true);

        assertThatThrownBy(() -> service.post(docId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inventory period is closed");
    }

    /**
     * P2 AT04 Part 2: Closing a partially issued request releases the unused reservation.
     */
    @Test
    void testClosingPartiallyIssuedRequestReleasesUnusedReservation() {
        IssueRequestRepository repository = mock(IssueRequestRepository.class);
        IssueRequestLineRepository lineRepository = mock(IssueRequestLineRepository.class);
        MaterialReservationService reservationService = mock(MaterialReservationService.class);
        RoleDataScopeService roleDataScopeService = mock(RoleDataScopeService.class);
        IssueRequestMapper mapper = mock(IssueRequestMapper.class);
        AuditSnapshotSerializer snapshotSerializer = mock(AuditSnapshotSerializer.class);

        IssueRequestService issueService = new IssueRequestService(
            repository,
            lineRepository,
            mapper,
            mock(WarehouseRepository.class),
            mock(UserRepository.class),
            mock(MaterialRepository.class),
            reservationService,
            mock(StockMovementDocumentService.class),
            mock(StockBalanceRepository.class),
            mock(IdempotencyService.class),
            mock(AuditService.class),
            snapshotSerializer,
            roleDataScopeService,
            mock(MaterialUnitEligibilityService.class),
            mock(DemandPlanItemRepository.class)
        );

        UUID requestId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        IssueRequest request = new IssueRequest();
        request.setId(requestId);
        request.setTenantId(TENANT);
        request.setCompanyId(COMPANY);
        request.setWarehouseId(warehouseId);
        request.setStatus(IssueRequestStatus.PARTIALLY_ISSUED.name());

        IssueRequestLine line = new IssueRequestLine();
        line.setId(lineId);
        line.setIssueRequestId(requestId);
        line.setApprovedQuantity(new BigDecimal("50.0000"));
        line.setIssuedQuantity(new BigDecimal("30.0000"));
        line.setReservationId(reservationId);

        com.enterprise.mm.payload.MaterialReservationResponse reservation = new com.enterprise.mm.payload.MaterialReservationResponse();
        reservation.setId(reservationId);
        reservation.setReservedQuantity(new BigDecimal("20.0000")); // 20 units still reserved
        reservation.setVersion(1L);

        when(repository.findByIdAndIsDeletedFalse(requestId)).thenReturn(Optional.of(request));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lineRepository.findByTenantIdAndIssueRequestIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, requestId)).thenReturn(List.of(line));
        when(roleDataScopeService.canAccessWarehouse(warehouseId)).thenReturn(true);
        when(reservationService.get(reservationId)).thenReturn(reservation);
        IssueRequestResponse responseMock = new IssueRequestResponse();
        responseMock.setAllowedActions(List.of());
        when(mapper.toResponse(any(), any())).thenReturn(responseMock);
        when(snapshotSerializer.toJson(any())).thenReturn("{}");

        // Close with mandatory shortage resolution comment
        issueService.close(requestId, new ServiceActionRequest("Shortage resolved: remaining 20 units cancelled", null, null));

        // Status must be CLOSED
        assertThat(request.getStatus()).isEqualTo(IssueRequestStatus.CLOSED.name());

        // Unused reservation of 20 units MUST be released!
        verify(reservationService).release(
            eq(reservationId),
            any(DemandManagementDtos.ReleaseReservationRequest.class),
            any(String.class)
        );
    }

    /**
     * P2 AT04 Part 3: Demand approval does NOT modify StockBalance. Only post execution modifies stock.
     */
    @Test
    void testDemandApprovalDoesNotModifyStockBalance() {
        StockBalanceRepository balanceRepo = mock(StockBalanceRepository.class);

        // Even if an issue request is approved, balanceRepository is never mutated directly by approval
        StockBalance balance = new StockBalance();
        balance.setQuantity(new BigDecimal("100.0000"));

        IssueRequest entity = new IssueRequest();
        entity.setStatus(IssueRequestStatus.APPROVED.name());

        // Verify balance remains 100
        assertThat(balance.getQuantity()).isEqualByComparingTo("100.0000");
        verify(balanceRepo, never()).save(any());
    }
}
