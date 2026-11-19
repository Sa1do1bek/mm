package com.enterprise.mm.warehouse.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.GoodsReceipt;
import com.enterprise.mm.entity.GoodsReceiptBatch;
import com.enterprise.mm.entity.GoodsReceiptEvidence;
import com.enterprise.mm.entity.GoodsReceiptLine;
import com.enterprise.mm.entity.GoodsReceiptUnit;
import com.enterprise.mm.entity.MaterialUnit;
import com.enterprise.mm.entity.MaterialUnitEvent;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.repository.GoodsReceiptBatchRepository;
import com.enterprise.mm.repository.GoodsReceiptEvidenceRepository;
import com.enterprise.mm.repository.GoodsReceiptLineRepository;
import com.enterprise.mm.repository.GoodsReceiptRepository;
import com.enterprise.mm.repository.GoodsReceiptUnitRepository;
import com.enterprise.mm.repository.MaterialUnitEventRepository;
import com.enterprise.mm.repository.MaterialUnitRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.warehouse.receiving.model.ReceiptBatchPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptLinePostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptPostingDraft;
import com.enterprise.mm.warehouse.receiving.model.ReceiptUnitPostingDraft;
import com.enterprise.mm.warehouse.receiving.service.WarehouseReceiptPostingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WarehouseReceiptPostingServiceTest {
    @Mock private GoodsReceiptRepository receiptRepository;
    @Mock private GoodsReceiptLineRepository lineRepository;
    @Mock private GoodsReceiptBatchRepository receiptBatchRepository;
    @Mock private GoodsReceiptEvidenceRepository evidenceRepository;
    @Mock private GoodsReceiptUnitRepository receiptUnitRepository;
    @Mock private MaterialUnitRepository materialUnitRepository;
    @Mock private MaterialUnitEventRepository materialUnitEventRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private AuditService auditService;

    private WarehouseReceiptPostingService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("receipt-ledger-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        service = new WarehouseReceiptPostingService(
            receiptRepository,
            lineRepository,
            receiptBatchRepository,
            evidenceRepository,
            receiptUnitRepository,
            materialUnitRepository,
            materialUnitEventRepository,
            purchaseOrderRepository,
            auditService
        );
        org.mockito.Mockito.lenient()
            .when(receiptRepository.save(any(GoodsReceipt.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(lineRepository.save(any(GoodsReceiptLine.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(receiptBatchRepository.save(any(GoodsReceiptBatch.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(evidenceRepository.save(any(GoodsReceiptEvidence.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(receiptUnitRepository.save(any(GoodsReceiptUnit.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(materialUnitRepository.save(any(MaterialUnit.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        org.mockito.Mockito.lenient()
            .when(materialUnitEventRepository.save(any(MaterialUnitEvent.class)))
            .thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void recordsImmutableLineOutcomeAndEvidenceLineageIncludingExcessAndShortage() {
        UUID poId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID approvedExceptionId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId(poId);
        purchaseOrder.setTenantId(TenantContext.currentTenant());
        purchaseOrder.setCompanyId(companyId);
        purchaseOrder.setContractId(contractId);
        purchaseOrder.setName("Contract receipt PO");
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(poId)).thenReturn(Optional.of(purchaseOrder));
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            itemId, materialId, materialGroupId, "PCS", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "LOT-1", new BigDecimal("10"), new BigDecimal("6"), BigDecimal.ONE, BigDecimal.ONE,
            new BigDecimal("2"), new BigDecimal("2"), "Carrier shortage", "QUALITY_HOLD", movementId,
            null, TrackingMode.QUANTITY, List.of(),
            "OVERRIDE_APPROVED", "One-time exception", approvedExceptionId
        );

        var result = service.record(new ReceiptPostingDraft(
            poId, supplierId, null, "receipt-key", "USD", new BigDecimal("12650"),
            new BigDecimal("72"), new BigDecimal("910800"), List.of(evidenceId), List.of(line)
        ));

        assertThat(result.receiptId()).isNotNull();
        assertThat(result.excessQuantity()).isEqualByComparingTo("2");
        assertThat(result.shortageQuantity()).isEqualByComparingTo("2");
        assertThat(result.approvedAccreditationExceptionIds()).containsExactly(approvedExceptionId);

        ArgumentCaptor<GoodsReceipt> receiptCaptor = ArgumentCaptor.forClass(GoodsReceipt.class);
        org.mockito.Mockito.verify(receiptRepository).save(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue().getCurrencyCode()).isEqualTo("USD");
        assertThat(receiptCaptor.getValue().getExchangeRate()).isEqualByComparingTo("12650");
        assertThat(receiptCaptor.getValue().getAcceptedAmount()).isEqualByComparingTo("72");
        assertThat(receiptCaptor.getValue().getBaseAcceptedAmount()).isEqualByComparingTo("910800");
        assertThat(receiptCaptor.getValue().getContractId()).isEqualTo(contractId);

        ArgumentCaptor<GoodsReceiptLine> lineCaptor = ArgumentCaptor.forClass(GoodsReceiptLine.class);
        org.mockito.Mockito.verify(lineRepository).save(lineCaptor.capture());
        GoodsReceiptLine savedLine = lineCaptor.getValue();
        assertThat(savedLine.getStockMovementId()).isEqualTo(movementId);
        assertThat(savedLine.getExcessQuantity()).isEqualByComparingTo("2");
        assertThat(savedLine.getShortageQuantity()).isEqualByComparingTo("2");

        ArgumentCaptor<GoodsReceiptEvidence> evidenceCaptor = ArgumentCaptor.forClass(GoodsReceiptEvidence.class);
        org.mockito.Mockito.verify(evidenceRepository).save(evidenceCaptor.capture());
        GoodsReceiptEvidence savedEvidence = evidenceCaptor.getValue();
        assertThat(savedEvidence.getPurchaseOrderId()).isEqualTo(poId);
        assertThat(savedEvidence.getPurchaseOrderItemId()).isEqualTo(itemId);
        assertThat(savedEvidence.getMaterialGroupId()).isEqualTo(materialGroupId);
        assertThat(savedEvidence.getStockMovementId()).isEqualTo(movementId);

        org.mockito.Mockito.verify(auditService).logInCurrentTransaction(
            eq("GoodsReceipt"), eq(result.receiptId()), eq("CREATE"), eq(result.receiptCode()), isNull(), any(GoodsReceipt.class));
        org.mockito.Mockito.verify(auditService).logInCurrentTransaction(
            eq("GoodsReceiptLine"), eq(savedLine.getId()), eq("CREATE"), eq(savedLine.getCode()), isNull(), any(GoodsReceiptLine.class));
        org.mockito.Mockito.verify(auditService).logInCurrentTransaction(
            eq("GoodsReceiptEvidence"), eq(savedEvidence.getId()), eq("CREATE"), eq(savedEvidence.getCode()), isNull(), any(GoodsReceiptEvidence.class));
        org.mockito.Mockito.verify(auditService, times(3)).logInCurrentTransaction(
            any(String.class), any(UUID.class), eq("CREATE"), any(String.class), isNull(), any());
    }

    @Test
    void recordsPhysicalReceiptQuantitySeparatelyFromAcceptedQuantity() {
        UUID poId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            itemId, materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            "LOT-2", new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "PENDING_INSPECTION", null,
            null, TrackingMode.QUANTITY, List.of(),
            "ELIGIBLE", null, null
        );

        service.record(new ReceiptPostingDraft(
            poId, supplierId, null, "receipt-key-2", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ));

        ArgumentCaptor<GoodsReceiptLine> lineCaptor = ArgumentCaptor.forClass(GoodsReceiptLine.class);
        org.mockito.Mockito.verify(lineRepository).save(lineCaptor.capture());
        GoodsReceiptLine savedLine = lineCaptor.getValue();
        assertThat(savedLine.getPhysicalReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(savedLine.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(savedLine.getStockMovementId()).isNull();
        assertThat(savedLine.getQualityStatus()).isEqualTo("PENDING_INSPECTION");
    }

    @Test
    void serializedReceiptCreatesMaterialUnitsOnlyForAcceptedPhysicalUnits() {
        UUID poId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID stockBatchId = UUID.randomUUID();
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            itemId, materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            "SER-BATCH", new BigDecimal("4"), new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("1"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, null, "ACCEPTED", movementId,
            stockBatchId, TrackingMode.SERIAL, List.of(
                new ReceiptUnitPostingDraft(" SN-001 ", "GOOD", "ACCEPTED", null),
                new ReceiptUnitPostingDraft("sn-002", "GOOD", "ACCEPTED", null),
                new ReceiptUnitPostingDraft("SN-003", "DAMAGED", "REJECTED", "Cracked casing")
            ),
            "ELIGIBLE", null, null
        );

        var result = service.record(new ReceiptPostingDraft(
            poId, supplierId, null, "serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ));

        assertThat(result.receiptUnitsRecorded()).isEqualTo(3);
        assertThat(result.materialUnitsCreated()).isEqualTo(2);
        org.mockito.Mockito.verify(receiptUnitRepository, times(5)).save(any(GoodsReceiptUnit.class));
        org.mockito.Mockito.verify(materialUnitRepository, times(2)).save(any(MaterialUnit.class));
        org.mockito.Mockito.verify(materialUnitEventRepository, times(2)).save(any(MaterialUnitEvent.class));
    }

    @Test
    void serializedReceiptCreatesOneMaterialUnitForEveryAcceptedSerial() {
        UUID poId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            itemId, materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            "SER-ALL", new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "ACCEPTED", movementId,
            null, TrackingMode.SERIAL, List.of(
                new ReceiptUnitPostingDraft("SN-001", "GOOD", "ACCEPTED", null),
                new ReceiptUnitPostingDraft("SN-002", "GOOD", "ACCEPTED", null),
                new ReceiptUnitPostingDraft("SN-003", "GOOD", "ACCEPTED", null)
            ),
            "ELIGIBLE", null, null
        );

        var result = service.record(new ReceiptPostingDraft(
            poId, supplierId, null, "serial-all-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ));

        assertThat(result.acceptedQuantity()).isEqualByComparingTo("3");
        assertThat(result.receiptUnitsRecorded()).isEqualTo(3);
        assertThat(result.materialUnitsCreated()).isEqualTo(3);
        ArgumentCaptor<GoodsReceiptLine> lineCaptor = ArgumentCaptor.forClass(GoodsReceiptLine.class);
        org.mockito.Mockito.verify(lineRepository).save(lineCaptor.capture());
        assertThat(lineCaptor.getValue().getAcceptedQuantity()).isEqualByComparingTo("3");
        assertThat(lineCaptor.getValue().getStockMovementId()).isEqualTo(movementId);
        ArgumentCaptor<MaterialUnit> materialUnitCaptor = ArgumentCaptor.forClass(MaterialUnit.class);
        org.mockito.Mockito.verify(materialUnitRepository, times(3)).save(materialUnitCaptor.capture());
        assertThat(materialUnitCaptor.getAllValues())
            .extracting(MaterialUnit::getManufacturerSerialNumber)
            .containsExactly("SN-001", "SN-002", "SN-003");
    }

    @Test
    void batchAndSerialReceiptLinksAcceptedMaterialUnitsToTheirStockBatch() {
        UUID poId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID movementA = UUID.randomUUID();
        UUID movementB = UUID.randomUUID();
        UUID stockBatchA = UUID.randomUUID();
        UUID stockBatchB = UUID.randomUUID();
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            itemId, materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            null, new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("3"), BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "POSTED_WITH_EXCEPTIONS", null,
            null, TrackingMode.SERIAL, List.of(),
            "ELIGIBLE", null, null,
            List.of(
                new ReceiptBatchPostingDraft(
                    "batch-a", movementA, stockBatchA, "B001", null, null,
                    new BigDecimal("2"), BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(
                        new ReceiptUnitPostingDraft("SN-A1", "GOOD", "ACCEPTED", null),
                        new ReceiptUnitPostingDraft("SN-A2", "GOOD", "ACCEPTED", null)
                    )
                ),
                new ReceiptBatchPostingDraft(
                    "batch-b", movementB, stockBatchB, "B002", null, null,
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
                    List.of(
                        new ReceiptUnitPostingDraft("SN-B1", "GOOD", "ACCEPTED", null),
                        new ReceiptUnitPostingDraft("SN-B2", "DAMAGED", "REJECTED", "Rejected during inspection")
                    )
                )
            )
        );

        var result = service.record(new ReceiptPostingDraft(
            poId, supplierId, null, "batch-serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ));

        assertThat(result.receiptUnitsRecorded()).isEqualTo(4);
        assertThat(result.materialUnitsCreated()).isEqualTo(3);
        ArgumentCaptor<GoodsReceiptBatch> receiptBatches = ArgumentCaptor.forClass(GoodsReceiptBatch.class);
        org.mockito.Mockito.verify(receiptBatchRepository, times(2)).save(receiptBatches.capture());
        assertThat(receiptBatches.getAllValues()).extracting(GoodsReceiptBatch::getStockMovementId)
            .containsExactly(movementA, movementB);
        ArgumentCaptor<MaterialUnit> materialUnitCaptor = ArgumentCaptor.forClass(MaterialUnit.class);
        org.mockito.Mockito.verify(materialUnitRepository, times(3)).save(materialUnitCaptor.capture());
        assertThat(materialUnitCaptor.getAllValues())
            .extracting(MaterialUnit::getManufacturerSerialNumber, MaterialUnit::getStockBatchId)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("SN-A1", stockBatchA),
                org.assertj.core.groups.Tuple.tuple("SN-A2", stockBatchA),
                org.assertj.core.groups.Tuple.tuple("SN-B1", stockBatchB)
            );
    }

    @Test
    void serializedReceiptRejectsMismatchedUnitRowsBeforeEvidenceIsSaved() {
        UUID materialId = UUID.randomUUID();
        ReceiptLinePostingDraft line = new ReceiptLinePostingDraft(
            UUID.randomUUID(), materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            null, new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "ACCEPTED", UUID.randomUUID(),
            null, TrackingMode.SERIAL, List.of(
                new ReceiptUnitPostingDraft("SN-001", null, "ACCEPTED", null),
                new ReceiptUnitPostingDraft("SN-002", null, "ACCEPTED", null)
            ),
            "ELIGIBLE", null, null
        );

        assertThatThrownBy(() -> service.record(new ReceiptPostingDraft(
            UUID.randomUUID(), UUID.randomUUID(), null, "bad-serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ))).hasMessageContaining("SERIAL_UNIT_COUNT_MISMATCH");
    }

    @Test
    void serializedReceiptRejectsMissingSerialBeforeReceiptIsSaved() {
        ReceiptLinePostingDraft line = serializedLine(List.of(
            new ReceiptUnitPostingDraft(null, null, "ACCEPTED", null)
        ), BigDecimal.ONE, BigDecimal.ONE);

        assertThatThrownBy(() -> service.record(new ReceiptPostingDraft(
            UUID.randomUUID(), UUID.randomUUID(), null, "missing-serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ))).hasMessageContaining("SERIAL_NUMBER_REQUIRED");

        org.mockito.Mockito.verify(receiptRepository, org.mockito.Mockito.never()).save(any(GoodsReceipt.class));
        org.mockito.Mockito.verify(materialUnitRepository, org.mockito.Mockito.never()).save(any(MaterialUnit.class));
    }

    @Test
    void serializedReceiptRejectsDuplicateRequestSerialBeforeReceiptIsSaved() {
        ReceiptLinePostingDraft line = serializedLine(List.of(
            new ReceiptUnitPostingDraft("SN-DUP", null, "ACCEPTED", null),
            new ReceiptUnitPostingDraft("sn-dup", null, "ACCEPTED", null)
        ), new BigDecimal("2"), new BigDecimal("2"));

        assertThatThrownBy(() -> service.record(new ReceiptPostingDraft(
            UUID.randomUUID(), UUID.randomUUID(), null, "duplicate-request-serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ))).hasMessageContaining("DUPLICATE_SERIAL_NUMBER");

        org.mockito.Mockito.verify(receiptRepository, org.mockito.Mockito.never()).save(any(GoodsReceipt.class));
        org.mockito.Mockito.verify(materialUnitRepository, org.mockito.Mockito.never()).save(any(MaterialUnit.class));
    }

    @Test
    void serializedReceiptRejectsExistingActiveSerialBeforeReceiptIsSaved() {
        UUID materialId = UUID.randomUUID();
        when(materialUnitRepository.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), materialId, "SN-EXISTS"
        )).thenReturn(true);
        ReceiptLinePostingDraft line = serializedLine(
            materialId,
            List.of(new ReceiptUnitPostingDraft("SN-EXISTS", null, "ACCEPTED", null)),
            BigDecimal.ONE,
            BigDecimal.ONE
        );

        assertThatThrownBy(() -> service.record(new ReceiptPostingDraft(
            UUID.randomUUID(), UUID.randomUUID(), null, "existing-serial-key", "UZS", BigDecimal.ONE,
            BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(line)
        ))).hasMessageContaining("SERIAL_ALREADY_REGISTERED");

        org.mockito.Mockito.verify(receiptRepository, org.mockito.Mockito.never()).save(any(GoodsReceipt.class));
        org.mockito.Mockito.verify(materialUnitRepository, org.mockito.Mockito.never()).save(any(MaterialUnit.class));
    }

    private <T extends com.enterprise.mm.common.BusinessEntity> T withId(T entity) {
        if (entity.getId() == null) entity.setId(UUID.randomUUID());
        if (entity.getVersion() == null) entity.setVersion(0L);
        return entity;
    }

    private ReceiptLinePostingDraft serializedLine(
        List<ReceiptUnitPostingDraft> units,
        BigDecimal physicalReceivedQuantity,
        BigDecimal acceptedQuantity
    ) {
        return serializedLine(UUID.randomUUID(), units, physicalReceivedQuantity, acceptedQuantity);
    }

    private ReceiptLinePostingDraft serializedLine(
        UUID materialId,
        List<ReceiptUnitPostingDraft> units,
        BigDecimal physicalReceivedQuantity,
        BigDecimal acceptedQuantity
    ) {
        return new ReceiptLinePostingDraft(
            UUID.randomUUID(), materialId, null, "PCS", UUID.randomUUID(), UUID.randomUUID(), null,
            null, new BigDecimal("10"), physicalReceivedQuantity, acceptedQuantity, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, "ACCEPTED", UUID.randomUUID(),
            null, TrackingMode.SERIAL, units,
            "ELIGIBLE", null, null
        );
    }
}
