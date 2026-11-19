package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.payload.ManualWarehouseOperationRequest;
import com.enterprise.mm.payload.ManualWarehouseDocumentRequest;
import com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest;
import com.enterprise.mm.mapper.StockMovementMapper;
import java.time.LocalDate;
import com.enterprise.mm.repository.StockMovementRepository;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"mm.pilot.enabled-capabilities=", "mm.pilot.code-enabled-capabilities="})
@ActiveProfiles("test")
class ContractMaterialLinkIntegrationTest {
    static final String SOURCE = "a".repeat(64), PREPARED = "b".repeat(64), ORIGINAL = "c".repeat(64), MATERIAL_MANIFEST = "d".repeat(64);
    static final String SPEC_SOURCE = "1".repeat(32), MATERIAL_SOURCE = "2".repeat(32);
    @Autowired ContractMaterialLinkMigrationService migration;
    @Autowired ManualWarehouseOperationService manual;
    @Autowired StockMovementMapper movementMapper;
    @MockBean StockMovementService posting;
    // Faktura has PostgreSQL-only storage; these tests exercise the receipt/approval boundary.
    @MockBean com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService fakturaEvidence;
    @MockBean com.enterprise.mm.service.approval.ApprovalEngineService approvalEngine;
    @MockBean RoleDataScopeService scope;
    @Autowired ContractGroupReceiptService receipts;
    @Autowired WarehouseReceiptProfileService receiptProfiles;
    @Autowired ContractMigrationService contracts;
    @Autowired EntityManager em;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;
    @Autowired StockMovementRepository movements;
    String tenant;
    UUID company, contract, specification, group, stockGroup, contractUnit, stockUnit, material;

    @BeforeEach void setup() {
        tenant = "material-link-" + UUID.randomUUID(); TenantContext.setTenant(tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("migration", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        tx.executeWithoutResult(status -> {
            Company c = base(new Company()); c.setCompanyId(UUID.randomUUID()); em.persist(c); company = c.getId(); c.setCompanyId(company);
            CompanyContext.setCompany(company);
            contractUnit = unit("т", 7).getId(); stockUnit = unit("кг", 4).getId();
            group = persist(new MaterialGroup()).getId(); stockGroup = persist(new MaterialGroup()).getId();
            Contract parent = new Contract(); parent.setSupplierId(persist(new Supplier()).getId()); contract = persist(parent).getId();
            ContractSpecification spec = new ContractSpecification(); spec.setContractId(contract); spec.setMaterialGroupId(group);
            spec.setUnitOfMeasureId(contractUnit); spec.setUnit("т"); spec.setQuantityLimit(new BigDecimal("2.0000001")); spec.setPrice(new BigDecimal("5000"));
            spec.setCode("1C-DOCUMENT9197MATERIALS-" + SPEC_SOURCE); spec.setDescription(marker(ORIGINAL, "Document9197Materials", SPEC_SOURCE));
            specification = persist(spec).getId(); material = importedMaterial(MATERIAL_SOURCE, "46017008", stockGroup, stockUnit).getId();
        });
        when(posting.createManualWarehouseDraft(any())).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0);
            movement.setTenantId(tenant); movement.setCompanyId(company); movement.setStatus("DRAFT");
            movement.setName("Receipt draft"); movement.setCode("DRAFT-" + UUID.randomUUID());
            em.persist(movement); em.flush(); return movementMapper.toResponse(movement);
        });
        when(posting.postManualWarehouseMovement(any())).thenAnswer(invocation -> {
            StockMovement movement = invocation.getArgument(0); movement.setStatus("POSTED");
            if (movement.getId() == null) persist(movement);
            em.flush(); return movementMapper.toResponse(movement);
        });
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    private com.enterprise.mm.payload.StockMovementResponse receiptAndApprove(ManualWarehouseOperationRequest request, String key) {
        return tx.execute(status -> {
            var result = manual.receipt(request, key);
            approveDocument(result.getDocumentId());
            return result;
        });
    }

    private com.enterprise.mm.payload.ManualWarehouseDocumentResponse receiptBatchAndApprove(ManualWarehouseDocumentRequest request, String key) {
        return tx.execute(status -> {
            var result = manual.receiptBatch(request, key);
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
        manual.postApprovedDocument(id);
        assertThat(document.getStatus()).isEqualTo("POSTED");
    }

    @Test void verifiedCrossGroupLinkLeavesMastersUnchangedAndExposesStockUnits() {
        var command = command(row()); var result = migration.importChunk(tenant, company, command);
        assertThat(result.rows().getFirst().masterId()).isEqualTo(specification);
        assertThat(result.rows().getFirst().created()).isTrue();
        assertThat(migration.importChunk(tenant, company, command)).isEqualTo(result);
        assertThat(migration.importChunk(tenant, company, command(row())).rows().getFirst().created()).isFalse();
        assertThat(migration.links(tenant, company, specification)).hasSize(1);
        assertThat(contracts.capabilities(tenant, company)).containsEntry("contractMaterialLinkVersion", 1);
        tx.executeWithoutResult(status -> {
            var page = receipts.members(contract, specification, null, PageRequest.of(0, 20));
            assertThat(page.content()).hasSize(1); var member = page.content().getFirst();
            assertThat(member.unitOfMeasureId()).isEqualTo(stockUnit); assertThat(member.unit()).isEqualTo("кг");
            assertThat(member.contractQuantityPerStockQuantity()).isEqualTo("0.001");
            assertThat(member.serialTrackingPolicy()).isEqualTo("NONE");
            assertThat(member.contractQuantityLimit()).isEqualByComparingTo("2000.0001");
            assertThat(member.contractPrice()).isEqualByComparingTo("5");
            assertThat(member.remainingReceiptQuantity()).isEqualByComparingTo("2000.0001");
            assertThat(receipts.contextLines(contract)).containsExactly(member);
            assertThat(em.find(Material.class, material).getMaterialGroupId()).isEqualTo(stockGroup);
            assertThat(em.find(ContractSpecification.class, specification).getMaterialGroupId()).isEqualTo(group);
            assertThat(em.find(ContractSpecification.class, specification).getVersion()).isZero();
        });
    }
    @Test void warehouseProfileMatchesActualStockGroupOfVerifiedCrossGroupLink() {
        migration.importChunk(tenant, company, command(row()));
        UUID warehouse = tx.execute(status -> persist(new Warehouse()).getId());
        var profile = receiptProfiles.update(warehouse, new WarehouseReceiptProfileDtos.UpdateRequest(null, "Stock group warehouse",
            List.of(new WarehouseReceiptProfileDtos.Rule(group, WarehouseReceiptProfileDtos.Effect.ALLOW, false))));
        var line = manual.contractManualReceiptContext(warehouse, contract).lines().getFirst();
        assertThat(line.materialGroupId()).isEqualTo(group); // Preserve contractual metadata.
        assertThat(line.receiptSelection().eligibility()).isEqualTo(WarehouseReceiptProfileDtos.Eligibility.REVIEW_REQUIRED);
        receiptProfiles.update(warehouse, new WarehouseReceiptProfileDtos.UpdateRequest(profile.version(), "Stock group warehouse",
            List.of(new WarehouseReceiptProfileDtos.Rule(stockGroup, WarehouseReceiptProfileDtos.Effect.ALLOW, false))));
        var selected = manual.contractManualReceiptContext(warehouse, contract).lines().getFirst();
        assertThat(selected.receiptSelection().recommendedSelected()).isTrue();
        assertThat(selected.remainingReceiptQuantity()).isEqualByComparingTo("2000.0001");
        receiptAndApprove(new ManualWarehouseOperationRequest(warehouse, material, new BigDecimal("1000"), BigDecimal.ONE,
            null, LocalDate.now(), null, null, "UZS", null, null, contract, specification), "cross-group-profile");
    }

    @Test void warehouseReceiptRetainsStockQuantityAndIdempotencyWhileEnforcingConvertedLimit() {
        migration.importChunk(tenant, company, command(row()));
        UUID warehouse = tx.execute(status -> persist(new Warehouse()).getId());
        var request = new ManualWarehouseOperationRequest(warehouse, material, new BigDecimal("2000"), new BigDecimal("5"),
            null, LocalDate.now(), null, null, "UZS", null, null, contract, specification);
        var posted = receiptAndApprove(request, "bound-first");
        assertThat(receiptAndApprove(request, "bound-first").getId()).isEqualTo(posted.getId());
        tx.executeWithoutResult(status -> {
            assertThat(em.find(StockMovement.class, posted.getId()).getQuantity()).isEqualByComparingTo("2000");
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("2");
        });
        var overrun = new ManualWarehouseOperationRequest(warehouse, material, new BigDecimal("0.001"), new BigDecimal("5"),
            null, LocalDate.now(), null, null, "UZS", null, null, contract, specification);
        assertThatThrownBy(() -> receiptAndApprove(overrun, "bound-overrun")).isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
    }
    @Test void batchReceiptRetainsStockQuantitiesAndRejectsConvertedOverrunAtomically() {
        migration.importChunk(tenant, company, command(row()));
        java.util.UUID warehouse = tx.execute(status -> persist(new Warehouse()).getId());
        java.util.UUID directSpec = tx.execute(status -> {
            var nativeMaterial = importedMaterial("3".repeat(32), "native-batch", group, contractUnit);
            var spec = new ContractSpecification(); spec.setContractId(contract); spec.setMaterialId(nativeMaterial.getId());
            spec.setMaterialTypeSnapshot(MaterialType.TMB);
            spec.setQuantityLimit(BigDecimal.TEN); spec.setPrice(BigDecimal.ONE); return persist(spec).getId();
        });
        var nativeLine = new com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest(directSpec, BigDecimal.ONE,
            BigDecimal.ONE, null, null, null, null);
        var linkedLine = new com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest(specification, new BigDecimal("1000"),
            new BigDecimal("5"), null, material, null, null);
        var document = new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(warehouse, contract, LocalDate.now(), null, null,
            List.of(nativeLine, linkedLine));
        var result = receiptBatchAndApprove(document, "converted-batch");
        assertThat(result.lineCount()).isEqualTo(2);
        assertThat(receiptBatchAndApprove(document, "converted-batch")).isEqualTo(result);
        tx.executeWithoutResult(status -> {
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("1");
            assertThat(em.createQuery("select m.quantity from StockMovement m where m.contractSpecificationId=:spec", BigDecimal.class)
                .setParameter("spec", specification).getSingleResult()).isEqualByComparingTo("1000");
        });
        var overrun = new com.enterprise.mm.payload.ManualWarehouseDocumentLineRequest(specification, new BigDecimal("1000.001"),
            new BigDecimal("5"), null, material, null, null);
        assertThatThrownBy(() -> receiptBatchAndApprove(new com.enterprise.mm.payload.ManualWarehouseDocumentRequest(warehouse, contract,
            LocalDate.now(), null, null, List.of(nativeLine, overrun)), "converted-batch-overrun"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        tx.executeWithoutResult(status -> assertThat(em.createQuery("select count(m) from StockMovement m where m.tenantId=:tenant", Long.class)
            .setParameter("tenant", tenant).getSingleResult()).isEqualTo(2L));
    }
    @Test void tonneStockAgainstKilogramContractConvertsInTheOppositeDirection() {
        tx.executeWithoutResult(status -> {
            em.find(UnitOfMeasure.class, stockUnit).setMeasurementNameShort("ТН");
            em.find(UnitOfMeasure.class, contractUnit).setMeasurementNameShort("килограмм");
        });
        var row = row(); payload(row).put("contractQuantityPerStockQuantity", "1000");
        migration.importChunk(tenant, company, command(row));
        tx.executeWithoutResult(status -> {
            var member = receipts.members(contract, specification, null, PageRequest.of(0, 20)).content().getFirst();
            assertThat(member.remainingReceiptQuantity()).isEqualByComparingTo("0.0020");
            assertThat(member.contractPrice()).isEqualByComparingTo("5000000");
            receipt(material, "0.002", "POSTED", null);
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("2");
        });
    }
    @Test void duplicateKitUnitsRequireTheirOwnSourceCatalogEdoEvidence() {
        String contractSource = "5".repeat(32), stockSource = "6".repeat(32);
        tx.executeWithoutResult(status -> {
            for (var entry : Map.of(contractUnit, contractSource, stockUnit, stockSource).entrySet()) {
                var unit = em.find(UnitOfMeasure.class, entry.getKey()); unit.setMeasurementNameShort("КОМП");
                unit.setMeasurementNameLong("комплект"); unit.setCode("1C-UOM-" + entry.getValue());
            }
        });
        var row = row(); payload(row).put("contractQuantityPerStockQuantity", "1");
        ObjectNode evidence = payload(row).withObject("evidence");
        evidence.putObject("sourceContractUnit").put("sourceId", contractSource).put("fullName", "комплект").put("shortName", "КОМП").put("edoCode", 26);
        evidence.putObject("sourceStockUnit").put("sourceId", stockSource).put("fullName", "комплект").put("shortName", "КОМП").put("edoCode", 26);
        evidence.withObject("sourceStockUnit").put("edoCode", 27);
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row))).isInstanceOf(BadRequestException.class);
        evidence.withObject("sourceStockUnit").put("edoCode", 26).put("sourceId", contractSource);
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row))).isInstanceOf(BadRequestException.class);
        evidence.withObject("sourceStockUnit").put("sourceId", stockSource);
        assertThatCode(() -> migration.importChunk(tenant, company, command(row))).doesNotThrowAnyException();
        tx.executeWithoutResult(status -> assertThat(receipts.members(contract, specification, null, PageRequest.of(0, 20)).content().getFirst().contractQuantityPerStockQuantity()).isEqualTo("1"));
    }
    @Test void duplicatePieceUnitsAreExplicitlyLinkedWithFactorOne() {
        tx.executeWithoutResult(status -> { em.find(UnitOfMeasure.class, stockUnit).setMeasurementNameShort("Штука"); em.find(UnitOfMeasure.class, contractUnit).setMeasurementNameShort("шт."); });
        var row = row(); payload(row).put("contractQuantityPerStockQuantity", "1");
        migration.importChunk(tenant, company, command(row));
        tx.executeWithoutResult(status -> assertThat(receipts.members(contract, specification, null, PageRequest.of(0, 20)).content().getFirst().contractQuantityPerStockQuantity()).isEqualTo("1"));
    }
    @Test void linkedAndNativeMembersShareConvertedLimitAndPostedReversalsReleaseIt() {
        migration.importChunk(tenant, company, command(row()));
        UUID nativeMaterial = tx.execute(status -> importedMaterial("3".repeat(32), "native", group, contractUnit).getId());
        UUID firstReceipt = tx.execute(status -> receipt(material, "1000", "POSTED", null).getId());
        tx.executeWithoutResult(status -> receipt(nativeMaterial, "1", "POSTED", null));
        tx.executeWithoutResult(status -> {
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("2");
            assertThatThrownBy(() -> receipts.validateReceipt(em.find(ContractSpecification.class, specification), em.find(Material.class, material), new BigDecimal("0.001")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
            receipt(material, "1000", "POSTED", firstReceipt);
        });
        tx.executeWithoutResult(status -> {
            receipt(material, "999999", "CANCELLED", null);
            assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("1");
            assertThatCode(() -> receipts.validateReceipt(em.find(ContractSpecification.class, specification), em.find(Material.class, material), new BigDecimal("1000"))).doesNotThrowAnyException();
        });
    }
    @Test void oneBatchAggregatesLinkedAndNativeMaterialQuantitiesInContractUnits() {
        migration.importChunk(tenant, company, command(row()));
        UUID nativeMaterial = tx.execute(status -> importedMaterial("3".repeat(32), "native", group, contractUnit).getId());
        UUID warehouse = tx.execute(status -> persist(new Warehouse()).getId());
        var tooMuch = new ManualWarehouseDocumentRequest(warehouse, contract, LocalDate.now(), null, null, List.of(
            new ManualWarehouseDocumentLineRequest(specification, new BigDecimal("1500"), BigDecimal.ONE, null, material, null, null),
            new ManualWarehouseDocumentLineRequest(specification, BigDecimal.ONE, BigDecimal.ONE, null, nativeMaterial, null, null)));
        assertThatThrownBy(() -> receiptBatchAndApprove(tooMuch, "converted-overrun"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining");
        var valid = new ManualWarehouseDocumentRequest(warehouse, contract, LocalDate.now(), null, null, List.of(
            new ManualWarehouseDocumentLineRequest(specification, new BigDecimal("1000"), BigDecimal.ONE, null, material, null, null),
            new ManualWarehouseDocumentLineRequest(specification, BigDecimal.ONE, BigDecimal.ONE, null, nativeMaterial, null, null)));
        assertThat(receiptBatchAndApprove(valid, "converted-batch").lineCount()).isEqualTo(2);
        tx.executeWithoutResult(status -> assertThat(movements.sumEffectiveContractReceipts(tenant, company, specification)).isEqualByComparingTo("2"));
    }

    @Test void candidateRemainderRoundsDownToStockPrecision() {
        tx.executeWithoutResult(status -> em.find(UnitOfMeasure.class, stockUnit).setStockQuantityScale(0));
        migration.importChunk(tenant, company, command(row()));
        tx.executeWithoutResult(status -> assertThat(receipts.members(contract, specification, null, PageRequest.of(0, 20)).content().getFirst().remainingReceiptQuantity()).isEqualByComparingTo("2000"));
    }
    @Test void serializedForeignDeletedAndUnitDriftFailClosed() {
        migration.importChunk(tenant, company, command(row()));
        tx.executeWithoutResult(status -> assertThat(receipts.members(contract, specification, null, PageRequest.of(0, 20)).content()).hasSize(1));
        tx.executeWithoutResult(status -> em.find(Material.class, material).setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED));
        assertNoMemberOrReceipt();
        tx.executeWithoutResult(status -> { var m = em.find(Material.class, material); m.setSerialTrackingPolicy(SerialTrackingPolicy.NONE); m.setCompanyId(UUID.randomUUID()); });
        assertNoMemberOrReceipt();
        tx.executeWithoutResult(status -> { var m = em.find(Material.class, material); m.setCompanyId(company); m.softDelete(); });
        assertNoMemberOrReceipt();
        tx.executeWithoutResult(status -> { em.find(Material.class, material).restore(); em.find(UnitOfMeasure.class, stockUnit).setMeasurementNameShort("шт"); });
        assertNoMemberOrReceipt();
    }
    @Test void changedSerialPolicyRequiresFreshReceiptPreflightEvenWhenSerializationIsEnabled() {
        migration.importChunk(tenant, company, command(row()));
        var enabled = new ContractGroupReceiptService(context.getBean(com.enterprise.mm.repository.ContractSpecificationRepository.class),
            context.getBean(com.enterprise.mm.repository.MaterialRepository.class), context.getBean(com.enterprise.mm.repository.MaterialGroupRepository.class),
            context.getBean(com.enterprise.mm.repository.UnitOfMeasureRepository.class), context.getBean(ContractReceiptLimitService.class),
            new com.enterprise.mm.security.pilot.PilotCapabilityPolicy("SERIALIZED_EXECUTION"),
            context.getBean(com.enterprise.mm.repository.ContractMaterialLinkRepository.class), mapper, em);
        tx.executeWithoutResult(status -> {
            var spec = em.find(ContractSpecification.class, specification, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            var stockMaterial = em.find(Material.class, material);
            assertThat(stockMaterial.getSerialTrackingPolicy()).isEqualTo(SerialTrackingPolicy.NONE);
            var concurrent = new TransactionTemplate(tx.getTransactionManager());
            concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.executeWithoutResult(other -> em.find(Material.class, material).setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED));
            assertThatThrownBy(() -> enabled.validateReceipt(spec, stockMaterial, BigDecimal.ONE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("serial tracking changed");
        });
    }
    @Test void aUnitChangedAfterReceiptLoadedItCannotHideBehindThePersistenceCache() {
        migration.importChunk(tenant, company, command(row()));
        tx.executeWithoutResult(status -> {
            var spec = em.find(ContractSpecification.class, specification, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            var stockMaterial = em.find(Material.class, material);
            assertThat(stockMaterial.getUnitOfMeasure().getMeasurementNameShort()).isEqualTo("кг");
            var concurrent = new TransactionTemplate(tx.getTransactionManager());
            concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.executeWithoutResult(other -> em.find(UnitOfMeasure.class, stockUnit).setMeasurementNameShort("шт"));
            assertThatThrownBy(() -> receipts.validateReceipt(spec, stockMaterial, BigDecimal.ONE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("snapshot");
        });
    }
    @Test void changedSnapshotCannotBecomeNativeAndBypassItsBinding() {
        migration.importChunk(tenant, company, command(row()));
        tx.executeWithoutResult(status -> { var m = em.find(Material.class, material); m.setMaterialGroupId(group); m.setUnitOfMeasureId(contractUnit); });
        assertNoMemberOrReceipt();
    }
    @Test void changedFactorCannotReinterpretHistoricalReceipts() {
        tx.executeWithoutResult(status -> receipt(material, "1", "CANCELLED", null));
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row()))).isInstanceOf(ConflictException.class).hasMessageContaining("historical");
        assertThat(migration.links(tenant, company, specification)).isEmpty();
    }
    @Test void sourceUnitIdentityCannotBeOmittedOrBorrowedFromAnotherImportedCard() {
        for (String key : List.of("sourceContractUnit", "sourceStockUnit")) {
            var missing = row(); payload(missing).withObject("evidence").remove(key);
            assertThatThrownBy(() -> migration.importChunk(tenant, company, command(missing))).isInstanceOf(BadRequestException.class);
            var borrowed = row(); payload(borrowed).withObject("evidence").withObject(key).put("sourceId", "f".repeat(32));
            assertThatThrownBy(() -> migration.importChunk(tenant, company, command(borrowed))).isInstanceOf(BadRequestException.class);
        }
    }
    @Test void strictIdentityVersionFactorAndEvidenceValidation() {
        for (String field : List.of("expectedVersion", "expectedContractId", "expectedMaterialGroupId", "expectedStockUnitOfMeasureId", "materialSourceId", "expectedMaterialCode", "originalPreparedManifestSha256")) {
            var row = row();
            if (field.equals("expectedVersion")) payload(row).put(field, 1);
            else if (field.endsWith("Sha256")) payload(row).put(field, "f".repeat(64));
            else if (field.equals("materialSourceId")) payload(row).put(field, "4".repeat(32));
            else payload(row).put(field, UUID.randomUUID().toString());
            assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row))).isInstanceOfAny(BadRequestException.class, ConflictException.class);
        }
        for (String factor : List.of("-1", "0", "2", "1000")) {
            var row = row(); payload(row).put("contractQuantityPerStockQuantity", factor);
            assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row))).isInstanceOf(BadRequestException.class);
        }
        var unknown = row(); payload(unknown).put("price", "1");
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(unknown))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> migration.importChunk("foreign", company, command(row()))).isInstanceOf(BadRequestException.class);
        tx.executeWithoutResult(status -> em.find(Contract.class, contract).setStatus("DRAFT"));
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(row()))).isInstanceOf(ConflictException.class);
    }
    @Test void duplicateLinksAndManifestDriftAreImmutableButMultipleMaterialsAreAllowed() {
        var row = row(); var command = command(row); migration.importChunk(tenant, company, command);
        var changed = row(); payload(changed).withObject("evidence").put("review", "changed");
        assertThatThrownBy(() -> migration.importChunk(tenant, company, command(changed))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> migration.importChunk(tenant, company, new MasterChunkRequest(command.importId(), UUID.randomUUID(), SOURCE, "f".repeat(64), List.of(row))))
            .isInstanceOf(ConflictException.class);
        UUID second = tx.execute(status -> importedMaterial("3".repeat(32), "second", stockGroup, stockUnit).getId());
        var other = row(); payload(other).put("materialId", second.toString()).put("materialSourceId", "3".repeat(32)).put("expectedMaterialCode", "second");
        migration.importChunk(tenant, company, command(other));
        assertThat(migration.links(tenant, company, specification)).hasSize(2);
    }
    void assertNoMemberOrReceipt() {
        tx.executeWithoutResult(status -> {
            assertThat(receipts.members(contract, specification, null, PageRequest.of(0, 20)).content()).isEmpty();
            assertThat(receipts.contextLines(contract)).isEmpty();
            assertThatThrownBy(() -> receipts.validateReceipt(em.find(ContractSpecification.class, specification), em.find(Material.class, material), BigDecimal.ONE)).isInstanceOf(BusinessException.class);
        });
    }
    MasterRow row() {
        ObjectNode p = mapper.createObjectNode(); p.put("materialId", material.toString()); p.put("materialSourceId", MATERIAL_SOURCE);
        p.put("materialSourceEntityType", "Catalog54"); p.put("expectedMaterialCode", "46017008"); p.put("expectedVersion", 0);
        p.put("expectedContractId", contract.toString()); p.put("expectedMaterialGroupId", group.toString());
        p.put("expectedContractUnitOfMeasureId", contractUnit.toString()); p.put("expectedStockUnitOfMeasureId", stockUnit.toString());
        p.put("contractQuantityPerStockQuantity", "0.001"); p.put("originalPreparedManifestSha256", ORIGINAL); p.put("materialPreparedManifestSha256", MATERIAL_MANIFEST);
        var evidence = p.putObject("evidence").put("review", "source group and physical UOM match");
        evidence.putObject("sourceContractUnit").put("sourceId", tx.<String>execute(status -> em.find(UnitOfMeasure.class, contractUnit).getCode().substring(7)));
        evidence.putObject("sourceStockUnit").put("sourceId", tx.<String>execute(status -> em.find(UnitOfMeasure.class, stockUnit).getCode().substring(7)));
        return new MasterRow(SPEC_SOURCE, "Document9197Materials", specification, p);
    }
    ObjectNode payload(MasterRow row) { return (ObjectNode) row.payload(); }
    MasterChunkRequest command(MasterRow... rows) { return new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), SOURCE, PREPARED, List.of(rows)); }
    String marker(String manifest, String type, String source) { return "[1c-mm-migration:" + manifest + ":" + type + ":" + source + "]"; }
    UnitOfMeasure unit(String label, int scale) { var u = new UnitOfMeasure(); u.setCode("1C-UOM-" + UUID.randomUUID().toString().replace("-", "")); u.setMeasurementNameShort(label); u.setStockQuantityScale(scale); return persist(u); }
    Material importedMaterial(String source, String code, UUID group, UUID unit) {
        Material m = new Material(); m.setMaterialGroupId(group); m.setUnitOfMeasureId(unit); m.setCode(code);
        m.setDescription(marker(MATERIAL_MANIFEST, "Catalog54", source)); return persist(m);
    }
    StockMovement receipt(UUID materialId, String quantity, String status, UUID reversal) {
        StockMovement m = new StockMovement(); m.setMaterialId(materialId); m.setWarehouseId(persist(new Warehouse()).getId()); m.setContractSpecificationId(specification);
        m.setQuantity(new BigDecimal(quantity)); m.setMovementType(reversal == null ? MovementType.RECEIPT : MovementType.ISSUE); m.setReversalOfId(reversal);
        persist(m).setStatus(status); em.flush(); return m;
    }
    <T extends BusinessEntity> T base(T entity) { entity.setTenantId(tenant); entity.setCompanyId(company); entity.setStatus("ACTIVE"); if (entity.getName() == null) entity.setName("Test " + entity.getClass().getSimpleName()); if (entity.getCode() == null) entity.setCode("TEST-" + UUID.randomUUID()); return entity; }
    <T extends BusinessEntity> T persist(T entity) { em.persist(base(entity)); return entity; }
}
