package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.enterprise.mm.payload.LaboratoryTestResponse;
import com.enterprise.mm.payload.QualityClaimResponse;
import com.enterprise.mm.payload.QualityControlDtos.LaboratoryResultRequest;
import com.enterprise.mm.payload.QualityControlDtos.LimsLaboratoryResultCallback;
import com.enterprise.mm.payload.QualityControlDtos.QualityActionRequest;
import com.enterprise.mm.payload.QualityControlDtos.QualityDecisionRequest;
import com.enterprise.mm.payload.QualityInspectionResponse;
import com.enterprise.mm.payload.QualityInspectionUpdateRequest;
import com.enterprise.mm.payload.QualityNonconformanceResponse;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.LaboratoryTestCreateRequest;
import com.enterprise.mm.payload.QualityClaimCreateRequest;
import com.enterprise.mm.payload.QualityInspectionCreateRequest;
import com.enterprise.mm.payload.QualityNonconformanceCreateRequest;
import com.enterprise.mm.service.LaboratoryTestService;
import com.enterprise.mm.service.QualityClaimService;
import com.enterprise.mm.service.QualityInspectionService;
import com.enterprise.mm.service.QualityNonconformanceService;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.service.SupplierEvaluationService;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.LaboratoryTestStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.QualityClaimStatus;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.enums.QualityNonconformanceStatus;
import com.enterprise.mm.enums.QualityQuarantineStatus;
import com.enterprise.mm.enums.QualitySeverity;
import com.enterprise.mm.enums.StockMovementStatus;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.QualityQuarantineLotRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.SupplierCorrectiveActionRepository;
import com.enterprise.mm.repository.SupplierEvaluationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "qc-manager", roles = "MM_MANAGER")
class QualityControlWorkflowTest {
    @Autowired MaterialRepository materialRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired StockBalanceRepository stockBalanceRepository;
    @Autowired SupplierCorrectiveActionRepository correctiveActionRepository;
    @Autowired IntegrationEventRepository integrationEventRepository;
    @Autowired QualityQuarantineLotRepository quarantineLotRepository;
    @Autowired StockMovementService stockMovementService;
    @Autowired QualityInspectionService inspectionService;
    @Autowired LaboratoryTestService laboratoryTestService;
    @Autowired QualityNonconformanceService nonconformanceService;
    @Autowired QualityClaimService claimService;
    @Autowired SupplierEvaluationService supplierEvaluationService;
    @Autowired SupplierEvaluationRepository supplierEvaluationRepository;

    @Test
    void incomingQualityControlQuarantinesLabFailsCreatesCapaClaimAndReleasesGoodQuantity() {
        Material material = material("MAT-QC");
        Supplier supplier = supplier("SUP-QC");
        Warehouse warehouse = warehouse("WH-QC");
        PurchaseOrder po = purchaseOrder("PO-QC", supplier.getId());
        PurchaseOrderItem poItem = purchaseOrderItem("POI-QC", po.getId(), material.getId());

        StockMovement receipt = new StockMovement();
        receipt.setCode("GR-QC-" + suffix());
        receipt.setName("QC goods receipt");
        receipt.setMaterialId(material.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(BigDecimal.TEN);
        receipt.setUnitCost(BigDecimal.valueOf(25));
        receipt.setReferenceType("PurchaseOrder");
        receipt.setReferenceId(po.getId());
        StockMovementResponse postedReceipt = stockMovementService.createAndPost(receipt);
        assertThat(postedReceipt.getStatus()).isEqualTo(StockMovementStatus.POSTED.name());
        supplierEvaluationService.evaluateReceipt(po, poItem, postedReceipt.getId(), BigDecimal.TEN, LocalDate.now());

        StockBalance afterReceipt = balance(material.getId(), warehouse.getId(), postedReceipt.getStockBatchId());
        assertThat(afterReceipt.getQuantity()).isEqualByComparingTo("10");
        assertThat(afterReceipt.getAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(afterReceipt.getQuarantineQuantity()).isEqualByComparingTo("0");

        QualityInspectionCreateRequest inspectionRequest = new QualityInspectionCreateRequest();
        inspectionRequest.setName("Incoming QC");
        inspectionRequest.setInspectionNumber("QC-" + suffix());
        inspectionRequest.setSupplierId(supplier.getId());
        inspectionRequest.setPurchaseOrderId(po.getId());
        inspectionRequest.setPurchaseOrderItemId(poItem.getId());
        inspectionRequest.setStockMovementId(postedReceipt.getId());
        inspectionRequest.setReceivedQuantity(BigDecimal.TEN);
        inspectionRequest.setSampleQuantity(BigDecimal.ONE);
        QualityInspectionResponse inspection = inspectionService.create(inspectionRequest);
        inspection = inspectionService.startQuarantine(inspection.getId());
        assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.IN_QUARANTINE.name());

        StockBalance quarantined = balance(material.getId(), warehouse.getId(), postedReceipt.getStockBatchId());
        assertThat(quarantined.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(quarantined.getQuarantineQuantity()).isEqualByComparingTo("10");
        QualityQuarantineLot quarantineLot = quarantineLot(inspection);
        assertThat(quarantineLot.getStatus()).isEqualTo(QualityQuarantineStatus.QUARANTINED.name());
        assertThat(quarantineLot.getQuarantinedQuantity()).isEqualByComparingTo("10");
        assertThat(quarantineLot.getRemainingQuantity()).isEqualByComparingTo("10");

        inspection = inspectionService.startSampling(inspection.getId());
        assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.SAMPLING.name());

        LaboratoryTestCreateRequest labRequest = new LaboratoryTestCreateRequest();
        labRequest.setName("Chemical composition");
        labRequest.setQualityInspectionId(inspection.getId());
        labRequest.setTestType("CHEMICAL_COMPOSITION");
        labRequest.setSpecification("C <= 0.20%");
        LaboratoryTestResponse lab = laboratoryTestService.create(labRequest);
        lab = laboratoryTestService.start(lab.getId());
        assertThat(lab.getStatus()).isEqualTo(LaboratoryTestStatus.IN_PROGRESS.name());
        lab = laboratoryTestService.recordResult(lab.getId(), new LaboratoryResultRequest(false, "C = 0.31%", "Out of specification", null, "Failed chemical composition"));
        assertThat(lab.getStatus()).isEqualTo(LaboratoryTestStatus.FAILED.name());
        assertThat(inspectionService.get(inspection.getId()).getStatus()).isEqualTo(QualityInspectionStatus.FAILED.name());

        QualityNonconformanceCreateRequest ncRequest = new QualityNonconformanceCreateRequest();
        ncRequest.setName("Chemical composition mismatch");
        ncRequest.setQualityInspectionId(inspection.getId());
        ncRequest.setSeverity(QualitySeverity.CRITICAL.name());
        ncRequest.setNonconformanceType("LAB_FAILURE");
        ncRequest.setDetectedQuantity(BigDecimal.valueOf(2));
        ncRequest.setDefectDescription("Carbon content exceeds specification");
        QualityNonconformanceResponse nc = nonconformanceService.create(ncRequest);
        assertThat(nc.isCapaRequired()).isTrue();
        quarantineLot = quarantineLot(inspection);
        assertThat(quarantineLot.getQualityNonconformanceId()).isEqualTo(nc.getId());
        nc = nonconformanceService.requireCapa(nc.getId(), new QualityActionRequest("Critical lab failure"));
        assertThat(nc.getStatus()).isEqualTo(QualityNonconformanceStatus.CAPA_REQUIRED.name());
        assertThat(nc.getCorrectiveActionId()).isNotNull();
        assertThat(correctiveActionRepository.findAnyById(nc.getCorrectiveActionId())).isPresent();

        QualityClaimCreateRequest claimRequest = new QualityClaimCreateRequest();
        claimRequest.setName("Supplier quality claim");
        claimRequest.setQualityNonconformanceId(nc.getId());
        claimRequest.setClaimNumber("QCL-" + suffix());
        claimRequest.setClaimAmount(BigDecimal.valueOf(500));
        claimRequest.setCurrencyCode("USD");
        claimRequest.setRequestedAction("Replace rejected quantity and provide CAPA");
        QualityClaimResponse claim = claimService.create(claimRequest);
        claim = claimService.send(claim.getId());
        assertThat(claim.getStatus()).isEqualTo(QualityClaimStatus.SENT.name());
        assertThat(integrationEventRepository.findAll()).anyMatch(event -> "SUPPLIER.QUALITY_CLAIM_SENT".equals(event.getEventType()));
        assertThat(supplierRepository.findAnyById(supplier.getId()).orElseThrow().getRating()).isLessThan(BigDecimal.valueOf(90));

        claim = claimService.acknowledge(claim.getId(), new QualityActionRequest("Supplier acknowledged claim"));
        assertThat(claim.getStatus()).isEqualTo(QualityClaimStatus.ACKNOWLEDGED.name());
        claim = claimService.requireCapa(claim.getId(), new QualityActionRequest("Claim requires supplier CAPA"));
        assertThat(claim.getStatus()).isEqualTo(QualityClaimStatus.CAPA_REQUIRED.name());
        assertThat(claim.getCorrectiveActionId()).isNotNull();

        UUID inspectionIdForDecision = inspection.getId();
        assertThatThrownBy(() -> inspectionService.release(
            inspectionIdForDecision,
            new QualityDecisionRequest(
                BigDecimal.valueOf(8), BigDecimal.ONE, "One unit left unallocated")
        )).hasMessageContaining("fully allocate received quantity");
        assertThatThrownBy(() -> inspectionService.reject(
            inspectionIdForDecision,
            new QualityDecisionRequest(
                BigDecimal.valueOf(8), BigDecimal.ONE, "One unit left unallocated")
        )).hasMessageContaining("fully allocate received quantity");
        QualityInspectionResponse unchanged = inspectionService.get(inspection.getId());
        assertThat(unchanged.getStatus()).isEqualTo(QualityInspectionStatus.FAILED.name());
        assertThat(unchanged.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(unchanged.getRejectedQuantity()).isEqualByComparingTo("0");
        assertThat(unchanged.getQuarantinedQuantity()).isEqualByComparingTo("10");
        StockBalance unchangedBalance = balance(
            material.getId(), warehouse.getId(), postedReceipt.getStockBatchId());
        assertThat(unchangedBalance.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(unchangedBalance.getQuarantineQuantity()).isEqualByComparingTo("10");
        assertThat(quarantineLot(unchanged).getRemainingQuantity()).isEqualByComparingTo("10");

        inspection = inspectionService.release(inspection.getId(), new QualityDecisionRequest(BigDecimal.valueOf(8), BigDecimal.valueOf(2), "8 accepted, 2 kept in quarantine for claim"));
        assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.RELEASED.name());
        assertThat(inspection.getAcceptedQuantity()).isEqualByComparingTo("8");
        assertThat(inspection.getRejectedQuantity()).isEqualByComparingTo("2");
        assertThat(purchaseOrderItemRepository.findById(poItem.getId()).orElseThrow().getAcceptedQuantity())
            .isEqualByComparingTo("8");
        assertThat(supplierEvaluationRepository.findByTenantIdAndCompanyIdAndStockMovementIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            inspection.getCompanyId(),
            postedReceipt.getId()
        )).get().satisfies(evaluation -> {
            assertThat(evaluation.getEvaluationSource()).isEqualTo("QUALITY_INSPECTION");
            assertThat(evaluation.getAcceptedQuantity()).isEqualByComparingTo("8");
            assertThat(evaluation.getRejectedQuantity()).isEqualByComparingTo("2");
            assertThat(evaluation.getQualityScore()).isLessThan(BigDecimal.valueOf(80));
        });

        StockBalance afterRelease = balance(material.getId(), warehouse.getId(), postedReceipt.getStockBatchId());
        assertThat(afterRelease.getQuantity()).isEqualByComparingTo("10");
        assertThat(afterRelease.getAvailableQuantity()).isEqualByComparingTo("8");
        assertThat(afterRelease.getQuarantineQuantity()).isEqualByComparingTo("2");
        quarantineLot = quarantineLot(inspection);
        assertThat(quarantineLot.getStatus()).isEqualTo(QualityQuarantineStatus.REJECTED.name());
        assertThat(quarantineLot.getRejectedQuantity()).isEqualByComparingTo("2");
        assertThat(quarantineLot.getReleasedQuantity()).isEqualByComparingTo("8");
        assertThat(quarantineLot.getRemainingQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void successfulLabSetPassesInspectionAndWorkflowManagedFieldsCannotBeOverwritten() {
        Material material = material("MAT-QC-PASS");
        Supplier supplier = supplier("SUP-QC-PASS");
        Warehouse warehouse = warehouse("WH-QC-PASS");
        PurchaseOrder po = purchaseOrder("PO-QC-PASS", supplier.getId());
        PurchaseOrderItem poItem = purchaseOrderItem(
            "POI-QC-PASS", po.getId(), material.getId());

        StockMovement receipt = new StockMovement();
        receipt.setCode("GR-QC-PASS-" + suffix());
        receipt.setName("Passing QC goods receipt");
        receipt.setMaterialId(material.getId());
        receipt.setWarehouseId(warehouse.getId());
        receipt.setMovementType(MovementType.RECEIPT);
        receipt.setQuantity(BigDecimal.TEN);
        receipt.setUnitCost(BigDecimal.valueOf(25));
        receipt.setReferenceType("PurchaseOrder");
        receipt.setReferenceId(po.getId());
        StockMovementResponse postedReceipt = stockMovementService.createAndPost(receipt);
        supplierEvaluationService.evaluateReceipt(
            po, poItem, postedReceipt.getId(), BigDecimal.TEN, LocalDate.now());

        QualityInspectionCreateRequest createRequest = new QualityInspectionCreateRequest();
        createRequest.setName("Incoming passing QC");
        createRequest.setInspectionNumber("QC-PASS-" + suffix());
        createRequest.setSupplierId(supplier.getId());
        createRequest.setPurchaseOrderId(po.getId());
        createRequest.setPurchaseOrderItemId(poItem.getId());
        createRequest.setStockMovementId(postedReceipt.getId());
        createRequest.setReceivedQuantity(BigDecimal.TEN);
        createRequest.setSampleQuantity(BigDecimal.ONE);
        QualityInspectionResponse inspection = inspectionService.create(createRequest);
        inspection = inspectionService.startQuarantine(inspection.getId());
        inspection = inspectionService.startSampling(inspection.getId());

        Supplier alternateSupplier = supplier("SUP-QC-ALT");
        PurchaseOrder alternatePo = purchaseOrder("PO-QC-ALT", alternateSupplier.getId());
        PurchaseOrderItem alternatePoItem = purchaseOrderItem(
            "POI-QC-ALT", alternatePo.getId(), material.getId());
        LocalDateTime originalQuarantineStartedAt = inspection.getQuarantineStartedAt();
        LocalDateTime originalInspectionStartedAt = inspection.getInspectionStartedAt();
        QualityInspectionUpdateRequest metadataUpdate = new QualityInspectionUpdateRequest();
        metadataUpdate.setCode(inspection.getCode());
        metadataUpdate.setName("Updated inspection metadata");
        metadataUpdate.setDescription("Operator-visible metadata update");
        metadataUpdate.setInspectionNumber(inspection.getInspectionNumber());
        metadataUpdate.setInspectionType(inspection.getInspectionType());
        metadataUpdate.setInspectionDate(LocalDate.now());
        metadataUpdate.setDueDate(LocalDate.now().plusDays(2));
        metadataUpdate.setSampleQuantity(BigDecimal.valueOf(2));
        metadataUpdate.setConclusion("Metadata-only conclusion");
        metadataUpdate.setStatus(QualityInspectionStatus.DRAFT.name());
        metadataUpdate.setSupplierId(alternateSupplier.getId());
        metadataUpdate.setPurchaseOrderId(alternatePo.getId());
        metadataUpdate.setPurchaseOrderItemId(alternatePoItem.getId());
        metadataUpdate.setStockMovementId(postedReceipt.getId());
        metadataUpdate.setMaterialId(material.getId());
        metadataUpdate.setWarehouseId(warehouse.getId());
        metadataUpdate.setReceivedQuantity(BigDecimal.valueOf(99));
        metadataUpdate.setAcceptedQuantity(BigDecimal.valueOf(40));
        metadataUpdate.setRejectedQuantity(BigDecimal.valueOf(30));
        metadataUpdate.setQuarantinedQuantity(BigDecimal.valueOf(29));
        metadataUpdate.setQuarantineStartedAt(LocalDateTime.now().plusDays(1));
        metadataUpdate.setQuarantineReleasedAt(LocalDateTime.now().plusDays(1));
        metadataUpdate.setInspectionStartedAt(LocalDateTime.now().plusDays(1));
        metadataUpdate.setCompletedAt(LocalDateTime.now().plusDays(1));

        inspection = inspectionService.update(inspection.getId(), metadataUpdate);

        assertThat(inspection.getName()).contains("Updated inspection metadata");
        assertThat(inspection.getDueDate()).isEqualTo(LocalDate.now().plusDays(2));
        assertThat(inspection.getSampleQuantity()).isEqualByComparingTo("2");
        assertThat(inspection.getStatus()).isEqualTo(QualityInspectionStatus.SAMPLING.name());
        assertThat(inspection.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(inspection.getPurchaseOrderId()).isEqualTo(po.getId());
        assertThat(inspection.getPurchaseOrderItemId()).isEqualTo(poItem.getId());
        assertThat(inspection.getStockMovementId()).isEqualTo(postedReceipt.getId());
        assertThat(inspection.getMaterialId()).isEqualTo(material.getId());
        assertThat(inspection.getWarehouseId()).isEqualTo(warehouse.getId());
        assertThat(inspection.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(inspection.getAcceptedQuantity()).isEqualByComparingTo("0");
        assertThat(inspection.getRejectedQuantity()).isEqualByComparingTo("0");
        assertThat(inspection.getQuarantinedQuantity()).isEqualByComparingTo("10");
        assertThat(inspection.getQuarantineStartedAt()).isEqualTo(originalQuarantineStartedAt);
        assertThat(inspection.getInspectionStartedAt())
            .isCloseTo(originalInspectionStartedAt, within(1, ChronoUnit.MICROS));
        assertThat(inspection.getQuarantineReleasedAt()).isNull();
        assertThat(inspection.getCompletedAt()).isNull();

        LaboratoryTestCreateRequest firstRequest = new LaboratoryTestCreateRequest();
        firstRequest.setName("Chemical composition");
        firstRequest.setQualityInspectionId(inspection.getId());
        firstRequest.setTestType("CHEMICAL_COMPOSITION");
        LaboratoryTestResponse first = laboratoryTestService.create(firstRequest);
        LaboratoryTestCreateRequest secondRequest = new LaboratoryTestCreateRequest();
        secondRequest.setName("Mechanical properties");
        secondRequest.setQualityInspectionId(inspection.getId());
        secondRequest.setTestType("MECHANICAL_PROPERTIES");
        LaboratoryTestResponse second = laboratoryTestService.create(secondRequest);

        first = laboratoryTestService.start(first.getId());
        first = laboratoryTestService.recordResult(first.getId(), new LaboratoryResultRequest(
            true, "C = 0.18%", "Within specification", null, "Passed chemical test"));
        assertThat(first.getStatus()).isEqualTo(LaboratoryTestStatus.PASSED.name());
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.LAB_TESTING.name());

        second = laboratoryTestService.start(second.getId());
        second = laboratoryTestService.recordResult(second.getId(), new LaboratoryResultRequest(
            true, "Yield 355 MPa", "Within specification", null, "Passed mechanical test"));
        assertThat(second.getStatus()).isEqualTo(LaboratoryTestStatus.PASSED.name());
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.PASSED.name());

        LaboratoryTestCreateRequest postPassRequest = new LaboratoryTestCreateRequest();
        postPassRequest.setName("Late test after passed decision state");
        postPassRequest.setQualityInspectionId(inspection.getId());
        postPassRequest.setTestType("LATE_TEST");
        assertThatThrownBy(() -> laboratoryTestService.create(postPassRequest))
            .hasMessageContaining("cannot be created when quality inspection status is PASSED");
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.PASSED.name());

        QualityInspectionResponse released = inspectionService.release(
            inspection.getId(),
            new QualityDecisionRequest(BigDecimal.TEN, BigDecimal.ZERO, "Full release after passed tests")
        );
        assertThat(released.getStatus()).isEqualTo(QualityInspectionStatus.RELEASED.name());
        assertThatThrownBy(() -> inspectionService.release(
            released.getId(),
            new QualityDecisionRequest(BigDecimal.TEN, BigDecimal.ZERO, "Repeated release")
        )).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> inspectionService.reject(
            released.getId(),
            new QualityDecisionRequest(BigDecimal.ZERO, BigDecimal.TEN, "Repeated rejection")
        )).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> inspectionService.cancel(released.getId()))
            .hasMessageContaining("not allowed");
    }

    @Test
    void heldInspectionCannotBeCancelledAndLaboratoryResultDoesNotReleaseStock() {
        Material material = material("MAT-QC-LATE-LIMS");
        Warehouse warehouse = warehouse("WH-QC-LATE-LIMS");
        StockBalance balance = new StockBalance();
        balance.setCode("BAL-QC-LATE-LIMS-" + suffix());
        balance.setName("Late LIMS callback inspection stock");
        balance.setStatus("ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setQuantity(BigDecimal.ONE);
        balance.setAvailableQuantity(BigDecimal.ONE);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.ZERO);
        balance.setTotalValue(BigDecimal.valueOf(25));
        stockBalanceRepository.save(balance);

        QualityInspectionCreateRequest inspectionRequest = new QualityInspectionCreateRequest();
        inspectionRequest.setName("Cancelled inspection with late LIMS callback");
        inspectionRequest.setInspectionNumber("QC-LATE-LIMS-" + suffix());
        inspectionRequest.setMaterialId(material.getId());
        inspectionRequest.setWarehouseId(warehouse.getId());
        inspectionRequest.setReceivedQuantity(BigDecimal.ONE);
        inspectionRequest.setSampleQuantity(BigDecimal.ONE);
        QualityInspectionResponse inspection = inspectionService.create(inspectionRequest);
        inspection = inspectionService.startQuarantine(inspection.getId());
        inspection = inspectionService.startSampling(inspection.getId());

        LaboratoryTestCreateRequest laboratoryRequest = new LaboratoryTestCreateRequest();
        laboratoryRequest.setName("Late-callback laboratory test");
        laboratoryRequest.setQualityInspectionId(inspection.getId());
        laboratoryRequest.setSampleNumber("SAMPLE-LATE-LIMS-" + suffix());
        laboratoryRequest.setTestType("CHEMICAL_COMPOSITION");
        LaboratoryTestResponse laboratoryTest = laboratoryTestService.create(laboratoryRequest);
        laboratoryTest = laboratoryTestService.start(laboratoryTest.getId());
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.LAB_TESTING.name());

        UUID heldInspectionId = inspection.getId();
        assertThatThrownBy(() -> inspectionService.cancel(heldInspectionId)).hasMessageContaining("explicit quality decision");
        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()).getFirst().getAvailableQuantity()).isZero();

        LaboratoryTestResponse completed = laboratoryTestService.applyLimsResult(
            new LimsLaboratoryResultCallback(
                laboratoryTest.getId(),
                "LIMS-LATE-" + suffix(),
                laboratoryTest.getSampleNumber(),
                "RESULT_RECEIVED",
                true,
                "C = 0.18%",
                "Within specification",
                null,
                "Late result after inspection cancellation"
            )
        );

        assertThat(completed.getStatus()).isEqualTo(LaboratoryTestStatus.PASSED.name());
        assertThat(completed.getLimsStatus()).isEqualTo("RESULT_RECEIVED");
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.PASSED.name());
        assertThat(stockBalanceRepository.findByTenantIdAndMaterial_IdAndWarehouse_IdAndIsDeletedFalse(TenantContext.currentTenant(), material.getId(), warehouse.getId()).getFirst().getAvailableQuantity()).isZero();
    }

    @Test
    void manualResultCannotBypassInspectionLaboratoryLifecycle() {
        Material material = material("MAT-QC-EARLY-RESULT");
        Warehouse warehouse = warehouse("WH-QC-EARLY-RESULT");
        QualityInspectionCreateRequest inspectionRequest = new QualityInspectionCreateRequest();
        inspectionRequest.setName("Draft inspection with premature manual result");
        inspectionRequest.setInspectionNumber("QC-EARLY-RESULT-" + suffix());
        inspectionRequest.setMaterialId(material.getId());
        inspectionRequest.setWarehouseId(warehouse.getId());
        inspectionRequest.setReceivedQuantity(BigDecimal.ONE);
        inspectionRequest.setSampleQuantity(BigDecimal.ONE);
        QualityInspectionResponse inspection = inspectionService.create(inspectionRequest);

        LaboratoryTestCreateRequest laboratoryRequest = new LaboratoryTestCreateRequest();
        laboratoryRequest.setName("Premature manual-result test");
        laboratoryRequest.setQualityInspectionId(inspection.getId());
        laboratoryRequest.setTestType("CHEMICAL_COMPOSITION");
        LaboratoryTestResponse laboratoryTest = laboratoryTestService.create(laboratoryRequest);

        assertThatThrownBy(() -> laboratoryTestService.recordResult(
            laboratoryTest.getId(),
            new LaboratoryResultRequest(
                true, "C = 0.18%", "Within specification", null, "Premature result")
        )).hasMessageContaining("must be IN_QUARANTINE, SAMPLING or LAB_TESTING");
        assertThat(laboratoryTestService.get(laboratoryTest.getId()).getStatus())
            .isEqualTo(LaboratoryTestStatus.REQUESTED.name());
        assertThat(inspectionService.get(inspection.getId()).getStatus())
            .isEqualTo(QualityInspectionStatus.DRAFT.name());
    }

    private StockBalance balance(UUID materialId, UUID warehouseId, UUID batchId) {
        return stockBalanceRepository.findBalance(
            TenantContext.currentTenant(),
            materialId,
            warehouseId,
            true,
            null,
            false,
            batchId
        ).orElseThrow();
    }

    private QualityQuarantineLot quarantineLot(QualityInspectionResponse inspection) {
        return quarantineLotRepository.findByTenantIdAndCompanyIdAndQualityInspection_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            inspection.getCompanyId(),
            inspection.getId()
        ).stream().findFirst().orElseThrow();
    }

    @Autowired com.enterprise.mm.repository.UnitOfMeasureRepository stockUnits;
    private Material material(String prefix) {
        String code = prefix + "-" + suffix();
        Material material = new Material();
        var unit=new com.enterprise.mm.entity.UnitOfMeasure();unit.setCode("EA-"+suffix());unit.setName("Each");unit.setMeasurementNameShort("EA");unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.QUANTITY);unit=stockUnits.saveAndFlush(unit);
        material.setUnitOfMeasureId(unit.getId());material.setUnitOfMeasure(unit);
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        return materialRepository.save(material);
    }

    private Supplier supplier(String prefix) {
        String code = prefix + "-" + suffix();
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setRating(BigDecimal.valueOf(90));
        supplier.setCategory("A");
        return supplierRepository.save(supplier);
    }

    private Warehouse warehouse(String prefix) {
        String code = prefix + "-" + suffix();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode(code);
        warehouse.setName(code);
        warehouse.setStatus("ACTIVE");
        return warehouseRepository.save(warehouse);
    }

    private PurchaseOrder purchaseOrder(String prefix, UUID supplierId) {
        String code = prefix + "-" + suffix();
        PurchaseOrder po = new PurchaseOrder();
        po.setCode(code);
        po.setName(code);
        po.setStatus(PurchaseOrderStatus.SENT.name());
        po.setSupplierId(supplierId);
        po.setOrderDate(LocalDate.now());
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(7));
        po.setTotalAmount(BigDecimal.valueOf(250));
        po.setCurrencyCode("USD");
        return purchaseOrderRepository.save(po);
    }

    private PurchaseOrderItem purchaseOrderItem(String prefix, UUID purchaseOrderId, UUID materialId) {
        String code = prefix + "-" + suffix();
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode(code);
        item.setName(code);
        item.setStatus("OPEN");
        item.setPurchaseOrderId(purchaseOrderId);
        item.setMaterialId(materialId);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.valueOf(25));
        return purchaseOrderItemRepository.save(item);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
