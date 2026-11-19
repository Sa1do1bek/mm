package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.entity.InventoryItem;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.InventoryAdjustmentPostRequest;
import com.enterprise.mm.payload.InventoryAdjustmentPostingResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.InventoryItemRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StockBatchRepository;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.inventory.InventoryFoundStockService;
import com.enterprise.mm.service.inventory.InventoryPostingReadinessService;
import com.enterprise.mm.service.inventory.InventoryRevisionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InventoryCountVsDecisionPostingTest {

    private static final String TENANT = "navoiy-tenant";
    private static final UUID COMPANY = UUID.randomUUID();

    private InventoryDocumentRepository documentRepository;
    private InventoryItemRepository itemRepository;
    private StockMovementService stockMovementService;
    private StockMovementRepository movementRepository;
    private InventoryRevisionService revisionService;
    private InventoryPostingReadinessService readinessService;
    private InventoryPeriodGuardService periodGuard;
    private MaterialRepository materialRepository;
    private InventoryProcessingService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY);

        documentRepository = mock(InventoryDocumentRepository.class);
        itemRepository = mock(InventoryItemRepository.class);
        stockMovementService = mock(StockMovementService.class);
        movementRepository = mock(StockMovementRepository.class);
        revisionService = mock(InventoryRevisionService.class);
        readinessService = mock(InventoryPostingReadinessService.class);
        periodGuard = mock(InventoryPeriodGuardService.class);
        materialRepository = mock(MaterialRepository.class);

        var idempotency = mock(IdempotencyService.class);
        when(idempotency.execute(any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Object arg = inv.getArgument(5);
                if (arg instanceof Supplier<?> s) return s.get();
                return null;
            });

        var permit = mock(InventoryAdjustmentPermitService.class);
        when(permit.withApprovedCount(any(), anyLong(), any()))
            .thenAnswer(inv -> {
                Object arg = inv.getArgument(2);
                if (arg instanceof Supplier<?> s) return s.get();
                return null;
            });

        when(materialRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(inv -> {
            Material m = new Material();
            m.setId(inv.getArgument(0));
            m.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
            return Optional.of(m);
        });

        service = new InventoryProcessingService(
            documentRepository,
            itemRepository,
            stockMovementService,
            movementRepository,
            mock(StockBatchRepository.class),
            mock(AuditService.class),
            idempotency,
            permit,
            revisionService,
            readinessService,
            mock(InventoryScopeLockService.class),
            mock(InventoryScopeService.class),
            mock(InventoryFoundStockService.class),
            periodGuard,
            materialRepository,
            mock(MaterialUnitEligibilityService.class),
            mock(StockValuationService.class)
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT07: Sanoq natijasi yoki taqqoslash bayonnomasi o'zi o'zidan ombor qoldig'ini
     * o'zgartirmasligi va harakat yaratmasligini tasdiqlash.
     */
    @Test
    @DisplayName("P2 AT07: Sanoq kiritish o'zi o'zidan ombor harakati yaratmaydi va qoldiqni o'zgartirmaydi")
    void testCountEntryAloneDoesNotMutateStock() {
        UUID docId = UUID.randomUUID();
        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setStatus("COUNTING"); // Physical counting is ongoing
        doc.setVersion(1L);
        doc.setCountRevision(1L);

        // A line was counted with difference (-5 shortage), but decision not approved
        InventoryItem item = new InventoryItem();
        item.setId(UUID.randomUUID());
        item.setInventoryDocumentId(docId);
        item.setSystemQuantity(new BigDecimal("20.0000"));
        item.setCountedQuantity(new BigDecimal("15.0000")); // 5 missing

        // Stock movement service is NEVER called simply because count was recorded
        verifyNoInteractions(stockMovementService);
        assertThat(item.getLinkedStockMovementId()).isNull();
    }

    /**
     * P2 AT07: Tasdiqlanmagan yoki eskirgan reviziya bilan posting qilinishiga yo'l qo'yilmaydi.
     */
    @Test
    @DisplayName("P2 AT07: Eskirgan yoki tasdiqlanmagan reviziya bilan posting rad etiladi (APPROVAL_REVISION_STALE)")
    void testStaleOrUnapprovedRevisionPostingRejected() {
        UUID docId = UUID.randomUUID();
        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setStatus("IN_REVIEW");
        doc.setVersion(2L);
        doc.setCountRevision(3L);
        doc.setApprovedCountRevision(2L); // Approved revision is 2, but current revision is 3

        when(revisionService.lock(docId)).thenReturn(doc);

        // Command passes stale revision 1L instead of approved 2L
        InventoryAdjustmentPostRequest request = new InventoryAdjustmentPostRequest(
            2L,
            1L, // Stale approved count revision!
            LocalDate.of(2026, 10, 14)
        );

        assertThatThrownBy(() -> service.postAdjustments(docId, request, "idemp-adj-01"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("approved count revision is stale");

        verifyNoInteractions(stockMovementService);
    }

    /**
     * P2 AT07: Tasdiqlangan qaror bo'yicha aniq kamomad (WRITE_OFF) va ortiqchalik (ADJUSTMENT)
     * harakatlari o'tkaziladi va xujjatga bog'lanadi.
     */
    @Test
    @DisplayName("P2 AT07: Tasdiqlangan qaror bo'yicha aniq kamomad WRITE_OFF sifatida o'tkaziladi")
    void testApprovedShortageDecisionPostsExactWriteOff() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();

        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setWarehouseId(warehouseId);
        doc.setStatus("APPROVED");
        doc.setVersion(1L);
        doc.setCountRevision(2L);
        doc.setApprovedCountRevision(2L);

        InventoryItem shortageLine = new InventoryItem();
        shortageLine.setId(UUID.randomUUID());
        shortageLine.setInventoryDocumentId(docId);
        shortageLine.setTenantId(TENANT);
        shortageLine.setCompanyId(COMPANY);
        shortageLine.setWarehouseId(warehouseId);
        shortageLine.setMaterialId(materialId);
        shortageLine.setStockBatchId(batchId);
        shortageLine.setSystemQuantity(new BigDecimal("10.0000"));
        shortageLine.setCountedQuantity(new BigDecimal("7.0000")); // -3 shortage
        shortageLine.setUnitCost(new BigDecimal("50000.00"));

        when(revisionService.lock(docId)).thenReturn(doc);
        when(itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TENANT, docId))
            .thenReturn(List.of(shortageLine));

        UUID movementId = UUID.randomUUID();
        StockMovementResponse stockResponse = new StockMovementResponse();
        stockResponse.setId(movementId);
        when(stockMovementService.createAndPostManagedInventoryAdjustment(any())).thenReturn(stockResponse);

        LocalDate postDate = LocalDate.of(2026, 10, 14);
        InventoryAdjustmentPostRequest request = new InventoryAdjustmentPostRequest(1L, 2L, postDate);

        InventoryAdjustmentPostingResponse response = service.postAdjustments(docId, request, "idemp-adj-02");

        assertThat(response).isNotNull();
        assertThat(response.movementIds()).containsExactly(movementId);
        assertThat(shortageLine.getLinkedStockMovementId()).isEqualTo(movementId);

        // Verify the posted movement parameters
        ArgumentCaptor<com.enterprise.mm.entity.StockMovement> captor = ArgumentCaptor.forClass(com.enterprise.mm.entity.StockMovement.class);
        verify(stockMovementService).createAndPostManagedInventoryAdjustment(captor.capture());
        var movement = captor.getValue();

        assertThat(movement.getMovementType()).isEqualTo(MovementType.WRITE_OFF);
        assertThat(movement.getQuantity()).isEqualByComparingTo("3.0000"); // difference.abs()
        assertThat(movement.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(movement.getStockBatchId()).isEqualTo(batchId);
        assertThat(movement.getMovementDate()).isEqualTo(postDate);
        verify(periodGuard).ensureOpen(warehouseId, postDate, "inventory adjustment");
    }
}
