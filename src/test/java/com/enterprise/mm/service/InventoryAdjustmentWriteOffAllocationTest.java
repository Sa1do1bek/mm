package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.InsufficientStockException;
import com.enterprise.mm.payload.InventoryAdjustmentPostRequest;
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

class InventoryAdjustmentWriteOffAllocationTest {

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
     * P2 AT08: Yopiq buxgalteriya davrida inventarizatsiya hisobdan chiqarishini (write-off)
     * post qilish rad etiladi.
     */
    @Test
    @DisplayName("P2 AT08: Yopiq davrda hisobdan chiqarishni o'tkazish rad etiladi")
    void testClosedPeriodBlocksInventoryWriteOff() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setWarehouseId(warehouseId);
        doc.setStatus("APPROVED");
        doc.setVersion(1L);
        doc.setCountRevision(1L);
        doc.setApprovedCountRevision(1L);

        InventoryItem line = new InventoryItem();
        line.setId(UUID.randomUUID());
        line.setInventoryDocumentId(docId);
        line.setWarehouseId(warehouseId);
        line.setSystemQuantity(new BigDecimal("10.0000"));
        line.setCountedQuantity(new BigDecimal("8.0000")); // -2 shortage

        when(revisionService.lock(docId)).thenReturn(doc);
        when(itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TENANT, docId))
            .thenReturn(List.of(line));

        LocalDate postDate = LocalDate.of(2025, 12, 31);
        doThrow(new BusinessException("Inventory period is closed", "inventory.period.closed"))
            .when(periodGuard).ensureOpen(eq(warehouseId), eq(postDate), any());

        InventoryAdjustmentPostRequest request = new InventoryAdjustmentPostRequest(1L, 1L, postDate);

        assertThatThrownBy(() -> service.postAdjustments(docId, request, "idemp-adj-period"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Inventory period is closed");

        verifyNoInteractions(stockMovementService);
    }

    /**
     * P2 AT08: Mavjud qoldiqdan ortiqcha hisobdan chiqarishga (write-off) ruxsat berilmaydi
     * (manfiy qoldiq bloklanadi).
     */
    @Test
    @DisplayName("P2 AT08: Mavjud qoldiqdan oshiqcha kamomad hisobdan chiqarilishi rad etiladi")
    void testOverWriteOffPreventedByStockMutationService() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setWarehouseId(warehouseId);
        doc.setStatus("APPROVED");
        doc.setVersion(1L);
        doc.setCountRevision(1L);
        doc.setApprovedCountRevision(1L);

        InventoryItem line = new InventoryItem();
        line.setId(UUID.randomUUID());
        line.setInventoryDocumentId(docId);
        line.setWarehouseId(warehouseId);
        line.setMaterialId(materialId);
        line.setSystemQuantity(new BigDecimal("100.0000"));
        line.setCountedQuantity(BigDecimal.ZERO); // Attempting to write off 100 units

        when(revisionService.lock(docId)).thenReturn(doc);
        when(itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TENANT, docId))
            .thenReturn(List.of(line));

        Material material = new Material();
        material.setId(materialId);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.NONE);
        when(materialRepository.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));

        // Stock movement service throws InsufficientStockException when stock balance is below 100
        when(stockMovementService.createAndPostManagedInventoryAdjustment(any()))
            .thenThrow(new InsufficientStockException(
                "Not enough available stock to write off",
                materialId,
                "Steel Sheet",
                warehouseId,
                "Central Depot",
                new BigDecimal("100.0000"),
                BigDecimal.ZERO,
                "KG"
            ));

        LocalDate postDate = LocalDate.of(2026, 10, 14);
        InventoryAdjustmentPostRequest request = new InventoryAdjustmentPostRequest(1L, 1L, postDate);

        assertThatThrownBy(() -> service.postAdjustments(docId, request, "idemp-adj-nowriteoff"))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("Not enough available stock");
    }

    /**
     * P2 AT08: Seriyali materiallar uchun lineage bo'lmagan holda musbat tuzatish (topilma)
     * kiritish taqiqlanadi (SERIALIZED_POSITIVE_ADJUSTMENT_REQUIRES_RECEIPT_LINEAGE).
     */
    @Test
    @DisplayName("P2 AT08: Seriyali materialda kelib chiqishisiz musbat tuzatish kiritish taqiqlanadi")
    void testSerializedPositiveAdjustmentRequiresReceiptLineage() {
        UUID docId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        InventoryDocument doc = new InventoryDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setCompanyId(COMPANY);
        doc.setWarehouseId(warehouseId);
        doc.setStatus("APPROVED");
        doc.setVersion(1L);
        doc.setCountRevision(1L);
        doc.setApprovedCountRevision(1L);

        InventoryItem line = new InventoryItem();
        line.setId(UUID.randomUUID());
        line.setInventoryDocumentId(docId);
        line.setWarehouseId(warehouseId);
        line.setMaterialId(materialId);
        line.setSystemQuantity(new BigDecimal("5.0000"));
        line.setCountedQuantity(new BigDecimal("7.0000")); // +2 surplus!

        when(revisionService.lock(docId)).thenReturn(doc);
        when(itemRepository.findByTenantIdAndInventoryDocumentIdAndIsDeletedFalse(TENANT, docId))
            .thenReturn(List.of(line));

        Material serializedMaterial = new Material();
        serializedMaterial.setId(materialId);
        serializedMaterial.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED); // REQUIRED!
        when(materialRepository.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(serializedMaterial));

        LocalDate postDate = LocalDate.of(2026, 10, 14);
        InventoryAdjustmentPostRequest request = new InventoryAdjustmentPostRequest(1L, 1L, postDate);

        assertThatThrownBy(() -> service.postAdjustments(docId, request, "idemp-adj-serial"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("SERIALIZED_POSITIVE_ADJUSTMENT_REQUIRES_RECEIPT_LINEAGE");

        verifyNoInteractions(stockMovementService);
    }
}
