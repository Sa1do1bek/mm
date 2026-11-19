package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.TransferDispatchRequest;
import com.enterprise.mm.payload.WarehouseStockFactRequest;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;

class StockPickingAllocationServiceTest {
    final StockBalanceRepository balances = mock(StockBalanceRepository.class);
    final MaterialRepository materials = mock(MaterialRepository.class);
    final MaterialUnitRepository units = mock(MaterialUnitRepository.class);
    final ExactOpeningImportGuard openingGuard = mock(ExactOpeningImportGuard.class);
    final InventoryCostingService costing = mock(InventoryCostingService.class);
    final com.enterprise.mm.security.pilot.PilotCapabilityPolicy capabilities = mock(com.enterprise.mm.security.pilot.PilotCapabilityPolicy.class);
    final StockPickingAllocationService service = new StockPickingAllocationService(balances, materials, units, openingGuard, costing, capabilities);
    TransferDocument transfer;
    TransferDocumentLine line;
    Material material;
    StockBalance oldStock, newStock;

    @BeforeEach void setup() {
        when(capabilities.enabled(com.enterprise.mm.security.pilot.PilotCapability.SERIALIZED_EXECUTION)).thenReturn(true);
        TenantContext.setTenant("picking"); CompanyContext.setCompany(UUID.randomUUID());
        transfer = scope(new TransferDocument()); transfer.setSourceWarehouseId(UUID.randomUUID()); transfer.setVersion(3L);
        material = scope(new Material()); material.setName("Cotton"); material.setMaterialType(MaterialType.TMB); material.setValuationMethod(ValuationMethod.WEIGHTED_AVERAGE);
        var uom = new UnitOfMeasure(); uom.setId(UUID.randomUUID()); uom.setCode("TN"); uom.setStockQuantityScale(7);
        material.setUnitOfMeasure(uom); material.setUnitOfMeasureId(uom.getId());
        line = scope(new TransferDocumentLine()); line.setMaterialId(material.getId()); line.setTransferDocumentId(transfer.getId());
        line.setPlannedQuantity(new BigDecimal("30"));
        oldStock = position(LocalDate.of(2025, 1, 1), "6"); newStock = position(LocalDate.of(2026, 1, 1), "8");
        when(materials.findByIdAndIsDeletedFalse(material.getId())).thenReturn(Optional.of(material));
        when(balances.findTransferPickingBalances(anyString(), any(), any(), anyList())).thenReturn(List.of(newStock, oldStock));
        when(balances.lockTransferPickingBalances(anyString(), any(), any(), anyList())).thenReturn(List.of(newStock, oldStock));
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void quantityOnlyFixedAssetsUseFifoWithoutSerialUnitSelection() {
        material.setMaterialType(MaterialType.FIXED_ASSET);
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        when(capabilities.enabled(com.enterprise.mm.security.pilot.PilotCapability.SERIALIZED_EXECUTION)).thenReturn(false);
        var input = new WarehouseStockFactRequest.Line(material.getId(), BigDecimal.TEN, null, null, null, null,
            PickingMethod.FIFO, StockAllocationMode.AUTO, null);
        var result = service.allocateFixedAssetIssue(transfer.getSourceWarehouseId(), List.of(input), true);
        assertThat(result.getFirst().allocations()).extracting(a -> a.stockBalanceId()).containsExactly(oldStock.getId(), newStock.getId());
        assertThat(result.getFirst().allocations()).allSatisfy(a -> assertThat(a.materialUnitIds()).isEmpty());
        assertThat(service.fixedAssetIssueOptions(transfer.getSourceWarehouseId(), material.getId(), PickingMethod.FIFO, 0, 25).quantityScale()).isZero();
        verifyNoInteractions(units);
        // The ordinary material/transfer path still excludes fixed asset stock.
        assertThat(service.issueOptions(transfer.getSourceWarehouseId(), material.getId(), PickingMethod.FIFO, 0, 25).eligibleAvailableQuantity()).isZero();
    }

    @Test void fixedAssetPickingRejectsFractionalQuantitiesAndWrongMaterialType() {
        var input = new WarehouseStockFactRequest.Line(material.getId(), BigDecimal.ONE, null, null, null, null);
        assertThatThrownBy(() -> service.allocateFixedAssetIssue(transfer.getSourceWarehouseId(), List.of(input), false))
            .hasMessageContaining("fixed-asset");
        material.setMaterialType(MaterialType.FIXED_ASSET);
        var fraction = new WarehouseStockFactRequest.Line(material.getId(), new BigDecimal("1.5"), null, null, null, null);
        assertThatThrownBy(() -> service.allocateFixedAssetIssue(transfer.getSourceWarehouseId(), List.of(fraction), false))
            .hasMessageContaining("whole");
    }

    @Test void fixedAssetManualAllocationsMustBeWholeEvenWhenStockUomAllowsFractions() {
        material.setMaterialType(MaterialType.FIXED_ASSET);
        var allocations = List.of(
            new WarehouseStockFactRequest.Allocation(oldStock.getId(), new BigDecimal("0.5"), null),
            new WarehouseStockFactRequest.Allocation(newStock.getId(), new BigDecimal("0.5"), null));
        var input = new WarehouseStockFactRequest.Line(material.getId(), BigDecimal.ONE, null, null, null, null,
            PickingMethod.FIFO, StockAllocationMode.MANUAL, allocations);
        assertThatThrownBy(() -> service.allocateFixedAssetIssue(transfer.getSourceWarehouseId(), List.of(input), false))
            .hasMessageContaining("allocation quantity must be whole");
    }

    @Test void fifoSplitsAcrossBatchesAndPreviewDoesNotMutateOrLock() {
        var plan = service.allocate(transfer, List.of(line), request(auto(line, "10", PickingMethod.FIFO)), false);
        assertThat(plan.decisions().getFirst().allocations()).extracting(a -> a.stockBalanceId()).containsExactly(oldStock.getId(), newStock.getId());
        assertThat(plan.postings()).extracting(p -> p.getDispatchedQuantity()).containsExactly(new BigDecimal("6.0000000"), new BigDecimal("4.0000000"));
        assertThat(oldStock.getAvailableQuantity()).isEqualByComparingTo("6");
        assertThat(material.getValuationMethod()).isEqualTo(ValuationMethod.WEIGHTED_AVERAGE);
        verify(balances, never()).lockTransferPickingBalances(anyString(), any(), any(), anyList());
        verify(balances, never()).save(any());
    }
    @Test void lifoTakesNewestFirstUnderPostingLocks() {
        var plan = service.allocate(transfer, List.of(line), request(auto(line, "10", PickingMethod.LIFO)), true);
        assertThat(plan.decisions().getFirst().allocations()).extracting(a -> a.stockBalanceId()).containsExactly(newStock.getId(), oldStock.getId());
        assertThat(plan.postings()).extracting(p -> p.getDispatchedQuantity()).containsExactly(new BigDecimal("8.0000000"), new BigDecimal("2.0000000"));
        var lockOrder = inOrder(openingGuard, costing, balances);
        lockOrder.verify(openingGuard).assertOperationsAllowed(transfer.getCompanyId());
        lockOrder.verify(costing).lockPickingSources(transfer.getSourceWarehouseId(), List.of(material.getId()));
        lockOrder.verify(balances).lockTransferPickingBalances(anyString(), any(), any(), anyList());
    }
    @Test void unknownDatesStayLastInBothMethodsAndDetailsIncludeBlockedStock() {
        oldStock.getStockBatch().setReceivedDate(null); newStock.getStockBatch().setStatus("BLOCKED");
        for (var method : PickingMethod.values()) {
            var options = service.options(transfer, line, method, 0, 25);
            assertThat(options.positions()).extracting(p -> p.stockBalanceId()).containsExactly(newStock.getId(), oldStock.getId());
            assertThat(options.positions().getFirst().selectable()).isFalse();
            assertThat(options.positions().getFirst().executableQuantity()).isZero();
        }
    }
    @Test void blockedExpiredAndOtherOwnedStockAreNeverConsumed() {
        newStock.setOwnerPartyId(UUID.randomUUID()); oldStock.getStockBatch().setExpiresOn(LocalDate.now().minusDays(1));
        assertThat(service.context(transfer, List.of(line)).lines().getFirst().maxDispatchQuantity()).isZero();
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(auto(line, "1", PickingMethod.FIFO)), false)).hasMessageContaining("Insufficient executable");
    }
    @Test void availableRatherThanOnHandLimitsQuantityAndPartialPlanLimitsMax() {
        oldStock.setQuantity(new BigDecimal("20")); oldStock.setAvailableQuantity(new BigDecimal("1"));
        line.setDispatchedQuantity(new BigDecimal("25")); line.setCancelledQuantity(new BigDecimal("2"));
        var context = service.context(transfer, List.of(line)).lines().getFirst();
        assertThat(context.eligibleAvailableQuantity()).isEqualByComparingTo("9");
        assertThat(context.maxDispatchQuantity()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(auto(line, "4", PickingMethod.FIFO)), false)).hasMessageContaining("remaining transfer plan");
    }
    @Test void sharedMaterialAcrossLinesCannotSpendStockTwice() {
        var second = scope(new TransferDocumentLine()); second.setMaterialId(material.getId()); second.setPlannedQuantity(BigDecimal.TEN);
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line, second), request(auto(line, "10", PickingMethod.FIFO), auto(second, "5", PickingMethod.FIFO)), false))
            .hasMessageContaining("Insufficient executable");
    }
    @Test void manualSelectionUsesExactPositionAndRequiresMatchingSum() {
        var row = manual("5", allocation(newStock, "5"));
        var plan = service.allocate(transfer, List.of(line), request(row), false);
        assertThat(plan.postings().getFirst().getStockBatchId()).isEqualTo(newStock.getStockBatchId());
        row.setDispatchedQuantity(new BigDecimal("6"));
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(row), false)).hasMessageContaining("sum must equal");
    }
    @Test void manualCannotExceedStockOrChooseForeignPositionOrDuplicateIt() {
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(manual("9", allocation(newStock,"9"))), false)).hasMessageContaining("exceeds executable");
        var invalid = allocation(newStock,"1"); invalid.setStockBalanceId(UUID.randomUUID());
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(manual("1", invalid)), false)).hasMessageContaining("outside this transfer line");
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(manual("2", allocation(newStock,"1"), allocation(newStock,"1"))), false)).hasMessageContaining("Duplicate source");
    }
    @Test void acceptsLegacySplitBatchRequestButRejectsMixedAndRepeatedModernRows() {
        var first = legacy(oldStock, "2"); var second = legacy(newStock, "3");
        assertThat(service.allocate(transfer, List.of(line), request(first, second), false).postings()).hasSize(2);
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(first, auto(line,"1",PickingMethod.FIFO)), false)).hasMessageContaining("do not mix");
        assertThatThrownBy(() -> service.allocate(transfer, List.of(line), request(auto(line,"1",PickingMethod.FIFO), auto(line,"1",PickingMethod.LIFO)), false)).hasMessageContaining("one AUTO/MANUAL");
    }
    @Test void explicitModeRejectsAmbiguousSourceFieldsAndAutoRejectsManualAllocations() {
        var row = auto(line,"1",PickingMethod.FIFO); row.setStockBatchId(newStock.getStockBatchId());
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line),request(row),false)).hasMessageContaining("legacy source");
        row.setStockBatchId(null); row.setAllocations(List.of(allocation(newStock,"1")));
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line),request(row),false)).hasMessageContaining("AUTO allocation");
    }
    @Test void exactSevenDecimalQuantitiesArePreservedAndOverPrecisionRejected() {
        var row = auto(line,"0.1234567",PickingMethod.FIFO);
        assertThat(service.allocate(transfer,List.of(line),request(row),false).postings().getFirst().getDispatchedQuantity()).isEqualByComparingTo("0.1234567");
        row.setDispatchedQuantity(new BigDecimal("0.12345678"));
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line),request(row),false)).hasMessageContaining("not rounded");
    }
    @Test void unknownLineAndCrossCompanyTransferRejectedBeforeStockQuery() {
        var row = auto(line,"1",PickingMethod.FIFO); row.setTransferLineId(UUID.randomUUID());
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line),request(row),false)).hasMessageContaining("does not belong");
        transfer.setCompanyId(UUID.randomUUID());
        assertThatThrownBy(() -> service.context(transfer,List.of(line))).hasMessageContaining("company scope");
        verifyNoInteractions(balances);
    }
    @Test void requiredSerialAutoSelectsEligibleUnitsAndManualRequiresExactUnitIds() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        var u1 = serial(oldStock,"S1"); var u2 = serial(oldStock,"S2");
        when(units.findTransferPickingUnits(anyString(),any(),any(),anyList())).thenReturn(List.of(u1,u2));
        var plan = service.allocate(transfer,List.of(line),request(auto(line,"2",PickingMethod.FIFO)),false);
        assertThat(plan.postings().getFirst().getMaterialUnitIds()).containsExactly(u1.getId(),u2.getId());
        assertThat(service.context(transfer,List.of(line)).lines().getFirst().eligibleAvailableQuantity()).isEqualByComparingTo("2");
        var a = allocation(oldStock,"1"); a.setMaterialUnitIds(List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line),request(manual("1",a)),false)).hasMessageContaining("does not belong");
        a.setMaterialUnitIds(List.of(u2.getId()));
        assertThat(service.allocate(transfer,List.of(line),request(manual("1",a)),false).postings().getFirst().getMaterialUnitIds()).containsExactly(u2.getId());
    }
    @Test void lifoSerialSelectionIsReversedAndUnitCannotBeSpentAcrossLines() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        var u1=serial(newStock,"A"); var u2=serial(newStock,"B");
        when(units.findTransferPickingUnits(anyString(),any(),any(),anyList())).thenReturn(List.of(u1,u2));
        var plan=service.allocate(transfer,List.of(line),request(auto(line,"1",PickingMethod.LIFO)),false);
        assertThat(plan.postings().getFirst().getMaterialUnitIds()).containsExactly(u2.getId());
        var other=scope(new TransferDocumentLine()); other.setMaterialId(material.getId()); other.setPlannedQuantity(BigDecimal.TEN);
        assertThatThrownBy(() -> service.allocate(transfer,List.of(line,other),request(auto(line,"2",PickingMethod.FIFO),auto(other,"1",PickingMethod.FIFO)),false)).hasMessageContaining("Insufficient executable");
    }
    @Test void legacyRowSerializationRemainsByteCompatibleAndQuantityAliasIsAccepted() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var row = legacy(oldStock,"2");
        String expected = "{\"transferLineId\":\"" + line.getId() + "\",\"dispatchedQuantity\":2,\"stockBatchId\":\""
            + oldStock.getStockBatchId() + "\",\"storageLocationId\":null,\"storageBinId\":null,\"materialUnitIds\":null}";
        assertThat(mapper.writeValueAsString(row)).isEqualTo(expected);
        var decoded = mapper.readValue("{\"quantity\":0.1234567,\"allocationMode\":\"AUTO\",\"pickingMethod\":\"LIFO\"}", TransferDispatchRequest.Line.class);
        assertThat(decoded.getDispatchedQuantity()).isEqualByComparingTo("0.1234567");
        assertThat(decoded.getPickingMethod()).isEqualTo(PickingMethod.LIFO);
    }

    @Test void deploymentGatePreventsDisabledSerialStockFromBeingOffered() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        when(capabilities.enabled(com.enterprise.mm.security.pilot.PilotCapability.SERIALIZED_EXECUTION)).thenReturn(false);
        assertThat(service.options(transfer,line,PickingMethod.FIFO,0,25).positions())
            .allSatisfy(p -> {assertThat(p.blockedReason()).isEqualTo("capability");assertThat(p.executableQuantity()).isZero();});
    }

    @Test void manualWarehouseIssueUsesTheSameFifoAndLifoSplitAndPostingLocks() {
        var fifo = issue("10", PickingMethod.FIFO, StockAllocationMode.AUTO, null);
        var preview = service.allocateIssue(transfer.getSourceWarehouseId(), List.of(fifo), false).getFirst();
        assertThat(preview.allocations()).extracting(a -> a.stockBalanceId()).containsExactly(oldStock.getId(), newStock.getId());
        assertThat(preview.allocations()).extracting(a -> a.quantity()).containsExactly(new BigDecimal("6.0000000"), new BigDecimal("4.0000000"));
        verifyNoInteractions(costing, openingGuard);
        var lifo = service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("10", PickingMethod.LIFO, StockAllocationMode.AUTO, null)), true).getFirst();
        assertThat(lifo.allocations()).extracting(a -> a.stockBalanceId()).containsExactly(newStock.getId(), oldStock.getId());
        var order = inOrder(openingGuard, costing, balances);
        order.verify(openingGuard).assertOperationsAllowed(CompanyContext.currentCompany());
        order.verify(costing).lockPickingSources(transfer.getSourceWarehouseId(), List.of(material.getId()));
        order.verify(balances).lockTransferPickingBalances(eq(TenantContext.currentTenant()), eq(CompanyContext.currentCompany()), eq(transfer.getSourceWarehouseId()), eq(List.of(material.getId())));
    }

    @Test void manualWarehouseIssueRequiresExactManualSumsAndRejectsForeignOrRepeatedPositions() {
        var allocation = new WarehouseStockFactRequest.Allocation(newStock.getId(), new BigDecimal("5"), null);
        var manual = issue("5", PickingMethod.FIFO, StockAllocationMode.MANUAL, List.of(allocation));
        assertThat(service.allocateIssue(transfer.getSourceWarehouseId(), List.of(manual), false).getFirst().allocations().getFirst().stockBatchId()).isEqualTo(newStock.getStockBatchId());
        assertThatThrownBy(() -> service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("6", PickingMethod.FIFO, StockAllocationMode.MANUAL, List.of(allocation))), false)).hasMessageContaining("sum must equal");
        assertThatThrownBy(() -> service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("10", PickingMethod.FIFO, StockAllocationMode.MANUAL, List.of(allocation, allocation))), false)).hasMessageContaining("only once");
        var foreign = new WarehouseStockFactRequest.Allocation(UUID.randomUUID(), BigDecimal.ONE, null);
        assertThatThrownBy(() -> service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("1", PickingMethod.FIFO, StockAllocationMode.MANUAL, List.of(foreign))), false)).hasMessageContaining("outside");
    }

    @Test void manualWarehouseIssueSerialsAndOptionsRespectEligibilityAndCurrentScope() {
        material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        var unit = serial(oldStock, "S-1");
        when(units.findTransferPickingUnits(anyString(), any(), any(), anyList())).thenReturn(List.of(unit));
        var options = service.issueOptions(transfer.getSourceWarehouseId(), material.getId(), PickingMethod.FIFO, 0, 25);
        assertThat(options.eligibleAvailableQuantity()).isEqualByComparingTo("1");
        var plan = service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("1", PickingMethod.FIFO, StockAllocationMode.AUTO, null)), false);
        assertThat(plan.getFirst().allocations().getFirst().materialUnitIds()).containsExactly(unit.getId());
        var invalid = new WarehouseStockFactRequest.Allocation(oldStock.getId(), BigDecimal.ONE, List.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.allocateIssue(transfer.getSourceWarehouseId(), List.of(issue("1", PickingMethod.FIFO, StockAllocationMode.MANUAL, List.of(invalid))), false)).hasMessageContaining("does not belong");
        oldStock.setCompanyId(UUID.randomUUID());
        assertThat(service.issueOptions(transfer.getSourceWarehouseId(), material.getId(), PickingMethod.FIFO, 0, 25).eligibleAvailableQuantity()).isZero();
    }

    private WarehouseStockFactRequest.Line issue(String quantity, PickingMethod method, StockAllocationMode mode, List<WarehouseStockFactRequest.Allocation> allocations) {
        return new WarehouseStockFactRequest.Line(material.getId(), new BigDecimal(quantity), null, null, null, null, method, mode, allocations);
    }

    private MaterialUnit serial(StockBalance b,String serial) {
        var u=scope(new MaterialUnit()); u.setMaterialId(material.getId()); u.setWarehouseId(b.getWarehouseId());
        u.setStockBatchId(b.getStockBatchId()); u.setStorageLocationId(b.getStorageLocationId()); u.setStorageBinId(b.getStorageBinId());
        u.setManufacturerSerialNumber(serial); return u;
    }
    private TransferDispatchRequest.Line auto(TransferDocumentLine line,String qty,PickingMethod method) {
        var r=new TransferDispatchRequest.Line();r.setTransferLineId(line.getId());r.setDispatchedQuantity(new BigDecimal(qty));
        r.setAllocationMode(StockAllocationMode.AUTO);r.setPickingMethod(method);return r;
    }
    private TransferDispatchRequest.Line manual(String qty,TransferDispatchRequest.Allocation... allocations) {
        var r=auto(line,qty,PickingMethod.FIFO);r.setAllocationMode(StockAllocationMode.MANUAL);r.setAllocations(List.of(allocations));return r;
    }
    private TransferDispatchRequest.Line legacy(StockBalance b,String qty) {
        var r=new TransferDispatchRequest.Line();r.setTransferLineId(line.getId());r.setDispatchedQuantity(new BigDecimal(qty));
        r.setStockBatchId(b.getStockBatchId());r.setStorageLocationId(b.getStorageLocationId());r.setStorageBinId(b.getStorageBinId());return r;
    }
    private TransferDispatchRequest.Allocation allocation(StockBalance b,String qty) {
        var a=new TransferDispatchRequest.Allocation();a.setStockBalanceId(b.getId());a.setQuantity(new BigDecimal(qty));return a;
    }
    private TransferDispatchRequest request(TransferDispatchRequest.Line... rows) {
        var r=new TransferDispatchRequest();r.setLines(List.of(rows));return r;
    }
    private StockBalance position(LocalDate date,String qty) {
        var batch=scope(new StockBatch());batch.setMaterialId(material.getId());batch.setWarehouseId(transfer.getSourceWarehouseId());
        batch.setReceivedDate(date);batch.setCreatedAt(LocalDateTime.of(2025,1,1,0,0));
        var b=scope(new StockBalance());b.setMaterialId(material.getId());b.setMaterial(material);b.setWarehouseId(transfer.getSourceWarehouseId());
        b.setStockBatchId(batch.getId());b.setStockBatch(batch);b.setQuantity(new BigDecimal(qty));b.setAvailableQuantity(new BigDecimal(qty));return b;
    }
    private <T extends com.enterprise.mm.common.BusinessEntity> T scope(T entity) {
        entity.setId(UUID.randomUUID());entity.setTenantId(TenantContext.currentTenant());entity.setCompanyId(CompanyContext.currentCompany());entity.setStatus("ACTIVE");return entity;
    }
}
