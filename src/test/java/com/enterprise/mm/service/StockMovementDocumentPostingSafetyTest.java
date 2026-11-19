package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementCancelRequest;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StockMovementDocumentPostingSafetyTest {
    private static final String TENANT = "tenant-a";
    private static final UUID COMPANY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID LINE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID WAREHOUSE = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final StockMovementDocumentRepository documentRepository = mock(StockMovementDocumentRepository.class);
    private final StockMovementRepository movementRepository = mock(StockMovementRepository.class);
    private final MaterialCatalogGuard materialCatalogGuard = mock(MaterialCatalogGuard.class);
    private final StockMovementService movementService = mock(StockMovementService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final ApprovalEngineService approvalEngineService = mock(ApprovalEngineService.class);
    private final StockMovementDocumentService service = new StockMovementDocumentService(
        documentRepository,
        movementRepository,
        new StockMovementMapper(),
        materialCatalogGuard,
        movementService,
        mock(AuditService.class),
        mock(AuditSnapshotSerializer.class),
        mock(RoleDataScopeService.class),
        mock(NonMovingStockExecutionService.class),
        idempotencyService,
        mock(StockBalanceRepository.class),
        mock(IssueRequestRepository.class),
        mock(InventoryPeriodGuardService.class),
        mock(WarehouseRepository.class),
        approvalEngineService
    );

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);
        when(documentRepository.save(any(StockMovementDocument.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void kimyosanoatReferenceCannotBeCreatedThroughGenericStockDocumentApi() {
        var request = new StockMovementDocumentCreateRequest();
        request.setReferenceType("KIMYOSANOAT");
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("scoped approval workflow");
        verify(documentRepository, never()).save(any());
    }

    @Test
    void postRequiresNonBlankIdempotencyKeyAndExpectedVersion() {
        assertThatThrownBy(() -> service.post(DOCUMENT_ID, null, "key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.post(
            DOCUMENT_ID, new StockMovementPostRequest(7L), " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Idempotency-Key");

        verify(idempotencyService, never()).execute(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void postRejectsStaleVersionBeforeAnyLineMutation() {
        StockMovementDocument document = approvedDocument(8L);
        when(documentRepository.findByIdAndIsDeletedFalse(DOCUMENT_ID)).thenReturn(Optional.of(document));
        executeIdempotentAction();

        assertThatThrownBy(() -> service.post(
            DOCUMENT_ID, new StockMovementPostRequest(7L), "post-key"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(movementService, never()).postApprovedDocumentLine(any());
    }

    @Test
    void reversalRequiresVersionReasonAndIdempotencyKeyBeforeMutation() {
        assertThatThrownBy(() -> service.cancelPosted(DOCUMENT_ID, null, "reverse-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.cancelPosted(
            DOCUMENT_ID, new StockMovementCancelRequest(7L, "bad"), "reverse-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.cancelPosted(
            DOCUMENT_ID, new StockMovementCancelRequest(7L, "Duplicate posting"), " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Idempotency-Key");

        verify(idempotencyService, never()).execute(eq("STOCK_MOVEMENT_CANCEL"), any(), any(), any(), any(), any());
        verify(movementService, never()).cancelPosted(any(), any(), anyString());
    }

    @Test
    void reversalRejectsStaleVersionBeforeCreatingReverseLines() {
        StockMovementDocument document = approvedDocument(8L);
        document.setStatus(StockMovementStatus.POSTED.name());
        when(documentRepository.findByIdAndIsDeletedFalse(DOCUMENT_ID)).thenReturn(Optional.of(document));
        executeIdempotentAction();

        assertThatThrownBy(() -> service.cancelPosted(
            DOCUMENT_ID,
            new StockMovementCancelRequest(7L, "Duplicate posting"),
            "reverse-key"
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("changed by another user");

        verify(movementService, never()).cancelPosted(any(), any(), anyString());
    }

    @Test
    void sameIdempotencyKeyReplaysResponseWithoutASecondPhysicalPost() {
        StockMovementDocument document = approvedDocument(7L);
        StockMovement line = approvedLine();
        when(documentRepository.findByIdAndIsDeletedFalse(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(movementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TENANT, DOCUMENT_ID)).thenReturn(List.of(line));

        AtomicReference<StockMovementDocumentResponse> replay = new AtomicReference<>();
        when(idempotencyService.execute(
            eq("STOCK_MOVEMENT_POST"),
            eq(DOCUMENT_ID),
            eq("post-key"),
            any(),
            eq(StockMovementDocumentResponse.class),
            any()
        )).thenAnswer(invocation -> {
            if (replay.get() != null) {
                return replay.get();
            }
            @SuppressWarnings("unchecked")
            Supplier<StockMovementDocumentResponse> action = invocation.getArgument(5);
            StockMovementDocumentResponse response = action.get();
            replay.set(response);
            return response;
        });

        StockMovementDocumentResponse first = service.post(
            DOCUMENT_ID, new StockMovementPostRequest(7L), "post-key");
        StockMovementDocumentResponse second = service.post(
            DOCUMENT_ID, new StockMovementPostRequest(7L), "post-key");

        assertThat(first).isSameAs(second);
        assertThat(first.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        assertThat(first.getPostedAt()).isNotNull();
        verify(movementService, times(1)).postApprovedDocumentLine(line);
        verify(documentRepository, times(1)).save(document);
    }

    @Test
    void cancellationRequiresReasonVersionAndIdempotencyBeforeReversal() {
        assertThatThrownBy(() -> service.cancelPosted(DOCUMENT_ID, null, "cancel-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.cancelPosted(
            DOCUMENT_ID,
            new StockMovementCancelRequest(7L, "Wrong issue"),
            " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Idempotency-Key");

        verify(movementService, never()).cancelPosted(any(), any(), anyString());
    }

    private void executeIdempotentAction() {
        when(idempotencyService.execute(
            anyString(), any(), anyString(), any(), any(), any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<StockMovementDocumentResponse> action = invocation.getArgument(5);
            return action.get();
        });
    }

    private StockMovementDocument approvedDocument(long version) {
        StockMovementDocument document = new StockMovementDocument();
        document.setId(DOCUMENT_ID);
        document.setTenantId(TENANT);
        document.setCompanyId(COMPANY);
        document.setVersion(version);
        document.setCode("DOC-1");
        document.setName("Movement document");
        document.setStatus(StockMovementStatus.APPROVED.name());
        document.setWarehouseId(WAREHOUSE);
        document.setMovementType(MovementType.RECEIPT);
        document.setMovementDate(LocalDate.now());
        return document;
    }

    private StockMovement approvedLine() {
        StockMovement line = new StockMovement();
        line.setId(LINE_ID);
        line.setTenantId(TENANT);
        line.setCompanyId(COMPANY);
        line.setCode("DOC-1-1");
        line.setName("Movement line");
        line.setStatus(StockMovementStatus.APPROVED.name());
        line.setWarehouseId(WAREHOUSE);
        line.setMovementType(MovementType.RECEIPT);
        return line;
    }
}
