package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.mm.service.approval.ApprovalEngineService;

@ExtendWith(MockitoExtension.class)
class ManualWarehouseOperationServiceTest {
    @Mock RoleDataScopeService scope;
    @Mock WarehouseRepository warehouses;
    @Mock MaterialCatalogGuard catalog;
    @Mock StockBalanceRepository balances;
    @Mock StockMovementService movements;
    @Mock IdempotencyService idempotency;
    @Mock QualityInspectionService quality;
    @Mock MaterialUnitEligibilityService eligibility;
    @Mock MaterialUnitRepository units;
    @Mock MaterialUnitEventRepository events;
    @Mock ContractRepository contracts;
    @Mock SupplierRepository suppliers;
    @Mock ContractSpecificationRepository contractSpecifications;
    @Mock StockMovementDocumentRepository stockMovementDocuments;
    @Mock StockMovementRepository stockMovementRepository;
    @Mock ContractGroupReceiptService groupReceipts;
    @Spy ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Mock ObjectProvider<ApprovalEngineService> approvalEngineProvider;
    @Mock ApprovalEngineService approvalEngine;
    @Mock InventoryScopeLockService inventoryLocks;
    @Mock jakarta.persistence.EntityManager em;
    final StockMovementRepository receiptMovements = mock(StockMovementRepository.class);
    @Mock WarehouseReceiptProfileService receiptProfiles;
    @Mock WarehouseReceiptEligibilityService receiptEligibility;
    @Spy ContractReceiptLimitService receiptLimits = new ContractReceiptLimitService(receiptMovements);
    ManualWarehouseOperationService service;
    UUID company = UUID.randomUUID(), warehouse = UUID.randomUUID(), materialId = UUID.randomUUID(), positionId = UUID.randomUUID();
    UUID receiptContractId = UUID.randomUUID(), receiptSpecId = UUID.randomUUID();
    Material material;
    StockMovementResponse posted;

    @BeforeEach void setup() {
        // Two StockMovementRepository mocks serve different checks; wire them explicitly.
        service = new ManualWarehouseOperationService(scope, warehouses, catalog, balances, movements,
            idempotency, quality, eligibility, units, events, contracts, suppliers, contractSpecifications,
            stockMovementDocuments, stockMovementRepository, groupReceipts, objectMapper, approvalEngineProvider,
            inventoryLocks, em, mock(com.enterprise.mm.integration.faktura.FakturaReceiptEvidenceService.class),
            receiptLimits, receiptProfiles, receiptEligibility,
            mock(com.enterprise.mm.warehouse.packaging.ReturnablePackagingService.class));
        lenient().when(receiptMovements.sumEffectiveContractReceipts(anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        TenantContext.setTenant("manual-test"); CompanyContext.setCompany(company);
        Warehouse w = new Warehouse(); w.setId(warehouse); w.setCompanyId(company);
        lenient().when(warehouses.findByTenantIdAndIdAndIsDeletedFalse("manual-test", warehouse)).thenReturn(Optional.of(w));
        material = new Material(); material.setId(materialId); material.setMaterialType(MaterialType.TMB); material.setName("Cable");
        UnitOfMeasure uom = new UnitOfMeasure(); uom.setId(UUID.randomUUID()); uom.setCode("M"); uom.setStockQuantityScale(4);
        material.setUnitOfMeasureId(uom.getId()); material.setUnitOfMeasure(uom);
        lenient().when(catalog.requireActiveTmbMaterial(materialId, "materialId")).thenReturn(material);
        lenient().when(idempotency.executeRequired(anyString(), any(), anyString(), any(), eq(StockMovementResponse.class), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        lenient().when(idempotency.executeRequired(anyString(), any(), anyString(), any(), eq(ManualWarehouseDocumentResponse.class), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        posted = new StockMovementResponse(); posted.setId(UUID.randomUUID()); posted.setStockBatchId(UUID.randomUUID());
        lenient().when(movements.postManualWarehouseMovement(any())).thenReturn(posted);
        lenient().when(movements.createManualWarehouseDraft(any())).thenReturn(posted);
        lenient().when(approvalEngineProvider.getObject()).thenReturn(approvalEngine);
        lenient().when(stockMovementDocuments.save(any())).thenAnswer(inv -> {
            StockMovementDocument d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
        Contract contract = new Contract(); contract.setId(receiptContractId); contract.setTenantId("manual-test");
        contract.setCompanyId(company); contract.setStatus("ACTIVE"); contract.setContractNumber("RC-01");
        lenient().when(contracts.findByIdAndIsDeletedFalse(receiptContractId)).thenReturn(Optional.of(contract));
        ContractSpecification spec = new ContractSpecification(); spec.setStatus("ACTIVE"); spec.setId(receiptSpecId); spec.setTenantId("manual-test");
        spec.setCompanyId(company); spec.setContractId(receiptContractId); spec.setMaterialId(materialId);
        spec.setLineType(ProcurementLineType.MATERIAL); spec.setQuantityLimit(new BigDecimal("100"));
        lenient().when(contractSpecifications.findByIdAndIsDeletedFalse(receiptSpecId)).thenReturn(Optional.of(spec));
        var warehouseSummary = mock(StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection.class);
        lenient().when(warehouseSummary.getMaterialId()).thenReturn(materialId);
        lenient().when(warehouseSummary.getTotalAvailable()).thenReturn(new BigDecimal("100"));
        lenient().when(balances.findWarehouseBalancesByMaterialIds(eq("manual-test"), eq(company), eq(warehouse), anyList()))
            .thenReturn(List.of(warehouseSummary));
        lenient().when(contractSpecifications.findByIdAndIsDeletedFalseForUpdate(receiptSpecId)).thenReturn(Optional.of(spec));
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }
    ManualWarehouseOperationRequest request(UUID position, BigDecimal cost) {
        return new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal("2"), cost, position,
            LocalDate.now(), "Returned from workshop", "M-123", "UZS", null, null, receiptContractId, receiptSpecId);
    }
    ManualWarehouseOperationRequest receiptRequest(BigDecimal cost, List<String> serials) {
        return new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal("2"), cost, null,
            LocalDate.now(), "Returned from workshop", "M-123", "UZS", serials, null, receiptContractId, receiptSpecId);
    }
    StockBalance position() {
        StockBalance b = new StockBalance(); b.setId(positionId); b.setTenantId("manual-test"); b.setCompanyId(company);
        b.setWarehouseId(warehouse); b.setMaterialId(materialId); b.setMaterial(material); b.setStatus("ACTIVE");
        b.setStockBatchId(posted.getStockBatchId()); b.setAvailableQuantity(new BigDecimal("5"));
        StockBatch batch = new StockBatch(); batch.setId(b.getStockBatchId()); batch.setMaterialId(materialId); batch.setWarehouseId(warehouse); batch.setStatus("ACTIVE"); b.setStockBatch(batch);
        when(balances.findByIdAndIsDeletedFalseForUpdate(positionId)).thenReturn(Optional.of(b));
        return b;
    }
    @Test void submitsReceiptLinkedToRequiredContractWithoutPostingStock() {
        assertThat(service.receipt(receiptRequest(new BigDecimal("50"), null), "receipt-key")).isSameAs(posted);
        ArgumentCaptor<StockMovement> capture = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).createManualWarehouseDraft(capture.capture());
        assertThat(capture.getValue().getMovementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(capture.getValue().getReferenceType()).isEqualTo("MANUAL_WAREHOUSE");
        assertThat(capture.getValue().getReferenceId()).isEqualTo(receiptContractId);
        assertThat(capture.getValue().getDescription()).contains("Returned from workshop", "M-123");
        verifyNoInteractions(balances, quality);
        verify(movements, never()).postManualWarehouseMovement(any());
        verify(approvalEngine).start(eq("STOCK_MOVEMENT"), any());
    }

    @Test void finalApprovalPostsSavedReceiptAndCompletesDocument() {
        AtomicReference<StockMovementDocument> savedDocument = new AtomicReference<>();
        AtomicReference<StockMovement> savedLine = new AtomicReference<>();
        doAnswer(inv -> {
            StockMovementDocument document = inv.getArgument(0);
            if (document.getId() == null) document.setId(UUID.randomUUID());
            savedDocument.set(document);
            return document;
        }).when(stockMovementDocuments).save(any());
        doAnswer(inv -> {
            StockMovement movement = inv.getArgument(0);
            movement.setId(posted.getId());
            savedLine.set(movement);
            return posted;
        }).when(movements).createManualWarehouseDraft(any());
        when(stockMovementDocuments.findByIdAndIsDeletedFalse(any())).thenAnswer(inv -> Optional.of(savedDocument.get()));
        when(stockMovementRepository.findByTenantIdAndDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(eq("manual-test"), any()))
            .thenAnswer(inv -> List.of(savedLine.get()));

        service.receipt(receiptRequest(new BigDecimal("50"), null), "receipt-approval-key");
        savedDocument.get().setStatus("APPROVED");
        savedLine.get().setStatus("APPROVED");
        service.postApprovedDocument(savedDocument.get().getId());

        verify(movements).postManualWarehouseMovement(savedLine.get());
        assertThat(savedDocument.get().getStatus()).isEqualTo("POSTED");
        assertThat(savedDocument.get().getPostedAt()).isNotNull();
    }
    @Test void manualReceiptRejectsMissingContractOrSpecificationBeforePosting() {
        var missingContract = new ManualWarehouseOperationRequest(warehouse, materialId, BigDecimal.ONE, BigDecimal.TEN,
            null, LocalDate.now(), null, null, "UZS", null, null);
        assertThatThrownBy(() -> service.receipt(missingContract, "missing-contract"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("Contract is required");
        var missingSpec = new ManualWarehouseOperationRequest(warehouse, materialId, BigDecimal.ONE, BigDecimal.TEN,
            null, LocalDate.now(), null, null, "UZS", null, null, receiptContractId, null);
        assertThatThrownBy(() -> service.receipt(missingSpec, "missing-spec"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("specification is required");
        verifyNoInteractions(movements);
    }
    @Test void manualReceiptRejectsSpecificationForAnotherMaterialOrCompany() {
        ContractSpecification wrongMaterial = new ContractSpecification(); wrongMaterial.setStatus("ACTIVE");
        wrongMaterial.setId(receiptSpecId); wrongMaterial.setTenantId("manual-test"); wrongMaterial.setCompanyId(company);
        wrongMaterial.setContractId(receiptContractId); wrongMaterial.setMaterialId(UUID.randomUUID());
        when(contractSpecifications.findByIdAndIsDeletedFalseForUpdate(receiptSpecId)).thenReturn(Optional.of(wrongMaterial));
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.TEN, null), "wrong-material"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("material does not match");
        wrongMaterial.setMaterialId(materialId); wrongMaterial.setCompanyId(UUID.randomUUID());
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.TEN, null), "wrong-company"))
            .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(movements);
    }
    @Test void manualReceiptRejectsInactiveContractAndExcessQuantity() {
        Contract inactive = new Contract(); inactive.setId(receiptContractId); inactive.setTenantId("manual-test");
        inactive.setCompanyId(company); inactive.setStatus("DRAFT");
        when(contracts.findByIdAndIsDeletedFalse(receiptContractId)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.TEN, null), "draft"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
        inactive.setStatus("ACTIVE");
        ContractSpecification limited = new ContractSpecification(); limited.setStatus("ACTIVE"); limited.setId(receiptSpecId);
        limited.setTenantId("manual-test"); limited.setCompanyId(company); limited.setContractId(receiptContractId);
        limited.setMaterialId(materialId); limited.setQuantityLimit(BigDecimal.ONE);
        when(contractSpecifications.findByIdAndIsDeletedFalseForUpdate(receiptSpecId)).thenReturn(Optional.of(limited));
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.TEN, null), "over-limit"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("remaining contract quantity");
        verifyNoInteractions(movements);
    }
    @Test void optionalSourceCanBeOmittedWithoutNullTextInMovement() {
        var request = new ManualWarehouseOperationRequest(warehouse, materialId, BigDecimal.ONE, BigDecimal.TEN,
            null, LocalDate.now(), null, null, "UZS", null, null, receiptContractId, receiptSpecId);
        service.receipt(request, "no-source");
        verify(movements).createManualWarehouseDraft(argThat(m -> "Shartnoma: RC-01".equals(m.getDescription())));
    }
    @Test void counterpartLookupRequiresOwnWarehouseAndUsesCurrentTenantAndCompany() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        Warehouse other = new Warehouse(); other.setId(UUID.randomUUID()); other.setCode("071"); other.setName("071 СЃРєР»Р°Рґ");
        when(warehouses.searchManualCounterparts("manual-test", company, warehouse, "%071%", pageable))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(other)));
        var result = service.counterpartWarehouses(warehouse, " 071 ", pageable);
        assertThat(result.content()).containsExactly(new ManualWarehouseOperationService.WarehouseOption(other.getId(), "071", "071 СЃРєР»Р°Рґ"));
        verify(scope).assertWarehouseAccess(warehouse);
        verifyNoInteractions(balances, movements);
    }
    @Test void counterpartLookupCannotBeUsedWithAnUnassignedWarehouse() {
        doThrow(new NotFoundException("outside warehouse")).when(scope).assertWarehouseAccess(warehouse);
        assertThatThrownBy(() -> service.counterpartWarehouses(warehouse, null, org.springframework.data.domain.PageRequest.of(0, 20)))
            .isInstanceOf(NotFoundException.class);
        verify(warehouses, never()).searchManualCounterparts(anyString(), any(), any(), anyString(), any());
    }
    @Test void receiptCannotExecuteOutsideAssignedWarehouseEvenOnIdempotentReplay() {
        doThrow(new NotFoundException("outside warehouse")).when(scope).assertWarehouseAccess(warehouse);
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.ONE, null), "key")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(idempotency, movements);
    }
    @Test void submitsIssueFromExactStockPositionWithoutClientValuation() {
        StockBalance balance = position();
        service.issue(request(positionId, new BigDecimal("99999")), "issue-key");
        verify(movements).createManualWarehouseDraft(argThat(m -> m.getMovementType() == MovementType.ISSUE
            && balance.getStockBatchId().equals(m.getStockBatchId()) && BigDecimal.ZERO.equals(m.getUnitCost())));
        verify(movements, never()).postManualWarehouseMovement(any());
    }
    @Test void manualIssueRejectsMissingContractOrSpecification() {
        var missingContract = new ManualWarehouseOperationRequest(warehouse, materialId, BigDecimal.ONE, null,
            positionId, LocalDate.now(), null, null, "UZS", null, null);
        assertThatThrownBy(() -> service.issue(missingContract, "missing-contract")).hasMessageContaining("Contract is required");
        var missingSpecification = new ManualWarehouseOperationRequest(warehouse, materialId, BigDecimal.ONE, null,
            positionId, LocalDate.now(), null, null, "UZS", null, null, receiptContractId, null);
        assertThatThrownBy(() -> service.issue(missingSpecification, "missing-specification")).hasMessageContaining("specification is required");
        verifyNoInteractions(movements);
    }
    @Test void rejectsForeignStockPositionBeforePosting() {
        position().setWarehouseId(UUID.randomUUID());
        assertThatThrownBy(() -> service.issue(request(positionId, null), "key")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(movements);
    }
    @Test void rejectsInsufficientAndExpiredPositions() {
        StockBalance balance = position(); balance.setAvailableQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> service.issue(request(positionId, null), "key")).isInstanceOf(BusinessException.class);
        balance.setAvailableQuantity(BigDecimal.TEN); balance.getStockBatch().setExpiresOn(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> service.issue(request(positionId, null), "key2")).hasMessageContaining("expired");
        verifyNoInteractions(movements);
    }
    @Test void qcReceiptDefersInspectionUntilApproval() {
        material.setIncomingInspectionRequired(true);
        service.receipt(receiptRequest(BigDecimal.ONE, null), "key");
        verifyNoInteractions(quality);
        verify(approvalEngine).start(eq("STOCK_MOVEMENT"), any());
    }
    @Test void serializedReceiptRejectsMissingPhysicalUnitsBeforeStockChanges() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        assertThatThrownBy(() -> service.receipt(receiptRequest(BigDecimal.ONE, null), "key")).hasMessageContaining("serial number");
        verifyNoInteractions(movements);
    }
    @Test void serializedReceiptDefersUnitRegistrationUntilApproval() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        ManualWarehouseOperationRequest request = receiptRequest(BigDecimal.TEN, List.of("SER-1", "SER-2"));
        service.receipt(request, "serial-receipt");
        verify(units, never()).save(any());
        verifyNoInteractions(events);
        verify(approvalEngine).start(eq("STOCK_MOVEMENT"), any());
    }
    @Test void serializedIssueRequiresExactUnitsFromTheSelectedBatch() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        position();
        MaterialUnit first = new MaterialUnit(); first.setStockBatchId(posted.getStockBatchId());
        MaterialUnit second = new MaterialUnit(); second.setStockBatchId(posted.getStockBatchId());
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(eligibility.lockAndValidate(eq(materialId), eq(warehouse), isNull(), isNull(), eq(new BigDecimal("2")), eq(ids), eq(MaterialUnitStatus.IN_STOCK)))
            .thenReturn(List.of(first, second));
        ManualWarehouseOperationRequest request = new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal("2"), null,
            positionId, LocalDate.now(), "Workshop issue", null, "UZS", null, ids, receiptContractId, receiptSpecId);
        service.issue(request, "serial-issue");
        verify(eligibility, never()).markIssued(anyList(), anyString(), any(), any(), any(), any(), any());
        verify(approvalEngine).start(eq("STOCK_MOVEMENT"), any());
    }
    @Test void rejectsSerialNumbersAlreadyInStockWithoutPostingAgain() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        when(units.existsByTenantIdAndCompanyIdAndMaterialIdAndNormalizedSerialNumberAndIsDeletedFalse("manual-test", company, materialId, "SER-1")).thenReturn(true);
        ManualWarehouseOperationRequest request = receiptRequest(BigDecimal.TEN, List.of("SER-1", "SER-2"));
        assertThatThrownBy(() -> service.receipt(request, "duplicate-serial")).hasMessageContaining("already registered");
        verifyNoInteractions(movements);
    }

    @Test void searchesContractsForIssueWithNormalizedFilters() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        Contract c = new Contract();
        c.setId(UUID.randomUUID());
        c.setCode("CTR-001");
        c.setContractNumber("SH-45/2026");
        c.setName("Steel contract");
        c.setStatus("ACTIVE");
        c.setStartDate(LocalDate.now().minusDays(10));
        c.setEndDate(LocalDate.now().plusDays(100));
        com.enterprise.mm.entity.Supplier s = new com.enterprise.mm.entity.Supplier();
        s.setCode("SUP-01");
        s.setLegalName("UzKimyo");
        c.setSupplier(s);

        when(contracts.searchContractsForManualIssue(eq("manual-test"), eq(company), eq("steel"), eq(""), eq(""), isNull(), eq(List.of()), eq("ACTIVE"), eq(true), any(), eq(pageable)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(c)));
        ContractSpecification spec = new ContractSpecification(); spec.setStatus("ACTIVE");
        spec.setLineType(ProcurementLineType.MATERIAL); spec.setQuantityLimit(new BigDecimal("100"));
        when(contractSpecifications.findByTenantIdAndContractIdAndIsDeletedFalse("manual-test", c.getId()))
            .thenReturn(List.of(spec));

        var result = service.searchContractsForIssue(warehouse, " steel ", null, null, null, pageable);
        assertThat(result.content()).hasSize(1);
        var item = result.content().get(0);
        assertThat(item.contractNumber()).isEqualTo("SH-45/2026");
        assertThat(item.supplierName()).isEqualTo("UzKimyo");
        assertThat(item.materialCount()).isEqualTo(1);
        verify(scope).assertWarehouseAccess(warehouse);
    }
    @Test void searchesContractsForIssueWithoutOptionalTextFilters() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(contracts.searchContractsForManualIssue(eq("manual-test"), eq(company), eq(""), eq(""), eq(""),
            isNull(), eq(List.of()), eq("ACTIVE"), eq(true), any(), eq(pageable)))
            .thenReturn(org.springframework.data.domain.Page.empty(pageable));

        assertThat(service.searchContractsForIssue(warehouse, null, null, null, null, pageable).content()).isEmpty();
        verify(scope).assertWarehouseAccess(warehouse);
        verifyNoInteractions(suppliers);
    }

    @Test void loadsContractManualIssueContextWithWarehouseBalancesAndPositions() {
        UUID contractId = UUID.randomUUID();
        Contract c = new Contract();
        c.setId(contractId);
        c.setTenantId("manual-test");
        c.setCompanyId(company);
        c.setStatus("ACTIVE");
        c.setContractNumber("SH-99");
        when(contracts.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(c));

        UUID specId = UUID.randomUUID();
        var specProj = mock(ContractSpecificationRepository.ContractMaterialSpecificationProjection.class);
        when(specProj.getSpecificationId()).thenReturn(specId);
        when(specProj.getMaterialId()).thenReturn(materialId);
        when(specProj.getMaterialCode()).thenReturn("MAT-1");
        when(specProj.getMaterialName()).thenReturn("Armatura");
        when(specProj.getUnit()).thenReturn("tn");
        when(specProj.getQuantityLimit()).thenReturn(new BigDecimal("50"));
        when(specProj.getPrice()).thenReturn(new BigDecimal("1000"));
        when(contractSpecifications.findActiveMaterialSpecificationsByContract("manual-test", contractId))
            .thenReturn(List.of(specProj));

        var balSummary = mock(StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection.class);
        when(balSummary.getMaterialId()).thenReturn(materialId);
        when(balSummary.getTotalAvailable()).thenReturn(new BigDecimal("30"));
        when(balSummary.getTotalOnHand()).thenReturn(new BigDecimal("30"));
        when(balSummary.getTotalReserved()).thenReturn(BigDecimal.ZERO);
        when(balances.findWarehouseBalancesByMaterialIds("manual-test", company, warehouse, List.of(materialId)))
            .thenReturn(List.of(balSummary));

        StockBalance pos = new StockBalance();
        pos.setId(positionId);
        pos.setAvailableQuantity(new BigDecimal("30"));
        when(balances.findAvailablePositionsForIssue(eq("manual-test"), eq(company), eq(warehouse), eq(materialId), any()))
            .thenReturn(List.of(pos));

        var context = service.contractManualIssueContext(warehouse, contractId);
        assertThat(context.contractNumber()).isEqualTo("SH-99");
        assertThat(context.lines()).hasSize(1);
        var line = context.lines().get(0);
        assertThat(line.materialCode()).isEqualTo("MAT-1");
        assertThat(line.availableStock().availableQuantity()).isEqualByComparingTo("30");
        assertThat(line.availableStock().defaultStockPositionId()).isEqualTo(positionId);
        assertThat(line.availableStock().hasStock()).isTrue();
    }

    @Test void postsIssueLinkedToContractAndEnforcesLimit() {
        StockBalance balance = position();
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        Contract c = new Contract();
        c.setId(contractId);
        c.setTenantId("manual-test");
        c.setCompanyId(company);
        c.setStatus("ACTIVE");
        c.setContractNumber("SH-10");
        when(contracts.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(c));

        ContractSpecification spec = new ContractSpecification(); spec.setStatus("ACTIVE");
        spec.setId(specId);
        spec.setContractId(contractId);
        spec.setTenantId("manual-test");
        spec.setCompanyId(company);
        spec.setMaterialId(materialId);
        spec.setQuantityLimit(new BigDecimal("10"));
        when(contractSpecifications.findByIdAndIsDeletedFalse(specId)).thenReturn(Optional.of(spec));

        var req = new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal("2"), null,
            positionId, LocalDate.now(), null, null, "UZS", null, null, contractId, specId);

        service.issue(req, "issue-contract-key");

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements).createManualWarehouseDraft(captor.capture());
        assertThat(captor.getValue().getReferenceId()).isEqualTo(contractId);
        assertThat(captor.getValue().getDescription()).contains("Shartnoma: SH-10");
    }

    @Test void rejectsIssueExceedingContractSpecificationLimit() {
        UUID contractId = UUID.randomUUID();
        UUID specId = UUID.randomUUID();
        Contract c = new Contract();
        c.setId(contractId);
        c.setTenantId("manual-test");
        c.setCompanyId(company);
        c.setStatus("ACTIVE");
        when(contracts.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(c));

        ContractSpecification spec = new ContractSpecification(); spec.setStatus("ACTIVE");
        spec.setId(specId);
        spec.setContractId(contractId);
        spec.setTenantId("manual-test");
        spec.setCompanyId(company);
        spec.setMaterialId(materialId);
        spec.setQuantityLimit(new BigDecimal("1")); // Limit is 1, but requested is 2
        when(contractSpecifications.findByIdAndIsDeletedFalse(specId)).thenReturn(Optional.of(spec));

        var req = new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal("2"), null,
            positionId, LocalDate.now(), null, null, "UZS", null, null, contractId, specId);

        assertThatThrownBy(() -> service.issue(req, "limit-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("exceeds contracted limit");
        verifyNoInteractions(movements);
    }

    @Test void receiptBatchSubmitsDocumentAndDraftLinesWithSpecifications() {
        UUID specId2 = UUID.randomUUID();
        UUID matId2 = UUID.randomUUID();
        Material mat2 = new Material(); mat2.setId(matId2); mat2.setMaterialType(MaterialType.TMB); mat2.setName("Pipe");
        UnitOfMeasure uom2 = new UnitOfMeasure(); uom2.setId(UUID.randomUUID()); uom2.setCode("M"); uom2.setStockQuantityScale(2);
        mat2.setUnitOfMeasureId(uom2.getId());
        mat2.setUnitOfMeasure(uom2);
        when(catalog.requireActiveTmbMaterial(matId2, "materialId")).thenReturn(mat2);

        ContractSpecification s2 = new ContractSpecification();
        s2.setId(specId2); s2.setContractId(receiptContractId); s2.setTenantId("manual-test"); s2.setCompanyId(company);
        s2.setMaterialId(matId2); s2.setLineType(ProcurementLineType.MATERIAL); s2.setStatus("ACTIVE");
        s2.setQuantityLimit(new BigDecimal("100"));
        when(contractSpecifications.findByIdAndIsDeletedFalseForUpdate(specId2)).thenReturn(Optional.of(s2));

        var lines = List.of(
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("10"), new BigDecimal("50.00"), null, null, null, null),
            new ManualWarehouseDocumentLineRequest(specId2, new BigDecimal("20"), new BigDecimal("75.00"), null, null, null, null)
        );
        var req = new ManualWarehouseDocumentRequest(warehouse, receiptContractId, LocalDate.now(), "Supplier A", "REF-99", lines);

        ManualWarehouseDocumentResponse res = service.receiptBatch(req, "batch-receipt-key");
        assertThat(res.lineCount()).isEqualTo(2);
        assertThat(res.movementType()).isEqualTo(MovementType.RECEIPT);
        assertThat(res.movementIds()).hasSize(2);
        assertThat(res.status()).isEqualTo("PENDING");

        ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);
        verify(movements, times(2)).createManualWarehouseDraft(captor.capture());
        List<StockMovement> postedMovements = captor.getAllValues();
        assertThat(postedMovements).extracting(StockMovement::getContractSpecificationId)
            .containsExactly(receiptSpecId, specId2);
        assertThat(postedMovements).extracting(StockMovement::getDocumentId)
            .containsOnly(res.documentId());
        assertThat(postedMovements).allSatisfy(m -> {
            assertThat(m.getDescription()).contains("Supplier A", "REF-99", "RC-01");
        });
        verify(movements, never()).postManualWarehouseMovement(any());
    }

    @Test void receiptBatchRejectsDuplicateSpecificationsAcrossLines() {
        var lines = List.of(
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("10"), new BigDecimal("50.00"), null, null, null, null),
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("5"), new BigDecimal("50.00"), null, null, null, null)
        );
        var req = new ManualWarehouseDocumentRequest(warehouse, receiptContractId, LocalDate.now(), null, null, lines);

        assertThatThrownBy(() -> service.receiptBatch(req, "dup-spec-key"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Duplicate contract specification in request");
        verifyNoInteractions(movements);
    }

    @Test void receiptBatchRejectsFixedAssetMaterial() {
        UUID faMaterialId = UUID.randomUUID();
        Material faMaterial = new Material();
        faMaterial.setId(faMaterialId);
        faMaterial.setMaterialType(MaterialType.FIXED_ASSET);
        faMaterial.setName("Turbine");
        when(catalog.requireActiveTmbMaterial(faMaterialId, "materialId"))
            .thenThrow(new BusinessException("Not a TMB material", "MATERIAL_TYPE_MISMATCH"));

        UUID faSpecId = UUID.randomUUID();
        ContractSpecification faSpec = new ContractSpecification();
        faSpec.setId(faSpecId); faSpec.setContractId(receiptContractId); faSpec.setTenantId("manual-test"); faSpec.setCompanyId(company);
        faSpec.setMaterialId(faMaterialId); faSpec.setLineType(ProcurementLineType.MATERIAL); faSpec.setStatus("ACTIVE");
        when(contractSpecifications.findByIdAndIsDeletedFalseForUpdate(faSpecId)).thenReturn(Optional.of(faSpec));

        var lines = List.of(
            new ManualWarehouseDocumentLineRequest(faSpecId, new BigDecimal("1"), new BigDecimal("5000.00"), null, null, null, null)
        );
        var req = new ManualWarehouseDocumentRequest(warehouse, receiptContractId, LocalDate.now(), null, null, lines);

        assertThatThrownBy(() -> service.receiptBatch(req, "fa-spec-key"))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(movements);
    }

    @Test void issueBatchRejectsWhenPositionStockInsufficient() {
        position(); // available is 5
        var lines = List.of(
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("4"), null, positionId, null, null, null),
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("2"), null, positionId, null, null, null)
        );
        // Note: duplicate spec is rejected first, so give line 2 a distinct spec pointing to same material
        UUID specId2 = UUID.randomUUID();
        ContractSpecification s2 = new ContractSpecification();
        s2.setId(specId2); s2.setContractId(receiptContractId); s2.setTenantId("manual-test"); s2.setCompanyId(company);
        s2.setMaterialId(materialId); s2.setLineType(ProcurementLineType.MATERIAL); s2.setStatus("ACTIVE");
        s2.setQuantityLimit(new BigDecimal("100"));
        when(contractSpecifications.findByIdAndIsDeletedFalse(specId2)).thenReturn(Optional.of(s2));

        var validDistinctLines = List.of(
            new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("4"), null, positionId, null, null, null),
            new ManualWarehouseDocumentLineRequest(specId2, new BigDecimal("2"), null, positionId, null, null, null)
        );
        var req = new ManualWarehouseDocumentRequest(warehouse, receiptContractId, LocalDate.now(), null, null, validDistinctLines);

        assertThatThrownBy(() -> service.issueBatch(req, "insufficient-pos-stock"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Not enough available stock in this position");
        verifyNoInteractions(movements);
    }

    @Test void issueBatchRejectsWhenWarehouseMaterialAvailabilityIsLowerThanPositionSnapshot() {
        position();
        var summary = mock(StockBalanceRepository.WarehouseMaterialBalanceSummaryProjection.class);
        when(summary.getMaterialId()).thenReturn(materialId);
        when(summary.getTotalAvailable()).thenReturn(new BigDecimal("3"));
        when(balances.findWarehouseBalancesByMaterialIds(eq("manual-test"), eq(company), eq(warehouse), anyList()))
            .thenReturn(List.of(summary));
        var request = new ManualWarehouseDocumentRequest(warehouse, receiptContractId, LocalDate.now(), null, null,
            List.of(new ManualWarehouseDocumentLineRequest(receiptSpecId, new BigDecimal("4"), null, positionId, null, null, null)));

        assertThatThrownBy(() -> service.issueBatch(request, "warehouse-material-shortage"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("this material in the warehouse");
        verifyNoInteractions(movements);
    }
}
