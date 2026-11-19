package com.enterprise.mm.supplier.po.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AdvanceShippingNotice;
import com.enterprise.mm.entity.AdvanceShippingNoticeBatch;
import com.enterprise.mm.entity.AdvanceShippingNoticeItem;
import com.enterprise.mm.entity.AdvanceShippingNoticeSerial;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.enums.AdvanceShippingNoticeStatus;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.NotificationService;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

/**
 * Covers ASN commitment-ownership validation and the remaining-quantity calculation
 * (docs/CURRENT_TASK.md section 7). Verification not run -- see final report.
 */
@ExtendWith(MockitoExtension.class)
class SupplierPoAsnServiceTest {
    @Mock private AdvanceShippingNoticeRepository asnRepository;
    @Mock private AdvanceShippingNoticeItemRepository asnItemRepository;
    @Mock private AdvanceShippingNoticeBatchRepository asnBatchRepository;
    @Mock private AdvanceShippingNoticeSerialRepository asnSerialRepository;
    @Mock private SupplierPoCommitmentRepository commitmentRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SupplierPoAsnService service;

    private UUID purchaseOrderId;
    private UUID supplierId;
    private UUID otherSupplierId;
    private UUID materialId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        purchaseOrderId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        otherSupplierId = UUID.randomUUID();
        materialId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void remainingQuantityIsConfirmedMinusActiveShippedQuantity() {
        SupplierPoCommitment commitment = commitment(BigDecimal.TEN, "CONFIRMED");
        when(asnItemRepository.sumActiveQuantityForCommitment(
            commitment.getTenantId(), commitment.getCompanyId(), commitment.getId(), null
        )).thenReturn(new BigDecimal("4"));

        BigDecimal remaining = service.remainingQuantity(commitment);

        assertThat(remaining).isEqualByComparingTo("6");
    }

    @Test
    void remainingQuantityNeverGoesNegative() {
        SupplierPoCommitment commitment = commitment(BigDecimal.ONE, "CONFIRMED");
        when(asnItemRepository.sumActiveQuantityForCommitment(
            commitment.getTenantId(), commitment.getCompanyId(), commitment.getId(), null
        )).thenReturn(new BigDecimal("5"));

        BigDecimal remaining = service.remainingQuantity(commitment);

        assertThat(remaining).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void remainingQuantityQueryCountsSubmittedAsnsNotDrafts() throws Exception {
        Method method = AdvanceShippingNoticeItemRepository.class.getMethod(
            "sumActiveQuantityForCommitment", String.class, UUID.class, UUID.class, UUID.class
        );

        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains("asn.status <> 'DRAFT'");
        assertThat(query).contains("asn.status <> 'CANCELLED'");
    }

    @Test
    void createDraftRejectsCommitmentBelongingToAnotherSupplier() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(BigDecimal.TEN, "CONFIRMED");
        commitment.setSupplierId(otherSupplierId);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(commitment.getId(), BigDecimal.ONE, null, null))
        );

        assertThatThrownBy(() -> service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not belong to the authenticated supplier");
    }

    @Test
    void createDraftRejectsQuantityExceedingRemaining() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(new BigDecimal("5"), "CONFIRMED");
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));
        when(asnItemRepository.sumActiveQuantityForCommitment(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(
                commitment.getId(),
                new BigDecimal("6"),
                new BigDecimal("2"),
                "Pallet",
                new BigDecimal("120"),
                new BigDecimal("100"),
                "BATCH-1",
                List.of("SN-001", "SN-002"),
                LocalDate.of(2028, 1, 1)
            ))
        );

        assertThatThrownBy(() -> service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exceeds remaining");
    }

    @Test
    void createDraftRejectsCommitmentNotConfirmed() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(BigDecimal.TEN, "PROPOSED");
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(commitment.getId(), BigDecimal.ONE, null, null))
        );

        assertThatThrownBy(() -> service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not CONFIRMED");
    }

    @Test
    void supplierAsnFailsClearlyWhenPoItemHasNoMaterial() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(new BigDecimal("6"), "CONFIRMED");
        commitment.getPurchaseOrderItem().setMaterialId(null);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));
        when(asnItemRepository.sumActiveQuantityForCommitment(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(commitment.getId(), BigDecimal.ONE, null, null))
        );

        assertThatThrownBy(() -> service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Purchase order item has no material and cannot be shipped as goods ASN");
    }

    @Test
    void servicePoDoesNotUseShipment() {
        PurchaseOrder po = purchaseOrder();
        po.setRequestType(RequestType.SERVICE);
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(UUID.randomUUID(), BigDecimal.ONE, null, null))
        );

        assertThatThrownBy(() -> service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command))
            .isInstanceOf(BusinessException.class)
            .hasMessage(SupplierPoAsnService.SERVICE_OR_WORK_PO_DOES_NOT_USE_SHIPMENT);
    }

    @Test
    void asnDraftCreatesLineWhenCommitmentHasMaterial() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(new BigDecimal("6"), "CONFIRMED");
        commitment.setProposedQuantity(new BigDecimal("6"));
        commitment.setProposedDate(java.time.LocalDate.now().plusDays(3));
        commitment.setConfirmedDate(commitment.getProposedDate());
        var savedItem = new java.util.concurrent.atomic.AtomicReference<AdvanceShippingNoticeItem>();
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any(AdvanceShippingNotice.class))).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = invocation.getArgument(0);
            if (asn.getId() == null) {
                asn.setId(UUID.randomUUID());
            }
            return asn;
        });
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));
        when(asnItemRepository.sumActiveQuantityForCommitment(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(asnItemRepository.save(any(AdvanceShippingNoticeItem.class))).thenAnswer(invocation -> {
            AdvanceShippingNoticeItem item = invocation.getArgument(0);
            savedItem.set(item);
            return item;
        });
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of())
            .thenAnswer(invocation -> savedItem.get() == null ? List.of() : List.of(savedItem.get()));
        when(asnRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = new AdvanceShippingNotice();
            asn.setId(invocation.getArgument(0));
            asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
            asn.setTotalQuantity(new BigDecimal("6"));
            return Optional.of(asn);
        });

        var command = new SupplierPoAsnService.AsnDraftCommand(
            null, null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(
                commitment.getId(),
                new BigDecimal("6"),
                new BigDecimal("2"),
                "Pallet",
                new BigDecimal("120"),
                new BigDecimal("100"),
                "BATCH-1",
                List.of("SN-001", "SN-002"),
                LocalDate.of(2028, 1, 1)
            ))
        );

        AdvanceShippingNotice draft = service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), command);

        AdvanceShippingNoticeItem item = savedItem.get();
        assertThat(draft.getTotalQuantity()).isEqualByComparingTo("6");
        assertThat(item.getSupplierPoCommitmentId()).isEqualTo(commitment.getId());
        assertThat(item.getMaterialId()).isEqualTo(materialId);
        assertThat(item.getQuantity()).isEqualByComparingTo("6");
        assertThat(item.getPackageCount()).isEqualByComparingTo("2");
        assertThat(item.getPackageType()).isEqualTo("Pallet");
        assertThat(item.getGrossWeight()).isEqualByComparingTo("120");
        assertThat(item.getNetWeight()).isEqualByComparingTo("100");
        assertThat(item.getBatchNumber()).isEqualTo("BATCH-1");
        assertThat(item.getSerialNumbers()).isEqualTo("SN-001\nSN-002");
        assertThat(item.getExpirationDate()).isEqualTo(LocalDate.of(2028, 1, 1));
    }

    @Test
    void draftPersistsMultipleBatchesAndStructuredManufacturerSerials() {
        PurchaseOrder po = purchaseOrder();
        SupplierPoCommitment commitment = commitment(new BigDecimal("6"), "CONFIRMED");
        commitment.getPurchaseOrderItem().setBatchTrackingSnapshot(BatchTrackingPolicy.REQUIRED);
        commitment.getPurchaseOrderItem().setSerialTrackingSnapshot(SerialTrackingPolicy.REQUIRED);
        commitment.getPurchaseOrderItem().setTrackingModeSnapshot(TrackingMode.SERIAL);
        var savedItem = new java.util.concurrent.atomic.AtomicReference<AdvanceShippingNoticeItem>();
        var savedBatches = new java.util.ArrayList<AdvanceShippingNoticeBatch>();
        var savedSerials = new java.util.ArrayList<AdvanceShippingNoticeSerial>();
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.save(any(AdvanceShippingNotice.class))).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = invocation.getArgument(0);
            if (asn.getId() == null) asn.setId(UUID.randomUUID());
            return asn;
        });
        when(commitmentRepository.findByIdAndIsDeletedFalse(commitment.getId())).thenReturn(Optional.of(commitment));
        when(asnItemRepository.sumActiveQuantityForCommitment(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(asnItemRepository.save(any(AdvanceShippingNoticeItem.class))).thenAnswer(invocation -> {
            AdvanceShippingNoticeItem item = invocation.getArgument(0);
            if (item.getId() == null) item.setId(UUID.randomUUID());
            savedItem.set(item);
            return item;
        });
        when(asnBatchRepository.save(any(AdvanceShippingNoticeBatch.class))).thenAnswer(invocation -> {
            AdvanceShippingNoticeBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) batch.setId(UUID.randomUUID());
            savedBatches.add(batch);
            return batch;
        });
        when(asnSerialRepository.save(any(AdvanceShippingNoticeSerial.class))).thenAnswer(invocation -> {
            AdvanceShippingNoticeSerial serial = invocation.getArgument(0);
            savedSerials.add(serial);
            return serial;
        });
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of())
            .thenAnswer(invocation -> savedItem.get() == null ? List.of() : List.of(savedItem.get()));
        when(asnRepository.findByIdAndIsDeletedFalse(any())).thenAnswer(invocation -> {
            AdvanceShippingNotice asn = new AdvanceShippingNotice();
            asn.setId(invocation.getArgument(0));
            asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
            asn.setTotalQuantity(new BigDecimal("6"));
            return Optional.of(asn);
        });

        service.createDraft(purchaseOrderId, supplierId, UUID.randomUUID(), new SupplierPoAsnService.AsnDraftCommand(
            "ASN-STRUCT", null, null, null, null, null, null, null, null,
            List.of(new SupplierPoAsnService.AsnLineCommand(
                commitment.getId(),
                new BigDecimal("6"),
                null, null, null, null,
                null,
                List.of(),
                null,
                List.of(
                    new SupplierPoAsnService.AsnBatchCommand("B-A", new BigDecimal("3"), LocalDate.of(2028, 1, 1)),
                    new SupplierPoAsnService.AsnBatchCommand("B-B", new BigDecimal("3"), LocalDate.of(2028, 6, 1))
                ),
                List.of(
                    new SupplierPoAsnService.AsnSerialCommand("SN-001", "B-A"),
                    new SupplierPoAsnService.AsnSerialCommand("SN-002", "B-A"),
                    new SupplierPoAsnService.AsnSerialCommand("SN-003", "B-A"),
                    new SupplierPoAsnService.AsnSerialCommand("SN-004", "B-B"),
                    new SupplierPoAsnService.AsnSerialCommand("SN-005", "B-B"),
                    new SupplierPoAsnService.AsnSerialCommand("SN-006", "B-B")
                )
            ))
        ));

        assertThat(savedBatches).extracting(AdvanceShippingNoticeBatch::getBatchNumber)
            .containsExactly("B-A", "B-B");
        assertThat(savedBatches).extracting(AdvanceShippingNoticeBatch::getQuantity)
            .containsExactly(new BigDecimal("3"), new BigDecimal("3"));
        assertThat(savedSerials).extracting(AdvanceShippingNoticeSerial::getManufacturerSerialNumber)
            .containsExactly("SN-001", "SN-002", "SN-003", "SN-004", "SN-005", "SN-006");
        assertThat(savedSerials).extracting(AdvanceShippingNoticeSerial::getBatchNumber)
            .containsExactly("B-A", "B-A", "B-A", "B-B", "B-B", "B-B");
    }

    @Test
    void incompleteStructuredTrackingDraftCanBeSavedButSubmitIsRejected() {
        PurchaseOrder po = purchaseOrder();
        UUID asnId = UUID.randomUUID();
        SupplierPoCommitment commitment = commitment(new BigDecimal("6"), "CONFIRMED");
        commitment.getPurchaseOrderItem().setBatchTrackingSnapshot(BatchTrackingPolicy.NONE);
        commitment.getPurchaseOrderItem().setSerialTrackingSnapshot(SerialTrackingPolicy.REQUIRED);
        commitment.getPurchaseOrderItem().setTrackingModeSnapshot(TrackingMode.SERIAL);
        AdvanceShippingNotice asn = new AdvanceShippingNotice();
        asn.setId(asnId);
        asn.setTenantId("default");
        asn.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        asn.setStatus(AdvanceShippingNoticeStatus.DRAFT.name());
        asn.setPurchaseOrderId(purchaseOrderId);
        asn.setSupplierId(supplierId);
        AdvanceShippingNoticeItem item = new AdvanceShippingNoticeItem();
        item.setId(UUID.randomUUID());
        item.setPurchaseOrderItem(commitment.getPurchaseOrderItem());
        item.setPurchaseOrderItemId(commitment.getPurchaseOrderItemId());
        item.setSupplierPoCommitmentId(commitment.getId());
        item.setQuantity(new BigDecimal("6"));
        when(purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)).thenReturn(Optional.of(po));
        when(asnRepository.findByIdAndIsDeletedFalse(asnId)).thenReturn(Optional.of(asn));
        when(asnItemRepository.findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(List.of(item));
        when(asnSerialRepository.findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(any(), any(), any()))
            .thenReturn(List.of(
                asnSerial("SN-001", null),
                asnSerial("SN-002", null),
                asnSerial("SN-003", null)
            ));
        when(idempotencyService.execute(any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(5)).get());

        assertThatThrownBy(() -> service.submit(purchaseOrderId, asnId, supplierId, "idem-serial-incomplete"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ASN_SERIAL_COUNT_MISMATCH");
    }

    @Test
    void hasUnresolvedSubmittedAsnsTrueWhenSentOrConfirmedAsnsExist() {
        when(asnRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusInAndIsDeletedFalse(
            any(), any(), any(), any()
        )).thenReturn(List.of(new AdvanceShippingNotice()));

        assertThat(service.hasUnresolvedSubmittedAsns(purchaseOrderId)).isTrue();
    }

    private PurchaseOrder purchaseOrder() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(purchaseOrderId);
        po.setTenantId("default");
        po.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        po.setCode("PO-1");
        po.setSupplierId(supplierId);
        po.setRequestType(RequestType.GOODS);
        return po;
    }

    private SupplierPoCommitment commitment(BigDecimal confirmedQuantity, String status) {
        SupplierPoCommitment commitment = new SupplierPoCommitment();
        commitment.setId(UUID.randomUUID());
        commitment.setTenantId("default");
        commitment.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        commitment.setCode("COMMIT-1");
        commitment.setStatus(status);
        commitment.setPurchaseOrderId(purchaseOrderId);
        commitment.setSupplierId(supplierId);
        commitment.setConfirmedQuantity(confirmedQuantity);
        commitment.setCurrent(true);
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setMaterialId(materialId);
        var material = new com.enterprise.mm.entity.Material();material.setId(materialId);
        var unit = new com.enterprise.mm.entity.UnitOfMeasure();unit.setId(UUID.randomUUID());unit.setCode("EA");unit.setName("Each");unit.setStatus("ACTIVE");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);material.setUnitOfMeasure(unit);material.setUnitOfMeasureId(unit.getId());item.setMaterial(material);item.setUnit("EA");
        commitment.setPurchaseOrderItem(item);
        commitment.setPurchaseOrderItemId(item.getId());
        return commitment;
    }

    private AdvanceShippingNoticeSerial asnSerial(String serialNumber, String batchNumber) {
        AdvanceShippingNoticeSerial serial = new AdvanceShippingNoticeSerial();
        serial.setManufacturerSerialNumber(serialNumber);
        serial.setBatchNumber(batchNumber);
        return serial;
    }

}
