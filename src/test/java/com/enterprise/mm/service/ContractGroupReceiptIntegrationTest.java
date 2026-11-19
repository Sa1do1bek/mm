package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService;
import com.enterprise.mm.mapper.StockMovementMapper;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"mm.pilot.enabled-capabilities=", "mm.pilot.code-enabled-capabilities="})
@ActiveProfiles("test")
class ContractGroupReceiptIntegrationTest {
    @Autowired ManualWarehouseOperationService service;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StockMovementRepository movements;
    @Autowired StockMovementMapper movementMapper;
    @Autowired WarehouseReceiptProfileService receiptProfiles;
    @Autowired WarehouseReceiptContextService contexts;
    @MockBean RoleDataScopeService scope;
    @MockBean StockMovementService posting;
    // Faktura has PostgreSQL-only storage; these tests exercise the receipt/approval boundary.
    @MockBean
    FakturaReceiptEvidenceService fakturaEvidence;
    @MockBean
    ApprovalEngineService approvalEngine;
    String tenant;
    UUID company, warehouse, otherWarehouse, contract, specification, group, unit, first, second;

    @BeforeEach void setup() {
        tenant = "group-receipt-" + UUID.randomUUID(); company = UUID.randomUUID();
        TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
        tx.executeWithoutResult(status -> {
            Company organization = persist(new Company()); company = organization.getId(); organization.setCompanyId(company);
            CompanyContext.setCompany(company);
            Warehouse w = persist(new Warehouse()); warehouse = w.getId();
            otherWarehouse = persist(new Warehouse()).getId();
            group = persist(new MaterialGroup()).getId();
            UnitOfMeasure u = new UnitOfMeasure(); u.setCode("M"); u.setStockQuantityScale(7);
            unit = persist(u).getId();
            Contract c = new Contract(); c.setSupplierId(persist(new Supplier()).getId());
            contract = persist(c).getId();
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract);
            spec.setMaterialGroupId(group); spec.setUnitOfMeasureId(unit); spec.setQuantityLimit(new BigDecimal("10"));
            spec.setPrice(new BigDecimal("5")); spec.setUnit("m"); specification = persist(spec).getId();
            first = material(group, unit, "Cable A").getId(); second = material(group, unit, "Cable B").getId();
        });
        when(posting.createManualWarehouseDraft(any())).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0);
            movement.setTenantId(tenant); movement.setCompanyId(company); movement.setStatus("DRAFT");
            movement.setName("Receipt draft"); movement.setCode("DRAFT-" + UUID.randomUUID());
            if (movement.getId() == null) em.persist(movement); em.flush(); return movementMapper.toResponse(movement);
        });
        when(posting.postManualWarehouseMovement(any())).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0);
            movement.setTenantId(tenant); movement.setStatus("POSTED"); movement.setName("Group receipt");
            movement.setCode("RECEIPT-" + UUID.randomUUID());
            if (movement.getId() == null) em.persist(movement); em.flush(); return movementMapper.toResponse(movement);
        });
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    private com.enterprise.mm.payload.StockMovementResponse receiptAndApprove(ManualWarehouseOperationRequest request, String key) {
        return tx.execute(status -> {
            var result = service.receipt(request, key);
            approveDocument(result.getDocumentId());
            return result;
        });
    }

    private com.enterprise.mm.payload.ManualWarehouseDocumentResponse receiptBatchAndApprove(ManualWarehouseDocumentRequest request, String key) {
        return tx.execute(status -> {
            var result = service.receiptBatch(request, key);
            approveDocument(result.documentId());
            return result;
        });
    }

    private void approveDocument(UUID id) {
        var document = em.find(StockMovementDocument.class, id);
        if ("POSTED".equals(document.getStatus())) return;
        assertThat(document.getStatus()).isEqualTo("PENDING");
        var lines = movements.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(tenant, id);
        assertThat(lines).allSatisfy(line -> assertThat(line.getStatus()).isNotEqualTo("POSTED"));
        document.setStatus("APPROVED");
        lines.forEach(line -> line.setStatus("APPROVED"));
        service.postApprovedDocument(id);
        assertThat(document.getStatus()).isEqualTo("POSTED");
    }

    @Test void approvalRechecksSharedLimitAfterAnotherPendingDocumentPosts() {
        var pendingA = service.receipt(request(first, warehouse, "6"), "pending-a");
        var pendingB = service.receipt(request(second, otherWarehouse, "6"), "pending-b");
        assertThat(pendingA.getStatus()).isEqualTo("PENDING");
        assertThat(pendingB.getStatus()).isEqualTo("PENDING");
        tx.executeWithoutResult(status -> approveDocument(pendingA.getDocumentId()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> approveDocument(pendingB.getDocumentId())))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        tx.executeWithoutResult(status -> assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification))
            .isEqualByComparingTo("6"));
    }

    @Test void advisoryProfileChangeDoesNotVetoApproval() {
        var pending = service.receipt(request(first, warehouse, "1"), "pending-profile");
        receiptProfiles.update(warehouse, new WarehouseReceiptProfileDtos.UpdateRequest(null, "Deny group",
            List.of(new WarehouseReceiptProfileDtos.Rule(group, WarehouseReceiptProfileDtos.Effect.DENY, false))));
        tx.executeWithoutResult(status -> approveDocument(pending.getDocumentId()));
        tx.executeWithoutResult(status -> assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification))
            .isEqualByComparingTo("1"));
    }

    @Test void twoMembersAcrossWarehousesShareOneLimitAndReplayDoesNotConsumeItTwice() {
        var a = request(first, warehouse, "4");
        var receipt = receiptAndApprove(a, "first");
        assertThat(receiptAndApprove(a, "first").getId()).isEqualTo(receipt.getId());
        receiptAndApprove(request(second, otherWarehouse, "6"), "second");
        assertThatThrownBy(() -> receiptAndApprove(request(first, warehouse, "0.0000001"), "overrun"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        Long count = tx.execute(status -> movements.findAll().stream().filter(m -> tenant.equals(m.getTenantId())).count());
        assertThat(count).isEqualTo(2L);
    }

    @Test void cancelledOrReversedReceiptsReleaseTheSharedLimit() {
        UUID cancelled = receiptAndApprove(request(first, warehouse, "10"), "cancelled").getId();
        tx.executeWithoutResult(status -> em.find(StockMovement.class, cancelled).setStatus("CANCELLED"));
        UUID original = receiptAndApprove(request(second, warehouse, "10"), "replacement").getId();
        tx.executeWithoutResult(status -> {
            // An effective reversal must exclude its original even if a legacy original still says POSTED.
            StockMovement reversal = new StockMovement(); reversal.setMaterialId(second); reversal.setWarehouseId(warehouse);
            reversal.setMovementType(MovementType.ISSUE); reversal.setQuantity(BigDecimal.TEN);
            reversal.setReversalOfId(original); persist(reversal).setStatus("POSTED");
        });
        assertThatCode(() -> receiptAndApprove(request(first, warehouse, "10"), "after-reversal")).doesNotThrowAnyException();
    }

    @Test void inactiveDraftWrongMemberAndWrongUnitCannotExecute() {
        UUID stranger = tx.execute(status -> material(persist(new MaterialGroup()).getId(), unit, "Stranger").getId());
        assertThatThrownBy(() -> receiptAndApprove(request(stranger, warehouse, "1"), "foreign-member"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("group");
        UUID wrongUnit = tx.execute(status -> material(group, persist(new UnitOfMeasure()).getId(), "Different unit").getId());
        assertThatThrownBy(() -> receiptAndApprove(request(wrongUnit, warehouse, "1"), "wrong-unit"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("unit");
        tx.executeWithoutResult(status -> em.find(ContractSpecification.class, specification).setStatus("DRAFT"));
        assertThatThrownBy(() -> receiptAndApprove(request(first, warehouse, "1"), "draft"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
    }

    @Test void groupIssueIsUnsupported() {
        assertThatThrownBy(() -> service.issue(request(first, warehouse, "1"), "issue"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void v2KeepsUnresolvedPositionsVisibleAndPaginatesContractPositions() {
        UUID unresolved = tx.execute(status -> {
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract);
            spec.setMaterialGroupId(persist(new MaterialGroup()).getId()); spec.setUnitOfMeasureId(unit);
            spec.setQuantityLimit(BigDecimal.TEN); spec.setPrice(BigDecimal.ONE);
            return persist(spec).getId();
        });
        var context = contexts.get(warehouse, contract, 0, 25);
        assertThat(context.positions().totalElements()).isEqualTo(2);
        assertThat(context.positions().content()).filteredOn(p -> unresolved.equals(p.specificationId()))
            .singleElement().satisfies(p -> {
                assertThat(p.materials()).isEmpty();
                assertThat(p.readiness().selectable()).isFalse();
                assertThat(p.readiness().reasonCode()).isEqualTo("MATERIAL_BINDING_REQUIRED");
            });
        assertThat(context.positions().content()).filteredOn(p -> specification.equals(p.specificationId()))
            .singleElement().satisfies(p -> {
                assertThat(p.candidateCount()).isEqualTo(2);
                assertThat(p.readiness().reasonCode()).isEqualTo("MATERIAL_CHOICE_REQUIRED");
                assertThat(p.materials()).allSatisfy(m -> assertThat(m.receiptSelection().recommendedSelected()).isFalse());
            });
        assertThat(contexts.get(warehouse, contract, 0, 1).positions().content()).hasSize(1);
        assertThat(contexts.get(warehouse, contract, 1, 1).positions().totalElements()).isEqualTo(2);
    }

    @Test void backendInfersInitialSelectionFromWarehouseStockWithoutAiAndExplicitProfileWins() {
        directSpecification();
        tx.executeWithoutResult(status -> {
            StockBalance balance = new StockBalance(); balance.setMaterialId(first); balance.setWarehouseId(warehouse);
            balance.setQuantity(new BigDecimal("8")); balance.setAvailableQuantity(new BigDecimal("6"));
            balance.setReservedQuantity(new BigDecimal("2")); persist(balance);
        });
        var selected = contexts.get(warehouse, contract, 0, 25).positions().content().getFirst().materials().getFirst();
        assertThat(selected.receiptSelection().recommendedSelected()).isTrue();
        assertThat(selected.receiptSelection().decisionSource()).isEqualTo("WAREHOUSE_STOCK_GROUP");
        assertThat(selected.availableStock().availableQuantity()).isEqualByComparingTo("6");
        assertThat(selected.availableStock().onHandQuantity()).isEqualByComparingTo("8");
        assertThat(contexts.get(otherWarehouse, contract, 0, 25).positions().content().getFirst()
            .materials().getFirst().receiptSelection().recommendedSelected()).isFalse();
        receiptProfiles.update(warehouse, profileRequest(null, deny(group)));
        var manual = contexts.get(warehouse, contract, 0, 25).positions().content().getFirst();
        assertThat(manual.readiness().selectable()).isTrue();
        assertThat(manual.materials().getFirst().receiptSelection().recommendedSelected()).isFalse();
        receiptAndApprove(request(first, warehouse, "1"), "manual-override-advice");
    }

    @Test void v2StillBlocksTechnicallyInvalidDirectMaterialAndRejectsForeignScope() {
        directSpecification();
        tx.executeWithoutResult(status -> em.find(Material.class, first).setStatus("INACTIVE"));
        var row = contexts.get(warehouse, contract, 0, 25).positions().content().getFirst();
        assertThat(row.readiness().selectable()).isFalse();
        assertThat(row.readiness().reasonCode()).isEqualTo("MATERIAL_UNAVAILABLE");
        assertThatThrownBy(() -> service.receipt(request(first, warehouse, "1"), "inactive-v2"))
            .isInstanceOf(BusinessException.class);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> contexts.get(warehouse, contract, 0, 25)).isInstanceOf(RuntimeException.class);
    }

    @Test void v2DiagnosesMissingDirectBindingAndInactiveSpecificationWithoutHidingThem() {
        UUID missing = tx.execute(status -> {
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract);
            spec.setQuantityLimit(BigDecimal.ONE); spec.setPrice(BigDecimal.ZERO);
            return persist(spec).getId();
        });
        tx.executeWithoutResult(status -> em.find(ContractSpecification.class, specification).setStatus("DRAFT"));
        var positions = contexts.get(warehouse, contract, 0, 25).positions().content();
        assertThat(positions).hasSize(2);
        assertThat(positions).filteredOn(p -> missing.equals(p.specificationId())).singleElement()
            .satisfies(p -> assertThat(p.readiness().reasonCode()).isEqualTo("MATERIAL_BINDING_REQUIRED"));
        assertThat(positions).filteredOn(p -> specification.equals(p.specificationId())).singleElement()
            .satisfies(p -> assertThat(p.readiness().reasonCode()).isEqualTo("SPECIFICATION_NOT_ACTIVE"));
    }

    @Test void receiptContextShowsEachMaterialWithoutGroupOnlyDraftOrUnitlessRows() {
        tx.executeWithoutResult(status -> {
            ContractSpecification draft = new ContractSpecification(); draft.setContractId(contract); draft.setMaterialGroupId(group);
            draft.setUnitOfMeasureId(unit); persist(draft).setStatus("DRAFT");
            ContractSpecification missing = new ContractSpecification(); missing.setContractId(contract); persist(missing).setStatus("DRAFT");
            ContractSpecification unitless = new ContractSpecification(); unitless.setContractId(contract); unitless.setMaterialGroupId(group); persist(unitless);
        });
        var context = service.contractManualReceiptContext(warehouse, contract);
        assertThat(context.lines()).extracting(ContractManualIssueContextResponse.ContractManualIssueLineResponse::materialId)
            .containsExactlyInAnyOrder(first, second);
        assertThat(context.lines()).filteredOn(line -> first.equals(line.materialId())).singleElement()
            .satisfies(line -> assertThat(line.materialName()).contains("Cable A"));
        assertThat(context.lines()).filteredOn(line -> second.equals(line.materialId())).singleElement()
            .satisfies(line -> assertThat(line.materialName()).contains("Cable B"));
        assertThat(context.lines()).allSatisfy(line -> {
            assertThat(line.specificationId()).isEqualTo(specification);
            assertThat(line.materialGroupId()).isEqualTo(group);
            assertThat(line.unitOfMeasureId()).isEqualTo(unit);
            assertThat(line.remainingReceiptQuantity()).isEqualByComparingTo("10");
        });
        assertThat(service.contractManualIssueContext(warehouse, contract).lines()).isEmpty();
    }

    @Test void memberPickerFiltersTenantCompanyGroupUnitLifecycleAndUnsupportedTypesAndPaginates() {
        tx.executeWithoutResult(status -> {
            material(group, unit, "Foreign company").setCompanyId(UUID.randomUUID());
            material(group, unit, "Foreign tenant").setTenantId("foreign");
            material(group, unit, "Inactive").setStatus("INACTIVE");
            material(group, unit, "Deleted").softDelete();
            material(group, unit, "Asset").setMaterialType(MaterialType.FIXED_ASSET);
            material(group, unit, "Service").setMaterialType(MaterialType.SERVICES);
            material(group, unit, "Serialized").setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
            material(group, persist(new UnitOfMeasure()).getId(), "Wrong unit");
            material(persist(new MaterialGroup()).getId(), unit, "Wrong group");
        });
        var page = org.springframework.data.domain.PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("name"));
        var result = service.contractSpecificationMaterials(warehouse, contract, specification, "Cable", page);
        assertThat(result.totalElements()).isEqualTo(2); assertThat(result.content()).hasSize(1);
        var member = result.content().getFirst();
        assertThat(member.materialId()).isIn(first, second);
        assertThat(member.materialGroupId()).isEqualTo(group);
        assertThat(member.remainingReceiptQuantity()).isEqualByComparingTo("10");
        assertThat(service.contractSpecificationMaterials(warehouse, contract, specification, "Cable B", page).content())
            .extracting(ContractManualIssueContextResponse.ContractManualIssueLineResponse::materialId).containsExactly(second);
        tx.executeWithoutResult(status -> em.find(ContractSpecification.class, specification).setStatus("DRAFT"));
        assertThatThrownBy(() -> service.contractSpecificationMaterials(warehouse, contract, specification, null, page))
            .isInstanceOf(BusinessException.class);
    }

    @Test void receiptContextHidesEmptyGroupsAndKeepsDirectMaterialsAndPopulatedGroups() {
        UUID emptyGroupSpec = tx.execute(status -> {
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract);
            spec.setMaterialGroupId(persist(new MaterialGroup()).getId()); spec.setUnitOfMeasureId(unit);
            spec.setQuantityLimit(BigDecimal.TEN); spec.setPrice(BigDecimal.ONE);
            return persist(spec).getId();
        });
        UUID directSpec = tx.execute(status -> {
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract); spec.setMaterialId(first);
            spec.setMaterialTypeSnapshot(MaterialType.TMB); spec.setQuantityLimit(BigDecimal.TEN); spec.setPrice(BigDecimal.ONE);
            return persist(spec).getId();
        });

        assertThat(service.contractManualReceiptContext(warehouse, contract).lines())
            .extracting(ContractManualIssueContextResponse.ContractManualIssueLineResponse::specificationId)
            .containsExactlyInAnyOrder(directSpec, specification, specification);
        tx.executeWithoutResult(status -> {
            ContractSpecification hidden = em.find(ContractSpecification.class, emptyGroupSpec);
            assertThat(hidden.getStatus()).isEqualTo("ACTIVE");
            assertThat(hidden.isDeleted()).isFalse();
        });
    }

    @Test void receiptContextHidesGroupsWithOnlyIneligibleMaterialsAndRestoresThemWhenEligible() {
        tx.executeWithoutResult(status -> {
            em.find(Material.class, first).softDelete();
            em.find(Material.class, second).setStatus("INACTIVE");
            material(group, persist(new UnitOfMeasure()).getId(), "Wrong unit");
            material(group, unit, "Foreign company").setCompanyId(UUID.randomUUID());
            material(group, unit, "Foreign tenant").setTenantId("foreign");
            material(group, unit, "Asset").setMaterialType(MaterialType.FIXED_ASSET);
            material(group, unit, "Service").setMaterialType(MaterialType.SERVICES);
        });

        assertThat(service.contractManualReceiptContext(warehouse, contract).lines()).isEmpty();

        tx.executeWithoutResult(status -> em.find(Material.class, second).setStatus("ACTIVE"));
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines())
            .extracting(ContractManualIssueContextResponse.ContractManualIssueLineResponse::specificationId)
            .containsExactly(specification);
    }

    @Test void directDraftLinesStayVisibleAsRecordsButCannotBeSelectedOrExecuted() {
        UUID draft = tx.execute(status -> {
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract); spec.setMaterialId(first);
            spec.setMaterialTypeSnapshot(MaterialType.TMB); spec.setQuantityLimit(BigDecimal.TEN);
            persist(spec).setStatus("DRAFT"); return spec.getId();
        });
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines()).extracting(ContractManualIssueContextResponse.ContractManualIssueLineResponse::specificationId)
            .doesNotContain(draft);
        assertThat(service.contractManualIssueContext(warehouse, contract).lines()).isEmpty();
        var command = new ManualWarehouseOperationRequest(warehouse, first, BigDecimal.ONE, BigDecimal.ONE, null,
            LocalDate.now(), null, null, "UZS", null, null, contract, draft);
        assertThatThrownBy(() -> receiptAndApprove(command, "draft-direct-receipt")).isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> service.issue(command, "draft-direct-issue")).isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
    }

    @Test void simultaneousReceiptsCannotSpendTheSameRemainingQuantity() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (UUID material : List.of(first, second)) {
                tasks.add(executor.submit(() -> {
                    TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
                    try {
                        start.await(); receiptAndApprove(request(material, warehouse, "6"), material.toString()); return true;
                    } catch (BusinessException failure) {
                        assertThat(failure).hasMessageContaining("remaining"); return false;
                    } finally { TenantContext.clear(); CompanyContext.clear(); }
                }));
            }
            start.countDown();
            assertThat(List.of(tasks.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), tasks.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void oneDocumentCanReceiveTwoMaterialsFromTheSameSpecificationAndReplaySafely() {
        var request = batch(first, "4", second, "6");
        var result = receiptBatchAndApprove(request, "two-materials");
        assertThat(result.lineCount()).isEqualTo(2);
        assertThat(receiptBatchAndApprove(request, "two-materials")).isEqualTo(result);
        tx.executeWithoutResult(status -> {
            var rows = movements.findAll().stream().filter(m -> tenant.equals(m.getTenantId())).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(StockMovement::getMaterialId).containsExactlyInAnyOrder(first, second);
            assertThat(rows).filteredOn(m -> first.equals(m.getMaterialId())).singleElement()
                .satisfies(m -> assertThat(m.getQuantity()).isEqualByComparingTo("4"));
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("10");
        });
    }

    @Test void combinedQuantitiesCannotOverspendTheSharedLimitAndNothingIsPosted() {
        assertThatThrownBy(() -> receiptBatchAndApprove(batch(first, "6", second, "5"), "combined-overrun"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        tx.executeWithoutResult(status -> assertThat(movements.findAll().stream().filter(m -> tenant.equals(m.getTenantId()))).isEmpty());
    }

    @Test void repeatingTheSameMaterialInOneSpecificationIsRejected() {
        assertThatThrownBy(() -> receiptBatchAndApprove(batch(first, "4", first, "6"), "duplicate-member"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Duplicate");
    }

    @Test void directMaterialLimitIsSharedAcrossWarehousesAndContextReportsRemaining() {
        directSpecification();
        var receipt = receiptAndApprove(request(first, warehouse, "4"), "direct-first");
        assertThat(receiptAndApprove(request(first, warehouse, "4"), "direct-first").getId()).isEqualTo(receipt.getId());
        var line = service.contractManualReceiptContext(otherWarehouse, contract).lines().getFirst();
        assertThat(line.remainingReceiptQuantity()).isEqualByComparingTo("6");
        assertThat(line.materialGroupId()).isEqualTo(group);
        assertThat(line.unitOfMeasureId()).isEqualTo(unit);
        assertThat(line.receiptSelection().recommendedSelected()).isFalse();
        receiptAndApprove(request(first, otherWarehouse, "6"), "direct-second");
        assertThatThrownBy(() -> receiptAndApprove(request(first, warehouse, "1"), "direct-overrun"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().reasonCode())
            .isEqualTo("CONTRACT_LIMIT_EXHAUSTED");
    }

    @Test void directMaterialCancelledAndReversedReceiptsReleaseLimit() {
        directSpecification();
        cancelledOrReversedReceiptsForDirectMaterial();
    }
    private void cancelledOrReversedReceiptsForDirectMaterial() {
        UUID cancelled = receiptAndApprove(request(first, warehouse, "10"), "direct-cancelled").getId();
        tx.executeWithoutResult(status -> em.find(StockMovement.class, cancelled).setStatus("CANCELLED"));
        UUID original = receiptAndApprove(request(first, warehouse, "10"), "direct-replacement").getId();
        tx.executeWithoutResult(status -> {
            StockMovement reversal = new StockMovement(); reversal.setMaterialId(first); reversal.setWarehouseId(warehouse);
            reversal.setMovementType(MovementType.ISSUE); reversal.setQuantity(BigDecimal.TEN);
            reversal.setReversalOfId(original); persist(reversal).setStatus("POSTED");
        });
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().remainingReceiptQuantity())
            .isEqualByComparingTo("10");
        receiptAndApprove(request(first, otherWarehouse, "10"), "direct-after-reversal");
    }

    @Test void directMaterialConcurrentReceiptsCannotExceedSharedLimit() throws Exception {
        directSpecification();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (UUID target : List.of(warehouse, otherWarehouse)) tasks.add(executor.submit(() -> {
                TenantContext.setTenant(tenant); CompanyContext.setCompany(company);
                try {
                    start.await(); receiptAndApprove(request(first, target, "6"), "direct-parallel-" + target); return true;
                } catch (BusinessException failure) {
                    assertThat(failure).hasMessageContaining("remaining"); return false;
                } finally { TenantContext.clear(); CompanyContext.clear(); }
            }));
            start.countDown();
            assertThat(List.of(tasks.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), tasks.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test void directMaterialWithoutPositiveLimitCannotBeReceived() {
        directSpecification();
        tx.executeWithoutResult(status -> em.find(ContractSpecification.class, specification).setQuantityLimit(BigDecimal.ZERO));
        assertThatThrownBy(() -> receiptAndApprove(request(first, warehouse, "1"), "no-limit"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("positive contract quantity limit");
    }

    @Test void profileRecommendationsMatchDescendantsButDoNotVetoUserSelection() {
        directSpecification();
        UUID child = tx.execute(status -> {
            MaterialGroup g = new MaterialGroup(); g.setParentId(group); return persist(g).getId();
        });
        tx.executeWithoutResult(status -> em.find(Material.class, first).setMaterialGroupId(child));
        var profile = receiptProfiles.update(warehouse, profileRequest(null, allow(group, true)));
        var selected = service.contractManualReceiptContext(warehouse, contract).lines().getFirst();
        assertThat(selected.receiptSelection().recommendedSelected()).isTrue();
        assertThat(selected.receiptSelection().policyVersion()).isEqualTo(profile.version());
        var updated = receiptProfiles.update(warehouse, profileRequest(profile.version(), allow(group, true), deny(child)));
        assertThat(updated.version()).isGreaterThan(profile.version());
        var blocked = service.contractManualReceiptContext(warehouse, contract).lines().getFirst();
        assertThat(blocked.receiptSelection().reasonCode()).isEqualTo("MATERIAL_GROUP_DENIED");
        assertThat(blocked.receiptSelection().eligibility()).isEqualTo(WarehouseReceiptProfileDtos.Eligibility.REVIEW_REQUIRED);
        assertThat(blocked.receiptSelection().recommendedSelected()).isFalse();
        receiptAndApprove(request(first, warehouse, "1"), "denied-single");
        receiptBatchAndApprove(new ManualWarehouseDocumentRequest(warehouse, contract, LocalDate.now(), null, null,
            List.of(new ManualWarehouseDocumentLineRequest(specification, BigDecimal.ONE, BigDecimal.ONE, null, first, null, null))), "denied-batch");
        // Other warehouses keep their independent profile and manual fallback.
        receiptAndApprove(request(first, otherWarehouse, "1"), "other-warehouse");
    }

    @Test void exactRuleDoesNotMatchDescendantsAndEmptyProfileAllowsManualChoice() {
        directSpecification();
        UUID parent = tx.execute(status -> persist(new MaterialGroup()).getId());
        tx.executeWithoutResult(status -> em.find(MaterialGroup.class, group).setParentId(parent));
        var profile = receiptProfiles.update(warehouse, profileRequest(null, allow(parent, false)));
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().reasonCode())
            .isEqualTo("MATERIAL_GROUP_NOT_ALLOWED");
        receiptProfiles.update(warehouse, profileRequest(profile.version(), allow(parent, true)));
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().recommendedSelected()).isTrue();
        var current = receiptProfiles.get(warehouse);
        receiptProfiles.update(warehouse, profileRequest(current.version()));
        receiptAndApprove(request(first, warehouse, "1"), "empty-profile");
    }

    @Test void groupAlternativesAreNeverAllAutoSelectedAndPickerRemainsUnselected() {
        receiptProfiles.update(warehouse, profileRequest(null, allow(group, false)));
        var context = service.contractManualReceiptContext(warehouse, contract);
        assertThat(context.lines()).hasSize(2).allSatisfy(line -> {
            assertThat(line.receiptSelection().eligibility().name()).isEqualTo("ALLOWED");
            assertThat(line.receiptSelection().recommendedSelected()).isFalse();
            assertThat(line.receiptSelection().reasonCode()).isEqualTo("MATERIAL_CHOICE_REQUIRED");
        });
        var picker = service.contractSpecificationMaterials(warehouse, contract, specification, "Cable A",
            org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(picker.content().getFirst().receiptSelection().recommendedSelected()).isFalse();
    }

    @Test void profileRejectsStaleVersionDuplicateRulesAndForeignGroupsOrWarehouses() {
        var created = receiptProfiles.update(warehouse, profileRequest(null, allow(group, false)));
        assertThatThrownBy(() -> receiptProfiles.update(warehouse, profileRequest(null)))
            .isInstanceOf(com.enterprise.mm.error.ConflictException.class);
        assertThatThrownBy(() -> receiptProfiles.update(warehouse, profileRequest(created.version(), allow(group, false), deny(group))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("unique");
        UUID foreignGroup = tx.execute(status -> {
            MaterialGroup g = persist(new MaterialGroup()); g.setCompanyId(UUID.randomUUID()); return g.getId();
        });
        assertThatThrownBy(() -> receiptProfiles.update(warehouse, profileRequest(created.version(), allow(foreignGroup, true))))
            .isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        UUID foreignWarehouse = tx.execute(status -> {
            Warehouse w = persist(new Warehouse()); w.setCompanyId(UUID.randomUUID()); return w.getId();
        });
        assertThatThrownBy(() -> receiptProfiles.get(foreignWarehouse)).isInstanceOf(com.enterprise.mm.error.NotFoundException.class);
        assertThat(receiptProfiles.get(warehouse).rules()).hasSize(1);
    }

    @Test void serializedMaterialIsNotAutoSelectedWhenExecutionIsDisabled() {
        directSpecification();
        receiptProfiles.update(warehouse, profileRequest(null, allow(group, false)));
        tx.executeWithoutResult(status -> em.find(Material.class, first).setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED));
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().reasonCode())
            .isEqualTo("SERIALIZED_EXECUTION_DISABLED");
    }

    @Test void cyclicOrInactiveGroupHierarchyIsBlocked() {
        directSpecification();
        receiptProfiles.update(warehouse, profileRequest(null, allow(group, true)));
        tx.executeWithoutResult(status -> em.find(MaterialGroup.class, group).setParentId(group));
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().reasonCode())
            .isEqualTo("MATERIAL_GROUP_HIERARCHY_INVALID");
        tx.executeWithoutResult(status -> { var g = em.find(MaterialGroup.class, group); g.setParentId(null); g.setStatus("INACTIVE"); });
        assertThat(service.contractManualReceiptContext(warehouse, contract).lines().getFirst().receiptSelection().reasonCode())
            .isEqualTo("MATERIAL_GROUP_UNAVAILABLE");
    }

    private void directSpecification() {
        tx.executeWithoutResult(status -> {
            var spec = em.find(ContractSpecification.class, specification);
            spec.setMaterialGroupId(null); spec.setMaterialId(first); spec.setMaterialTypeSnapshot(MaterialType.TMB);
        });
    }
    private com.enterprise.mm.payload.WarehouseReceiptProfileDtos.UpdateRequest profileRequest(Long version,
        com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Rule... rules) {
        return new com.enterprise.mm.payload.WarehouseReceiptProfileDtos.UpdateRequest(version, "Electrical warehouse", List.of(rules));
    }
    private com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Rule allow(UUID id, boolean children) {
        return new com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Rule(id,
            com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Effect.ALLOW, children);
    }
    private com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Rule deny(UUID id) {
        return new com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Rule(id,
            com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Effect.DENY, true);
    }

    ManualWarehouseDocumentRequest batch(UUID a, String firstQuantity, UUID b, String secondQuantity) {
        return new ManualWarehouseDocumentRequest(warehouse, contract, LocalDate.now(), "Supplier", null, List.of(
            new ManualWarehouseDocumentLineRequest(specification, new BigDecimal(firstQuantity), BigDecimal.ONE, null, a, null, null),
            new ManualWarehouseDocumentLineRequest(specification, new BigDecimal(secondQuantity), BigDecimal.TEN, null, b, null, null)));
    }

    ManualWarehouseOperationRequest request(UUID material, UUID targetWarehouse, String quantity) {
        return new ManualWarehouseOperationRequest(targetWarehouse, material, new BigDecimal(quantity), new BigDecimal("5"),
            null, LocalDate.now(), "Supplier", null, "UZS", null, null, contract, specification);
    }
    Material material(UUID materialGroup, UUID materialUnit, String name) {
        Material m = new Material(); m.setMaterialGroupId(materialGroup); m.setUnitOfMeasureId(materialUnit); m.setName(name);
        return persist(m);
    }
    <T extends BusinessEntity> T persist(T entity) {
        entity.setTenantId(tenant); entity.setCompanyId(company); entity.setStatus("ACTIVE");
        if (entity.getName() == null) entity.setName("Test " + entity.getClass().getSimpleName());
        if (entity.getCode() == null) entity.setCode("TEST-" + UUID.randomUUID());
        em.persist(entity); return entity;
    }
}
