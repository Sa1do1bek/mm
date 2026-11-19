package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.StockMovementCancelRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.repository.IssueRequestRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockMovementReversalSafetyTest {

    private static final String TENANT = "test-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    private StockMovementDocumentRepository documentRepository;
    private StockMovementRepository movementRepository;
    private StockMovementService stockMovementService;
    private StockBalanceRepository stockBalanceRepository;
    private InventoryPeriodGuardService periodGuard;
    private IdempotencyService idempotencyService;
    private StockMovementDocumentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        documentRepository = mock(StockMovementDocumentRepository.class);
        movementRepository = mock(StockMovementRepository.class);
        stockMovementService = mock(StockMovementService.class);
        stockBalanceRepository = mock(StockBalanceRepository.class);
        periodGuard = mock(InventoryPeriodGuardService.class);
        idempotencyService = mock(IdempotencyService.class);
        when(idempotencyService.execute(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                java.util.function.Supplier<?> sup = inv.getArgument(5);
                return sup.get();
            });

        service = new StockMovementDocumentService(
            documentRepository,
            movementRepository,
            new StockMovementMapper(),
            mock(MaterialCatalogGuard.class),
            stockMovementService,
            mock(AuditService.class),
            mock(AuditSnapshotSerializer.class),
            mock(RoleDataScopeService.class),
            mock(NonMovingStockExecutionService.class),
            idempotencyService,
            stockBalanceRepository,
            mock(IssueRequestRepository.class),
            periodGuard,
            mock(WarehouseRepository.class),
            mock(ApprovalEngineService.class)
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT06 Part 1: Reversal creates a linked reversal document and sets original to CANCELLED,
     * preserving full audit history without deleting the original record.
     */
    @Test
    @DisplayName("P2 AT06: Clean reversal creates linked reversal document and preserves original document")
    void testCleanReversalCreatesLinkedDocumentAndPreservesOriginal() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        StockMovementDocument original = new StockMovementDocument();
        original.setId(docId);
        original.setTenantId(TENANT);
        original.setCompanyId(COMPANY);
        original.setCode("SMD-1001");
        original.setName("Goods receipt");
        original.setStatus(StockMovementStatus.POSTED.name());
        original.setMovementType(MovementType.RECEIPT);
        original.setWarehouseId(warehouseId);
        original.setMovementDate(LocalDate.now());
        original.setVersion(1L);

        StockMovement line = new StockMovement();
        line.setId(UUID.randomUUID());
        line.setDocumentId(docId);
        line.setMaterialId(materialId);
        line.setQuantity(new BigDecimal("10.0000"));

        when(documentRepository.findByIdAndIsDeletedFalse(docId)).thenReturn(Optional.of(original));
        when(movementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, docId))
            .thenReturn(List.of(line));

        // Available balance is 10 units (full quantity intact, no downstream consumption)
        StockBalanceRepository.Material360AggregateProjection agg = mock(StockBalanceRepository.Material360AggregateProjection.class);
        when(agg.getAvailable()).thenReturn(new BigDecimal("10.0000"));
        when(stockBalanceRepository.aggregateMaterial360(TENANT, COMPANY, materialId, warehouseId))
            .thenReturn(agg);

        when(documentRepository.save(any(StockMovementDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        StockMovementDocumentResponse reversalResponse = service.cancelPosted(
            docId,
            new StockMovementCancelRequest(1L, "Reversal requested by accounting"),
            "IDEMP-REV-1"
        );

        // Verifications:
        // Original document status changed to CANCELLED (never deleted)
        assertThat(original.getStatus()).isEqualTo(StockMovementStatus.CANCELLED.name());
        // Reversal response has reversal reference linking to original
        assertThat(reversalResponse.getReversalOfId()).isEqualTo(docId);
        assertThat(reversalResponse.getCode()).contains("-REV");
    }

    /**
     * P2 AT06 Part 2: When dependent downstream movements have already consumed the stock,
     * direct reversal is rejected, requiring an approved correction.
     */
    @Test
    @DisplayName("P2 AT06: Direct reversal is blocked when dependent downstream movements exist")
    void testReversalBlockedWhenDownstreamMovementsExist() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        StockMovementDocument original = new StockMovementDocument();
        original.setId(docId);
        original.setTenantId(TENANT);
        original.setCompanyId(COMPANY);
        original.setCode("SMD-1002");
        original.setStatus(StockMovementStatus.POSTED.name());
        original.setMovementType(MovementType.RECEIPT);
        original.setWarehouseId(warehouseId);
        original.setMovementDate(LocalDate.now());
        original.setVersion(1L);

        StockMovement line = new StockMovement();
        line.setId(UUID.randomUUID());
        line.setDocumentId(docId);
        line.setMaterialId(materialId);
        line.setQuantity(new BigDecimal("10.0000")); // Received 10 units

        when(documentRepository.findByIdAndIsDeletedFalse(docId)).thenReturn(Optional.of(original));
        when(movementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TENANT, docId))
            .thenReturn(List.of(line));

        // Downstream movements consumed 8 units: only 2 available units remain in stock!
        StockBalanceRepository.Material360AggregateProjection agg = mock(StockBalanceRepository.Material360AggregateProjection.class);
        when(agg.getAvailable()).thenReturn(new BigDecimal("2.0000")); // Less than original 10 units!
        when(stockBalanceRepository.aggregateMaterial360(TENANT, COMPANY, materialId, warehouseId))
            .thenReturn(agg);

        assertThatThrownBy(() -> service.cancelPosted(
            docId,
            new StockMovementCancelRequest(1L, "Attempt reversal on consumed stock"),
            "IDEMP-REV-2"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Direct reversal is blocked because dependent downstream movements exist");
    }
}
