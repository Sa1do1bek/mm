package com.enterprise.mm.quality.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.LaboratoryTest;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.QualityClaim;
import com.enterprise.mm.entity.QualityInspection;
import com.enterprise.mm.entity.QualityNonconformance;
import com.enterprise.mm.entity.QualityQuarantineLot;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.StockBatch;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierCorrectiveAction;
import com.enterprise.mm.entity.SupplierEvaluation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.LaboratoryTestStatus;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.enums.QualityInspectionStatus;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionDetail;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter;
import com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey;
import com.enterprise.mm.quality.workspace.service.IncomingQualityWorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class IncomingQualityWorkspaceServiceTest {
    private static final String TENANT = "tenant-incoming-quality-detail";

    @Autowired private IncomingQualityWorkspaceService service;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        authenticate("quality-admin", "ADMIN");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void aggregatesTheFailedIncomingQualityCaseWithSafeRelatedContext() throws Exception {
        CaseFixture fixture = failedCase();
        entityManager.flush();
        entityManager.clear();

        InspectionDetail detail = service.detail(fixture.inspectionId());

        assertThat(detail.header().inspectionNumber()).isEqualTo("IQ-FAILED-001");
        assertThat(detail.header().supplier()).satisfies(ref -> {
            assertThat(ref.code()).isEqualTo("SUP-IQ-001");
            assertThat(ref.displayName()).isEqualTo("Industrial Metals LLC");
        });
        assertThat(detail.header().purchaseOrder().code()).isEqualTo("PO-IQ-001");
        assertThat(detail.header().material().displayName()).contains("Alloy steel plate");
        assertThat(detail.header().warehouse().displayName()).contains("Central receiving warehouse");
        assertThat(detail.header().storageLocation().code()).isEqualTo("LOC-IQ-001");
        assertThat(detail.header().stockBatch().code()).isEqualTo("BATCH-IQ-001");
        assertThat(detail.header().nextAction()).isEqualTo("RELEASE_OR_REJECT");

        assertThat(detail.quantities().receivedQuantity()).isEqualByComparingTo("10");
        assertThat(detail.quantities().sampleQuantity()).isEqualByComparingTo("1");
        assertThat(detail.quantities().acceptedQuantity()).isEqualByComparingTo("0");
        assertThat(detail.quantities().rejectedQuantity()).isEqualByComparingTo("0");
        assertThat(detail.quantities().quarantinedQuantity()).isEqualByComparingTo("10");
        assertThat(detail.quantities().remainingQuantity()).isEqualByComparingTo("10");

        assertThat(detail.stockImpact().onHandQuantity()).isEqualByComparingTo("10");
        assertThat(detail.stockImpact().availableQuantity()).isEqualByComparingTo("0");
        assertThat(detail.stockImpact().reservedQuantity()).isEqualByComparingTo("0");
        assertThat(detail.stockImpact().quarantineQuantity()).isEqualByComparingTo("10");
        assertThat(detail.stockImpact().totalValue()).isEqualByComparingTo("250");

        assertThat(detail.quarantine()).extracting(item -> item.id())
            .containsExactly(fixture.quarantineId());
        assertThat(detail.laboratoryTests()).extracting(item -> item.id())
            .containsExactly(fixture.laboratoryTestId());
        assertThat(detail.laboratoryTests().getFirst().status())
            .isEqualTo(LaboratoryTestStatus.FAILED.name());
        assertThat(detail.nonconformances()).extracting(item -> item.id())
            .containsExactly(fixture.nonconformanceId());
        assertThat(detail.nonconformances().getFirst().correctiveAction().id())
            .isEqualTo(fixture.correctiveActionId());
        assertThat(detail.claims()).extracting(item -> item.id())
            .containsExactly(fixture.claimId());

        assertThat(detail.supplierImpact().supplierRating()).isEqualByComparingTo("72");
        assertThat(detail.supplierImpact().qualityScore()).isEqualByComparingTo("55");
        assertThat(detail.evidence()).extracting(item -> item.id())
            .containsExactlyInAnyOrderElementsOf(fixture.evidenceIds());
        assertThat(detail.blockers()).extracting(blocker -> blocker.code())
            .contains("QUALITY_DECISION_REQUIRED");
        assertThat(detail.allowedActions()).filteredOn(action -> action.enabled())
            .extracting(action -> action.code())
            .containsExactlyInAnyOrder("RELEASE", "REJECT");
        assertThat(detail.dataScope()).isNotBlank();

        String serialized = objectMapper.writeValueAsString(detail);
        assertThat(serialized)
            .doesNotContain("private/storage/key")
            .doesNotContain("legacy/storage/key")
            .doesNotContain("passport/storage/key")
            .doesNotContain("lab/protocol/storage/key")
            .doesNotContain("RAW-INTEGRATION-SECRET")
            .doesNotContain("checksumSha256")
            .doesNotContain("storageKey")
            .doesNotContain("payload");
    }

    @Test
    void mergesProductionChildAuditAndIntegrationSources() {
        CaseFixture fixture = failedCase();
        entityManager.flush();
        entityManager.clear();

        InspectionDetail detail = service.detail(fixture.inspectionId());

        assertThat(detail.auditTimeline()).extracting(item -> item.action())
            .containsExactly(
                "SEND_TO_LAB",
                "CREATE_FOR_INSPECTION",
                "LIMS_RESULT",
                "REQUIRE_CAPA",
                "SEND"
            );
        assertThat(detail.integrationImpact()).extracting(item -> item.eventType())
            .containsExactlyInAnyOrder(
                "QUALITY_INSPECTION_DECISION_REQUIRED",
                "LIMS_LAB_TEST_REQUESTED",
                "LIMS_LAB_RESULT_RECEIVED",
                "SUPPLIER.QUALITY_CLAIM_SENT"
            );

        JsonNode detailJson = objectMapper.valueToTree(detail);
        assertThat(StreamSupport.stream(
            detailJson.path("auditTimeline").spliterator(), false)
            .map(item -> item.path("entityType").asText())
            .toList()).containsExactly(
                "QualityInspection",
                "QualityQuarantineLot",
                "LaboratoryTest",
                "QualityNonconformance",
                "QualityClaim"
            );
        assertThat(StreamSupport.stream(
            detailJson.path("auditTimeline").spliterator(), false)
            .map(item -> item.path("entityId").asText())
            .toList()).contains(
                fixture.inspectionId().toString(),
                fixture.quarantineId().toString(),
                fixture.laboratoryTestId().toString(),
                fixture.nonconformanceId().toString(),
                fixture.claimId().toString()
            );
        JsonNode labResultEvent = StreamSupport.stream(
            detailJson.path("integrationImpact").spliterator(), false)
            .filter(item -> "LIMS_LAB_RESULT_RECEIVED".equals(item.path("eventType").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(labResultEvent.path("aggregateType").asText()).isEqualTo("LaboratoryTest");
        assertThat(labResultEvent.path("aggregateId").asText())
            .isEqualTo(fixture.laboratoryTestId().toString());
    }

    @Test
    void sanitizesAuditAndIntegrationTextWithSharedRedactorRules() throws Exception {
        CaseFixture fixture = failedCase();
        entityManager.flush();
        entityManager.clear();

        String serialized = objectMapper.writeValueAsString(service.detail(fixture.inspectionId()));

        assertThat(serialized)
            .doesNotContain("abc.def.ghi")
            .doesNotContain("QWxhZGRpbjpvcGVuU2VzYW1l")
            .doesNotContain("cook123")
            .doesNotContain("sig123")
            .doesNotContain("cred123")
            .doesNotContain("RAW-INTEGRATION-SECRET")
            .doesNotContain("payload")
            .contains("***REDACTED***");
    }

    @Test
    void readOnlyUserReceivesTheCaseWithoutEnabledMutationActions() {
        CaseFixture fixture = failedCase();
        entityManager.flush();
        authenticate("quality-viewer", "VIEWER");

        InspectionDetail detail = service.detail(fixture.inspectionId());

        assertThat(detail.header().inspectionNumber()).isEqualTo("IQ-FAILED-001");
        assertThat(detail.allowedActions()).isNotEmpty().allSatisfy(action -> {
            assertThat(action.enabled()).isFalse();
            assertThat(action.disabledReason()).isEqualTo("Current role cannot execute this action");
        });
    }

    @Test
    void listUsesEffectiveWarehouseScopeAndDetailHidesOutOfScopeInspection() {
        CaseFixture fixture = failedCase();
        Warehouse otherWarehouse = persist(base(
            new Warehouse(), "WH-IQ-OTHER", "Other warehouse", "ACTIVE"));
        QualityInspection other = inspection(
            "IQ-OTHER-WAREHOUSE",
            QualityInspectionStatus.DRAFT,
            fixture.supplierId(),
            fixture.materialId(),
            otherWarehouse.getId()
        );
        Employee employee = base(new Employee(), "quality-warehouse-user", "Quality warehouse user", "ACTIVE");
        employee.setPersonnelNumber("quality-warehouse-user");
        employee.setWarehouse(entityManager.getReference(Warehouse.class, fixture.warehouseId()));
        persist(employee);
        entityManager.flush();
        entityManager.clear();
        authenticate("quality-warehouse-user", "WAREHOUSE_MANAGER");

        var result = service.list(
            new QueueFilter(QueueKey.OPEN, null, null, null),
            PageRequest.of(0, 25)
        );

        assertThat(result.items()).extracting(item -> item.id())
            .contains(fixture.inspectionId())
            .doesNotContain(other.getId());
        assertThat(result.appliedFilters().warehouseId()).isEqualTo(fixture.warehouseId());
        assertThatThrownBy(() -> service.detail(other.getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Incoming quality inspection not found");
    }

    @Test
    void entityReferenceUsesNeutralFallbackInsteadOfRawUuid() {
        Material unnamedMaterial = base(new Material(), null, " ", "ACTIVE");
        persist(unnamedMaterial);
        Warehouse warehouse = persist(base(
            new Warehouse(), "WH-IQ-NEUTRAL", "Neutral fallback warehouse", "ACTIVE"));
        QualityInspection inspection = inspection(
            "IQ-NEUTRAL-FALLBACK",
            QualityInspectionStatus.DRAFT,
            null,
            unnamedMaterial.getId(),
            warehouse.getId()
        );
        entityManager.flush();
        entityManager.clear();

        InspectionDetail detail = service.detail(inspection.getId());

        assertThat(detail.header().material().displayName()).contains("Material");
        assertThat(detail.header().material().displayName())
            .doesNotContain(unnamedMaterial.getId().toString());
    }

    private CaseFixture failedCase() {
        Supplier supplier = base(new Supplier(), "SUP-IQ-001", "Supplier registry name", "ACTIVE");
        supplier.setLegalName("Industrial Metals LLC");
        supplier.setRating(new BigDecimal("72"));
        supplier.setCategory("B");
        persist(supplier);

        Material material = persist(base(
            new Material(), "MAT-IQ-001", "Alloy steel plate", "ACTIVE"));
        Warehouse warehouse = persist(base(
            new Warehouse(), "WH-IQ-001", "Central receiving warehouse", "ACTIVE"));
        StorageLocation location = base(
            new StorageLocation(), "LOC-IQ-001", "Quarantine zone A", "ACTIVE");
        location.setWarehouseId(warehouse.getId());
        persist(location);
        StockBatch batch = base(
            new StockBatch(), "BATCH-IQ-001", "Heat H-100", "ACTIVE");
        batch.setBatchNumber("H-100");
        batch.setMaterialId(material.getId());
        batch.setWarehouseId(warehouse.getId());
        batch.setQuantity(BigDecimal.TEN);
        persist(batch);

        PurchaseOrder po = base(new PurchaseOrder(), "PO-IQ-001", "Steel supply order", "SENT");
        po.setSupplierId(supplier.getId());
        po.setOrderDate(LocalDate.now().minusDays(5));
        persist(po);
        PurchaseOrderItem poItem = base(
            new PurchaseOrderItem(), "POI-IQ-001", "Steel plate line", "OPEN");
        poItem.setPurchaseOrderId(po.getId());
        poItem.setMaterialId(material.getId());
        poItem.setQuantity(BigDecimal.TEN);
        poItem.setUnitPrice(new BigDecimal("25"));
        persist(poItem);

        StockMovement movement = base(
            new StockMovement(), "GR-IQ-001", "Posted goods receipt", "POSTED");
        movement.setMaterialId(material.getId());
        movement.setWarehouseId(warehouse.getId());
        movement.setStorageLocationId(location.getId());
        movement.setStockBatchId(batch.getId());
        movement.setMovementType(MovementType.RECEIPT);
        movement.setQuantity(BigDecimal.TEN);
        movement.setUnitCost(new BigDecimal("25"));
        movement.setTotalCost(new BigDecimal("250"));
        movement.setReferenceType("PurchaseOrder");
        movement.setReferenceId(po.getId());
        persist(movement);

        QualityInspection inspection = base(
            new QualityInspection(), "IQ-FAILED-001", "Incoming inspection IQ-FAILED-001", "FAILED");
        inspection.setInspectionNumber("IQ-FAILED-001");
        inspection.setInspectionType("INCOMING");
        inspection.setSupplierId(supplier.getId());
        inspection.setPurchaseOrderId(po.getId());
        inspection.setPurchaseOrderItemId(poItem.getId());
        inspection.setStockMovementId(movement.getId());
        inspection.setMaterialId(material.getId());
        inspection.setWarehouseId(warehouse.getId());
        inspection.setStorageLocationId(location.getId());
        inspection.setStockBatchId(batch.getId());
        inspection.setReceivedQuantity(BigDecimal.TEN);
        inspection.setSampleQuantity(BigDecimal.ONE);
        inspection.setAcceptedQuantity(BigDecimal.ZERO);
        inspection.setRejectedQuantity(BigDecimal.ZERO);
        inspection.setQuarantinedQuantity(BigDecimal.TEN);
        inspection.setInspectionDate(LocalDate.now().minusDays(2));
        inspection.setDueDate(LocalDate.now().minusDays(1));
        inspection.setQuarantineStartedAt(LocalDateTime.now().minusDays(2));
        inspection.setInspectionStartedAt(LocalDateTime.now().minusDays(1));
        inspection.setConclusion("Laboratory result is outside specification");
        persist(inspection);

        StockBalance balance = base(
            new StockBalance(), "BAL-IQ-001", "Inspection stock balance", "ACTIVE");
        balance.setMaterialId(material.getId());
        balance.setWarehouseId(warehouse.getId());
        balance.setStorageLocationId(location.getId());
        balance.setStockBatchId(batch.getId());
        balance.setQuantity(BigDecimal.TEN);
        balance.setAvailableQuantity(BigDecimal.ZERO);
        balance.setReservedQuantity(BigDecimal.ZERO);
        balance.setQuarantineQuantity(BigDecimal.TEN);
        balance.setTotalValue(new BigDecimal("250"));
        balance.setBalanceAt(LocalDateTime.now());
        persist(balance);

        QualityQuarantineLot quarantine = base(
            new QualityQuarantineLot(), "QL-IQ-001", "Inspection quarantine lot", "QUARANTINED");
        quarantine.setQuarantineNumber("QL-IQ-001");
        quarantine.setQualityInspectionId(inspection.getId());
        quarantine.setSupplierId(supplier.getId());
        quarantine.setPurchaseOrderId(po.getId());
        quarantine.setPurchaseOrderItemId(poItem.getId());
        quarantine.setStockMovementId(movement.getId());
        quarantine.setMaterialId(material.getId());
        quarantine.setWarehouseId(warehouse.getId());
        quarantine.setStorageLocationId(location.getId());
        quarantine.setStockBatchId(batch.getId());
        quarantine.setQuarantinedQuantity(BigDecimal.TEN);
        quarantine.setRemainingQuantity(BigDecimal.TEN);
        quarantine.setQuarantineReason("Failed incoming test");
        quarantine.setQuarantineStartedAt(LocalDateTime.now().minusDays(2));
        persist(quarantine);

        LaboratoryTest laboratoryTest = base(
            new LaboratoryTest(), "LAB-IQ-001", "Chemical composition", "FAILED");
        laboratoryTest.setQualityInspectionId(inspection.getId());
        laboratoryTest.setMaterialId(material.getId());
        laboratoryTest.setSampleNumber("SAMPLE-IQ-001");
        laboratoryTest.setTestType("CHEMICAL_COMPOSITION");
        laboratoryTest.setSpecification("C <= 0.20%");
        laboratoryTest.setMeasuredValue("C = 0.31%");
        laboratoryTest.setResult("OUT_OF_SPECIFICATION");
        laboratoryTest.setPassed(false);
        laboratoryTest.setRequestedAt(LocalDateTime.now().minusDays(2));
        laboratoryTest.setStartedAt(LocalDateTime.now().minusDays(1));
        laboratoryTest.setCompletedAt(LocalDateTime.now().minusHours(12));
        laboratoryTest.setLimsSampleId("LIMS-SAMPLE-001");
        laboratoryTest.setLimsStatus("RESULT_RECEIVED");
        persist(laboratoryTest);

        SupplierCorrectiveAction capa = base(
            new SupplierCorrectiveAction(), "CAPA-IQ-001", "Supplier corrective action", "OPEN");
        capa.setSupplierId(supplier.getId());
        capa.setSourceType("QUALITY_NONCONFORMANCE");
        capa.setIssueTitle("Chemical composition mismatch");
        capa.setSeverity("CRITICAL");
        capa.setDueDate(LocalDate.now().plusDays(3));
        persist(capa);

        QualityNonconformance nonconformance = base(
            new QualityNonconformance(), "NC-IQ-001", "Composition mismatch", "CAPA_REQUIRED");
        nonconformance.setQualityInspectionId(inspection.getId());
        nonconformance.setSupplierId(supplier.getId());
        nonconformance.setPurchaseOrderId(po.getId());
        nonconformance.setMaterialId(material.getId());
        nonconformance.setStockMovementId(movement.getId());
        nonconformance.setSeverity("CRITICAL");
        nonconformance.setNonconformanceType("LAB_FAILURE");
        nonconformance.setDetectedQuantity(new BigDecimal("2"));
        nonconformance.setDefectDescription("Carbon content exceeds specification");
        nonconformance.setDisposition("QUARANTINE");
        nonconformance.setCapaRequired(true);
        nonconformance.setCorrectiveActionId(capa.getId());
        nonconformance.setDetectedAt(LocalDateTime.now().minusHours(10));
        persist(nonconformance);
        capa.setSourceId(nonconformance.getId());

        QualityClaim claim = base(
            new QualityClaim(), "CLAIM-IQ-001", "Supplier quality claim", "SENT");
        claim.setClaimNumber("CLAIM-IQ-001");
        claim.setSupplierId(supplier.getId());
        claim.setQualityInspectionId(inspection.getId());
        claim.setQualityNonconformanceId(nonconformance.getId());
        claim.setPurchaseOrderId(po.getId());
        claim.setMaterialId(material.getId());
        claim.setClaimDate(LocalDate.now());
        claim.setClaimAmount(new BigDecimal("500"));
        claim.setCurrencyCode("USD");
        claim.setRequestedAction("Replace rejected quantity and provide CAPA");
        claim.setCorrectiveActionId(capa.getId());
        claim.setSentAt(LocalDateTime.now().minusHours(8));
        persist(claim);
        nonconformance.setClaimId(claim.getId());
        quarantine.setQualityNonconformanceId(nonconformance.getId());

        Attachment currentEvidence = attachment(
            inspection.getId(), "QUALITY_INSPECTION", "certificate.pdf", "private/storage/key");
        Attachment legacyEvidence = attachment(
            inspection.getId(), "QUALITY_INSPECTIONS", "protocol.pdf", "legacy/storage/key");
        Attachment passportEvidence = attachment(
            inspection.getId(), "QUALITY_PASSPORT", "quality-passport.pdf", "passport/storage/key");
        inspection.setQualityPassportAttachmentId(passportEvidence.getId());
        Attachment labProtocolEvidence = attachment(
            laboratoryTest.getId(), "LAB_PROTOCOL", "lab-protocol.pdf", "lab/protocol/storage/key");
        laboratoryTest.setProtocolAttachmentId(labProtocolEvidence.getId());

        audit(
            "AUDIT-IQ-INSPECTION",
            "QualityInspection",
            inspection.getId(),
            "SEND_TO_LAB",
            "Inspection sent to laboratory",
            LocalDateTime.now().minusHours(14)
        );
        audit(
            "AUDIT-IQ-QUARANTINE",
            "QualityQuarantineLot",
            quarantine.getId(),
            "CREATE_FOR_INSPECTION",
            "Lot quarantined",
            LocalDateTime.now().minusHours(13)
        );
        audit(
            "AUDIT-IQ-LAB",
            "LaboratoryTest",
            laboratoryTest.getId(),
            "LIMS_RESULT",
            "cookie=cook123 signature=sig123 credential=cred123",
            LocalDateTime.now().minusHours(12)
        );
        audit(
            "AUDIT-IQ-NC",
            "QualityNonconformance",
            nonconformance.getId(),
            "REQUIRE_CAPA",
            "Corrective action required",
            LocalDateTime.now().minusHours(11)
        );
        audit(
            "AUDIT-IQ-CLAIM",
            "QualityClaim",
            claim.getId(),
            "SEND",
            "Claim sent to supplier",
            LocalDateTime.now().minusHours(10)
        );
        audit(
            "AUDIT-IQ-WRONG-TYPE",
            "PurchaseOrder",
            inspection.getId(),
            "UNRELATED_SAME_UUID",
            "Must not appear",
            LocalDateTime.now().minusHours(9)
        );

        integrationEvent(
            "EVENT-IQ-INSPECTION",
            "QUALITY_INSPECTION_DECISION_REQUIRED",
            "QualityInspection",
            inspection.getId(),
            "Bearer abc.def.ghi",
            null
        );
        integrationEvent(
            "EVENT-IQ-LIMS-REQUEST",
            "LIMS_LAB_TEST_REQUESTED",
            "LIMS",
            laboratoryTest.getId(),
            null,
            null
        );
        integrationEvent(
            "EVENT-IQ-LIMS-RESULT",
            "LIMS_LAB_RESULT_RECEIVED",
            "LaboratoryTest",
            laboratoryTest.getId(),
            null,
            "Basic QWxhZGRpbjpvcGVuU2VzYW1l cookie=cook123 signature=sig123 credential=cred123"
        );
        integrationEvent(
            "EVENT-IQ-CLAIM",
            "SUPPLIER.QUALITY_CLAIM_SENT",
            "SUPPLIER_PORTAL",
            claim.getId(),
            null,
            null
        );
        integrationEvent(
            "EVENT-IQ-WRONG-TYPE",
            "UNRELATED_AGGREGATE_EVENT",
            "PurchaseOrder",
            inspection.getId(),
            null,
            null
        );
        integrationEvent(
            "EVENT-IQ-LAB-WRONG-TYPE",
            "UNRELATED_LAB_AGGREGATE_EVENT",
            "SupplierEvaluation",
            laboratoryTest.getId(),
            null,
            null
        );

        SupplierEvaluation evaluation = base(
            new SupplierEvaluation(), "EVAL-IQ-001", "Inspection supplier impact", "CALCULATED");
        evaluation.setSupplierId(supplier.getId());
        evaluation.setPurchaseOrderId(po.getId());
        evaluation.setPurchaseOrderItemId(poItem.getId());
        evaluation.setStockMovementId(movement.getId());
        evaluation.setMaterialId(material.getId());
        evaluation.setReceivedQuantity(BigDecimal.TEN);
        evaluation.setAcceptedQuantity(BigDecimal.ZERO);
        evaluation.setRejectedQuantity(new BigDecimal("2"));
        evaluation.setQualityScore(new BigDecimal("55"));
        evaluation.setTotalScore(new BigDecimal("68"));
        evaluation.setEvaluationSource("QUALITY_INSPECTION");
        evaluation.setEvaluatedAt(LocalDateTime.now().minusHours(6));
        persist(evaluation);

        QualityInspection unrelated = inspection(
            "IQ-UNRELATED",
            QualityInspectionStatus.FAILED,
            supplier.getId(),
            material.getId(),
            warehouse.getId()
        );
        LaboratoryTest unrelatedLab = base(
            new LaboratoryTest(), "LAB-IQ-OTHER", "Unrelated laboratory test", "FAILED");
        unrelatedLab.setQualityInspectionId(unrelated.getId());
        unrelatedLab.setMaterialId(material.getId());
        unrelatedLab.setSampleNumber("SAMPLE-IQ-OTHER");
        unrelatedLab.setTestType("OTHER");
        persist(unrelatedLab);
        QualityClaim unrelatedClaim = base(
            new QualityClaim(), "CLAIM-IQ-OTHER", "Unrelated claim", "DRAFT");
        unrelatedClaim.setClaimNumber("CLAIM-IQ-OTHER");
        unrelatedClaim.setSupplierId(supplier.getId());
        unrelatedClaim.setQualityInspectionId(unrelated.getId());
        persist(unrelatedClaim);
        attachment(unrelated.getId(), "QUALITY_INSPECTION", "unrelated.pdf", "unrelated/storage/key");
        AuditLog unrelatedAudit = base(
            new AuditLog(), "AUDIT-IQ-OTHER", "Unrelated audit", "RECORDED");
        unrelatedAudit.setEntityType("QualityInspection");
        unrelatedAudit.setEntityId(unrelated.getId());
        unrelatedAudit.setAction("OTHER");
        unrelatedAudit.setOccurredAt(LocalDateTime.now());
        persist(unrelatedAudit);
        IntegrationEvent unrelatedEvent = base(
            new IntegrationEvent(), "EVENT-IQ-OTHER", "Unrelated event", "FAILED");
        unrelatedEvent.setEventType("OTHER");
        unrelatedEvent.setAggregateType("QualityInspection");
        unrelatedEvent.setAggregateId(unrelated.getId());
        unrelatedEvent.setPayload("RAW-INTEGRATION-SECRET-OTHER");
        unrelatedEvent.setIdempotencyKey("event-other-" + UUID.randomUUID());
        persist(unrelatedEvent);

        return new CaseFixture(
            inspection.getId(), supplier.getId(), material.getId(), warehouse.getId(),
            quarantine.getId(), laboratoryTest.getId(), nonconformance.getId(),
            capa.getId(), claim.getId(), List.of(
                currentEvidence.getId(),
                legacyEvidence.getId(),
                passportEvidence.getId(),
                labProtocolEvidence.getId()
            )
        );
    }

    private QualityInspection inspection(
        String number,
        QualityInspectionStatus status,
        UUID supplierId,
        UUID materialId,
        UUID warehouseId
    ) {
        QualityInspection inspection = base(
            new QualityInspection(), number, "Inspection " + number, status.name());
        inspection.setInspectionNumber(number);
        inspection.setInspectionType("INCOMING");
        inspection.setSupplierId(supplierId);
        inspection.setMaterialId(materialId);
        inspection.setWarehouseId(warehouseId);
        inspection.setReceivedQuantity(BigDecimal.ONE);
        inspection.setQuarantinedQuantity(
            status == QualityInspectionStatus.FAILED ? BigDecimal.ONE : BigDecimal.ZERO);
        inspection.setDueDate(LocalDate.now());
        return persist(inspection);
    }

    private AuditLog audit(
        String code,
        String entityType,
        UUID entityId,
        String action,
        String details,
        LocalDateTime occurredAt
    ) {
        AuditLog audit = base(new AuditLog(), code, action, "RECORDED");
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setAction(action);
        audit.setUsername("quality-operator");
        audit.setDetails(details);
        audit.setOccurredAt(occurredAt);
        return persist(audit);
    }

    private IntegrationEvent integrationEvent(
        String code,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String externalId,
        String lastError
    ) {
        IntegrationEvent event = base(
            new IntegrationEvent(), code, eventType, "FAILED");
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload("{\"credential\":\"RAW-INTEGRATION-SECRET\"}");
        event.setExternalId(externalId);
        event.setLastError(lastError);
        event.setRetryCount(2);
        event.setIdempotencyKey(code + "-" + UUID.randomUUID());
        return persist(event);
    }

    private Attachment attachment(UUID inspectionId, String entityType, String fileName, String storageKey) {
        Attachment attachment = base(
            new Attachment(), "ATT-" + UUID.randomUUID(), fileName, "ACTIVE");
        attachment.setEntityType(entityType);
        attachment.setEntityId(inspectionId);
        attachment.setAttachmentType("QUALITY_EVIDENCE");
        attachment.setOriginalFileName(fileName);
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(2048L);
        attachment.setChecksumSha256("a".repeat(64));
        attachment.setStorageProvider("LOCAL");
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setDownloadable(true);
        attachment.setPreviewable(true);
        return persist(attachment);
    }

    private <T extends BusinessEntity> T base(T entity, String code, String name, String status) {
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus(status);
        return entity;
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private record CaseFixture(
        UUID inspectionId,
        UUID supplierId,
        UUID materialId,
        UUID warehouseId,
        UUID quarantineId,
        UUID laboratoryTestId,
        UUID nonconformanceId,
        UUID correctiveActionId,
        UUID claimId,
        List<UUID> evidenceIds
    ) {
    }
}
