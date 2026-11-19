package com.enterprise.mm.warehouse.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptBatch;
import com.enterprise.mm.entity.GoodsReceiptEvidence;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.GoodsReceiptUnit;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialUnitEvent;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.repository.GoodsReceiptBatchRepository;
import com.enterprise.mm.repository.GoodsReceiptEvidenceRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.GoodsReceiptUnitRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.warehouse.receiving.model.ReceiptLinePostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingResult;
import com.enterprise.mm.warehouse.receiving.model.ReceiptUnitPostingDraft;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WarehouseReceiptContextPostingTest {
    private final GoodsReceiptRepository receiptRepository = mock(GoodsReceiptRepository.class);
    private final GoodsReceiptLineRepository lineRepository = mock(GoodsReceiptLineRepository.class);
    private final GoodsReceiptBatchRepository receiptBatchRepository = mock(GoodsReceiptBatchRepository.class);
    private final GoodsReceiptEvidenceRepository evidenceRepository = mock(GoodsReceiptEvidenceRepository.class);
    private final GoodsReceiptUnitRepository receiptUnitRepository = mock(GoodsReceiptUnitRepository.class);
    private final MaterialUnitRepository materialUnitRepository = mock(MaterialUnitRepository.class);
    private final MaterialUnitEventRepository materialUnitEventRepository = mock(MaterialUnitEventRepository.class);
    private final PurchaseOrderRepository purchaseOrderRepository = mock(PurchaseOrderRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MaterialRepository materialRepository = mock(MaterialRepository.class);

    private final WarehouseReceiptPostingService service = new WarehouseReceiptPostingService(
        receiptRepository,
        lineRepository,
        receiptBatchRepository,
        evidenceRepository,
        receiptUnitRepository,
        materialUnitRepository,
        materialUnitEventRepository,
        purchaseOrderRepository,
        auditService,
        materialRepository
    );

    private final UUID companyId = UUID.randomUUID();
    private final String tenantId = "test-tenant";

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        when(receiptRepository.save(any(GoodsReceipt.class))).thenAnswer(i -> {
            GoodsReceipt gr = i.getArgument(0);
            if (gr.getId() == null) gr.setId(UUID.randomUUID());
            return gr;
        });
        when(lineRepository.save(any(GoodsReceiptLine.class))).thenAnswer(i -> {
            GoodsReceiptLine line = i.getArgument(0);
            if (line.getId() == null) line.setId(UUID.randomUUID());
            return line;
        });
        when(evidenceRepository.save(any(GoodsReceiptEvidence.class))).thenAnswer(i -> {
            GoodsReceiptEvidence ev = i.getArgument(0);
            if (ev.getId() == null) ev.setId(UUID.randomUUID());
            return ev;
        });
        when(receiptUnitRepository.save(any(GoodsReceiptUnit.class))).thenAnswer(i -> {
            GoodsReceiptUnit u = i.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID());
            return u;
        });
        when(materialUnitRepository.save(any(MaterialUnit.class))).thenAnswer(i -> {
            MaterialUnit mu = i.getArgument(0);
            if (mu.getId() == null) mu.setId(UUID.randomUUID());
            return mu;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    /**
     * P2 AT01: 100 ordered units receipt scenario.
     * 80 accepted (available), 10 damaged/held under approved policy (quarantine/hold),
     * and 10 outstanding on the PO remainder.
     */
    @Test
    void testPartialPoReceiptWithAcceptedHeldAndOutstandingRemainder() {
        UUID poId = UUID.randomUUID();
        UUID poItemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID exceptionId = UUID.randomUUID();

        // 100 ordered on PO: 80 accepted, 10 damaged/held, physicalReceived = 90, openBefore = 100.
        // Remainder on PO = 100 - 90 = 10.
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            poItemId, materialId, null, "KG", warehouseId, null, null,
            "BATCH-01",
            new BigDecimal("100.0000"), // openQuantityBefore
            new BigDecimal("90.0000"),  // physicalReceivedQuantity
            new BigDecimal("80.0000"),  // acceptedQuantity
            BigDecimal.ZERO,            // rejectedQuantity
            new BigDecimal("10.0000"),  // damagedQuantity (held under approved exception)
            BigDecimal.ZERO,            // excessQuantity
            BigDecimal.ZERO,            // shortageQuantity
            null,
            "QUALITY_HOLD",
            movementId,
            null,
            TrackingMode.QUANTITY,
            List.of(),
            "OVERRIDE_APPROVED",
            "Accepted with approved quality hold exception",
            exceptionId
        );

        ReceiptPostingDraft draft = new ReceiptPostingDraft(
            poId, supplierId, null, "rcpt-at01-key", "UZS", BigDecimal.ONE,
            new BigDecimal("80000.0000"), new BigDecimal("80000.0000"),
            List.of(), List.of(line)
        );

        ReceiptPostingResult result = service.record(draft);

        assertThat(result.acceptedQuantity()).isEqualByComparingTo("80.0000");
        assertThat(result.damagedQuantity()).isEqualByComparingTo("10.0000");
        assertThat(result.approvedAccreditationExceptionIds()).containsExactly(exceptionId);

        // Verify saved line preserves explicit disposition
        ArgumentCaptor<GoodsReceiptLine> lineCaptor = ArgumentCaptor.forClass(GoodsReceiptLine.class);
        verify(lineRepository).save(lineCaptor.capture());
        GoodsReceiptLine savedLine = lineCaptor.getValue();

        assertThat(savedLine.getOpenQuantityBefore()).isEqualByComparingTo("100.0000");
        assertThat(savedLine.getPhysicalReceivedQuantity()).isEqualByComparingTo("90.0000");
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("80.0000");
        assertThat(savedLine.getDamagedQuantity()).isEqualByComparingTo("10.0000");
        // Outstanding PO remainder is 100 - 90 = 10
        BigDecimal poOutstandingRemainder = savedLine.getOpenQuantityBefore().subtract(savedLine.getPhysicalReceivedQuantity());
        assertThat(poOutstandingRemainder).isEqualByComparingTo("10.0000");

        // Status is POSTED_WITH_EXCEPTIONS because damaged > 0 and exception is approved
        assertThat(savedLine.getStatus()).isEqualTo("POSTED_WITH_EXCEPTIONS");
    }

    /**
     * Standalone receipt origin: allows recording without a PO, while enforcing supplier and material checks.
     */
    @Test
    void testStandaloneReceiptOriginAllowedWithSupplierAndEvidence() {
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            null, // standalone: no PO item
            materialId, null, "PCS", warehouseId, null, null,
            "BATCH-STANDALONE",
            BigDecimal.ZERO,
            new BigDecimal("50.0000"),
            new BigDecimal("50.0000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "ACCEPTED",
            movementId,
            null,
            TrackingMode.QUANTITY,
            List.of(),
            "ELIGIBLE",
            null,
            null
        );

        ReceiptPostingDraft draft = new ReceiptPostingDraft(
            null, // standalone: no PO
            supplierId, null, "standalone-key", "UZS", BigDecimal.ONE,
            new BigDecimal("50000.0000"), new BigDecimal("50000.0000"),
            List.of(), List.of(line)
        );

        ReceiptPostingResult result = service.record(draft);

        assertThat(result.receiptId()).isNotNull();
        assertThat(result.acceptedQuantity()).isEqualByComparingTo("50.0000");

        ArgumentCaptor<GoodsReceipt> receiptCaptor = ArgumentCaptor.forClass(GoodsReceipt.class);
        verify(receiptRepository).save(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue().getPurchaseOrderId()).isNull();
        assertThat(receiptCaptor.getValue().getSupplierId()).isEqualTo(supplierId);
    }

    /**
     * Services keep out of physical stock: material of type SERVICES does not create physical MaterialUnits.
     */
    @Test
    void testServiceMaterialKeepsOutOfPhysicalStockUnits() {
        UUID poId = UUID.randomUUID();
        UUID poItemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID serviceMaterialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();

        Material serviceMaterial = new Material();
        serviceMaterial.setMaterialType(MaterialType.SERVICES);
        when(materialRepository.findByIdAndIsDeletedFalse(serviceMaterialId)).thenReturn(Optional.of(serviceMaterial));

        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            poItemId, serviceMaterialId, null, "SRV", warehouseId, null, null,
            null,
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            new BigDecimal("1.0000"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            "ACCEPTED",
            movementId,
            null,
            TrackingMode.SERIAL,
            List.of(new ReceiptUnitPostingDraft("SRV-001", "NEW", "ACCEPTED", null)),
            "ELIGIBLE",
            null,
            null
        );

        ReceiptPostingDraft draft = new ReceiptPostingDraft(
            poId, supplierId, null, "service-rcpt-key", "UZS", BigDecimal.ONE,
            new BigDecimal("10000.0000"), new BigDecimal("10000.0000"),
            List.of(), List.of(line)
        );

        ReceiptPostingResult result = service.record(draft);

        assertThat(result.receiptUnitsRecorded()).isEqualTo(1);
        // Zero physical material units created because it is a SERVICE!
        assertThat(result.materialUnitsCreated()).isZero();
        verify(materialUnitRepository, never()).save(any());
    }
}
