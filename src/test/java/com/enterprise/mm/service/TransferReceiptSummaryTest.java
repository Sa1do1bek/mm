package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.mapper.TransferDocumentMapper;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.transfer.*;
import com.enterprise.mm.warehouse.history.IssueSourceTransferGuard;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferReceiptSummaryTest {
    private final TransferDocumentRepository documents = mock(TransferDocumentRepository.class);
    private final TransferDocumentLineRepository lines = mock(TransferDocumentLineRepository.class);
    private final TransferShipmentAllocationRepository allocations = mock(TransferShipmentAllocationRepository.class);
    private final TransferAllocationClearanceRepository clearances = mock(TransferAllocationClearanceRepository.class);
    private final TransferShipmentRepository shipments = mock(TransferShipmentRepository.class);
    private final TransferTransitLedgerService transit = mock(TransferTransitLedgerService.class);
    private final RoleDataScopeService scope = mock(RoleDataScopeService.class);
    private final TransferActionPolicy policy = mock(TransferActionPolicy.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final TransferProgressService progress = new TransferProgressService(documents, lines, allocations, clearances,
        mock(TransferExceptionRepository.class), mock(StockMovementRepository.class), materials, mock(UserRepository.class), transit, policy, scope);
    private final TransferDocumentService service = new TransferDocumentService(org.mockito.Mockito.mock(com.enterprise.mm.service.UnitOfMeasureDisplayService.class), documents, lines, new TransferDocumentMapper(),
        mock(WarehouseRepository.class), materials, mock(IdempotencyService.class), mock(AuditService.class), mock(AuditSnapshotSerializer.class),
        scope, mock(TransferPostingService.class), mock(StockPickingAllocationService.class), shipments, allocations, clearances, transit,
        policy, progress, mock(MaterialUnitEligibilityService.class), mock(IssueSourceTransferGuard.class), mock(TransferReceiptService.class));
    private final TransferDocument document = new TransferDocument();
    private final List<TransferDocumentLine> positions = new ArrayList<>();
    private final List<TransferShipmentAllocation> facts = new ArrayList<>();
    private final TransferShipment shipment = new TransferShipment();

    @BeforeEach void setup() {
        TenantContext.setTenant("default"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        document.setId(UUID.randomUUID()); document.setTenantId("default"); document.setCompanyId(CompanyContext.currentCompany());
        document.setStatus("IN_TRANSIT"); document.setSourceWarehouseId(UUID.randomUUID()); document.setDestinationWarehouseId(UUID.randomUUID());
        shipment.setId(UUID.randomUUID());
        when(documents.findByIdAndIsDeletedFalse(document.getId())).thenReturn(Optional.of(document));
        when(scope.canAccessWarehouse(document.getSourceWarehouseId(), document.getDestinationWarehouseId())).thenReturn(true);
        when(lines.findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc("default", document.getId())).thenReturn(positions);
        when(allocations.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc("default", document.getCompanyId(), document.getId())).thenReturn(facts);
        when(shipments.findByTenantIdAndCompanyIdAndTransferDocumentIdOrderByCreatedAtAscIdAsc("default", document.getCompanyId(), document.getId())).thenReturn(List.of(shipment));
        role("ADMIN");
    }

    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void groupsPositionsByUnitAndPreservesSevenPlaceQuantitiesAndUzbekLabels() {
        var kg = unit("KG", "kg");
        position(kg, "100.1234567", "100.1234567", "1000");
        position(kg, "50", "50", "500");
        position(unit("L", "l"), "60", "60", "600");
        position(unit("PCS", "dona"), "45", "45", "450");
        var response = service.get(document.getId());
        assertThat(response.getLineCount()).isEqualTo(4);
        assertThat(response.getUnitSummaries()).extracting(TransferProgressService.UnitSummary::unitLabel).containsExactly("kg", "l", "dona");
        assertThat(response.getUnitSummaries().getFirst().inTransit()).isEqualByComparingTo("150.1234567");
        assertThat(response.getUnitSummaries().get(1).inTransit()).isEqualByComparingTo("60");
        assertThat(response.getUnitSummaries().get(2).inTransit()).isEqualByComparingTo("45");
        assertThat(response.getLines()).extracting(l -> l.getUnitOfMeasure()).containsExactly("kg", "kg", "l", "dona");
        assertThat(response.getInTransitAmount()).isEqualByComparingTo("2550");
        assertThat(response.getBookCurrencyCode()).isEqualTo("UZS");
    }

    @Test void partialReceiptUsesExactRemainingCostLayersInsteadOfAverageUnitCost() {
        var line = position(unit("PCS", "dona"), "20", "20", "3000"); line.setUnitCost(n("150"));
        var allocation = facts.getFirst();
        allocation.setDispatchedQuantity(n("10")); allocation.setBookValue(n("1000"));
        clearance(allocation, TransferAllocationClearance.Kind.GOOD_RECEIPT, "10");
        when(transit.balance(allocation.getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(BigDecimal.ZERO, BigDecimal.ZERO));
        var expensiveLayer = new TransferShipmentAllocation(); expensiveLayer.setId(UUID.randomUUID());
        expensiveLayer.setTransferLineId(line.getId()); expensiveLayer.setShipmentId(shipment.getId());
        expensiveLayer.setDispatchedQuantity(n("10")); expensiveLayer.setBookValue(n("2000")); facts.add(expensiveLayer);
        when(transit.balance(expensiveLayer.getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(n("10"), n("2000")));
        var response = service.get(document.getId());
        assertThat(response.getUnitSummaries().getFirst().dispatched()).isEqualByComparingTo("20");
        assertThat(response.getUnitSummaries().getFirst().inTransit()).isEqualByComparingTo("10");
        assertThat(response.getInTransitAmount()).isEqualByComparingTo("2000");
        assertThat(response.getLines().getFirst().getInTransitValue()).isEqualByComparingTo("2000");
        assertThat(response.getShipments().getFirst().allocations().getFirst().remainingBookValue()).isZero();
        assertThat(response.getShipments().getFirst().allocations().getLast().remainingBookValue()).isEqualByComparingTo("2000");
    }

    @ParameterizedTest @ValueSource(strings = {"WAREHOUSE_HEAD", "WAREHOUSE_MANAGER"})
    void warehouseHeadsCanReadAggregateWithoutExposingDetailedCosts(String role) {
        position(unit("KG", "kg"), "10", "10", "123.4567"); role(role);
        var response = service.get(document.getId());
        assertThat(response.getInTransitAmount()).isEqualByComparingTo("123.4567");
        assertThat(response.getLines().getFirst().getUnitCost()).isNull();
        assertThat(response.getLines().getFirst().getInTransitValue()).isNull();
        assertThat(response.getShipments().getFirst().allocations().getFirst().remainingBookValue()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"WAREHOUSE_KEEPER", "WAREHOUSE_OPERATOR", "VIEWER", "AUDITOR", "MM_MANAGER"})
    void otherRolesDoNotGainFinancialAccess(String role) {
        position(unit("KG", "kg"), "10", "10", "100"); role(role);
        var response = service.get(document.getId());
        assertThat(response.getInTransitAmount()).isNull();
        assertThat(response.getLines().getFirst().getInTransitValue()).isNull();
        assertThat(response.getUnitSummaries().getFirst().inTransit()).isEqualByComparingTo("10");
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT"})
    void financeRolesRetainExactDetailedValues(String role) {
        position(unit("KG", "kg"), "10", "10", "100"); role(role);
        var response = service.get(document.getId());
        assertThat(response.getInTransitAmount()).isEqualByComparingTo("100");
        assertThat(response.getLines().getFirst().getInTransitValue()).isEqualByComparingTo("100");
    }

    @Test void fullyClearedAndZeroValueStockReturnZeroInsteadOfUnknown() {
        position(unit("KG", "kg"), "10", "10", "0");
        assertThat(service.get(document.getId()).getInTransitAmount()).isZero();
        clearance(facts.getFirst(), TransferAllocationClearance.Kind.GOOD_RECEIPT, "10");
        when(transit.balance(facts.getFirst().getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(BigDecimal.ZERO, BigDecimal.ZERO));
        var response = service.get(document.getId());
        assertThat(response.getInTransitAmount()).isZero();
        assertThat(response.getUnitSummaries().getFirst().inTransit()).isZero();
        assertThat(response.getLineCount()).isEqualTo(1);
    }

    @Test void mixedLegacyAndNewPositionsDoNotExposeFabricatedZeroValue() {
        position(unit("KG", "kg"), "10", "10", "100");
        var legacy = position(unit("L", "l"), "5", "5", "50");
        facts.removeLast(); legacy.setDispatchedQuantity(n("5")); legacy.setInTransitQuantity(n("5"));
        var response = service.get(document.getId());
        assertThat(response.getInTransitAmount()).isNull();
        assertThat(response.getLines().getLast().getInTransitValue()).isNull();
        assertThat(response.getLines().getLast().getUnitCost()).isNull();
    }

    @Test void unreconciledDocumentNeverClaimsExactTotal() {
        position(unit("KG", "kg"), "10", "10", "100"); document.setReconciliationStatus("REQUIRED");
        assertThat(service.get(document.getId()).getInTransitAmount()).isNull();
    }

    @Test void returnsPostedLossAndQuarantineReduceTransitButUndispatchedPlanDoesNotIncreaseIt() {
        position(unit("KG", "kg"), "30", "20", "200");
        var events = new ArrayList<TransferAllocationClearance>();
        var kinds = List.of(TransferAllocationClearance.Kind.GOOD_RECEIPT, TransferAllocationClearance.Kind.QUARANTINE_RECEIPT,
            TransferAllocationClearance.Kind.TRANSIT_RETURN, TransferAllocationClearance.Kind.TRANSIT_LOSS);
        var quantities = List.of("5", "4", "2", "3");
        for (int i = 0; i < kinds.size(); i++) {
            var event = new TransferAllocationClearance(); event.setKind(kinds.get(i)); event.setQuantity(n(quantities.get(i))); events.add(event);
        }
        var a = facts.getFirst();
        when(clearances.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc("default", document.getCompanyId(), a.getId())).thenReturn(events);
        when(transit.balance(a.getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(n("6"), n("60")));
        var response = service.get(document.getId()); var summary = response.getUnitSummaries().getFirst();
        assertThat(summary.inTransit()).isEqualByComparingTo("6");
        assertThat(summary.remaining()).isEqualByComparingTo("10");
        assertThat(summary.received()).isEqualByComparingTo("9");
        assertThat(summary.returned()).isEqualByComparingTo("2");
        assertThat(summary.postedLoss()).isEqualByComparingTo("3");
        assertThat(response.getInTransitAmount()).isEqualByComparingTo("60");
    }

    @Test void labelsFallbackPastBlankUzbekAndGenericNamesAndFinallyUseCode() {
        var unit = unit("KG", "  "); unit.setMeasurementNameShort(" kg ");
        position(unit, "10", "10", "100");
        assertLabel("kg");
        unit.setMeasurementNameShort(null); unit.setMeasurementNameLongUz("kilogramm"); assertLabel("kilogramm");
        unit.setMeasurementNameLongUz(null); assertLabel("рус");
        unit.setMeasurementNameShortRu(null); unit.setName("Kilogram"); assertLabel("Kilogram");
        unit.setName(null); assertLabel("KG");
    }

    @Test void sameLabelDoesNotMergeDifferentUnitIdentities() {
        position(unit("KG1", "kg"), "10", "10", "100");
        position(unit("KG2", "kg"), "20", "20", "200");
        assertThat(service.get(document.getId()).getUnitSummaries()).hasSize(2);
    }

    @Test void uzbekLongNameTakesPriorityOverGenericAndRussianLabels() {
        var unit = unit("PCS", null);
        unit.setMeasurementNameShort("шт"); unit.setMeasurementNameLongUz("dona");
        position(unit, "10", "10", "100");
        assertLabel("dona");
    }

    @Test void httpResponseSerializesReceiptSummaryForWarehouseHead() throws Exception {
        position(unit("KG", "kg"), "150", "150", "12345.6789");
        position(unit("L", "l"), "60", "60", "600");
        role("WAREHOUSE_HEAD");
        var controller = new com.enterprise.mm.controller.TransferDocumentController(service,
            mock(com.enterprise.mm.service.approval.ApprovalOperationSubmissionService.class),
            mock(TransferSubmissionService.class), mock(TransferLegacyReconciliationService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/transfers/{id}", document.getId())
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.lineCount").value(2))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.inTransitAmount").value(12945.6789))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.bookCurrencyCode").value("UZS"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.unitSummaries[0].inTransit").value(150))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.unitSummaries[0].unitLabel").value("kg"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.unitSummaries[1].unitLabel").value("l"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.lines[0].unitCost").isEmpty());
    }

    private void assertLabel(String expected) {
        var response = service.get(document.getId());
        assertThat(response.getUnitSummaries().getFirst().unitLabel()).isEqualTo(expected);
        assertThat(response.getLines().getFirst().getUnitOfMeasure()).isEqualTo(expected);
    }

    private TransferDocumentLine position(UnitOfMeasure unit, String plan, String dispatched, String value) {
        var material = new Material(); material.setId(UUID.randomUUID()); material.setUnitOfMeasure(unit);
        var line = new TransferDocumentLine(); line.setId(UUID.randomUUID()); line.setMaterialId(material.getId()); line.setMaterial(material);
        line.setPlannedQuantity(n(plan)); positions.add(line);
        var a = new TransferShipmentAllocation(); a.setId(UUID.randomUUID()); a.setTransferLineId(line.getId()); a.setShipmentId(shipment.getId());
        a.setDispatchedQuantity(n(dispatched)); a.setBookValue(n(value)); facts.add(a);
        when(transit.balance(a.getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(n(dispatched), n(value)));
        return line;
    }
    private void clearance(TransferShipmentAllocation a, TransferAllocationClearance.Kind kind, String quantity) {
        var c = new TransferAllocationClearance(); c.setKind(kind); c.setQuantity(n(quantity));
        when(clearances.findByTenantIdAndCompanyIdAndAllocationIdOrderByCreatedAtAscIdAsc("default", document.getCompanyId(), a.getId())).thenReturn(List.of(c));
    }
    private UnitOfMeasure unit(String code, String uzbek) {
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setCode(code);
        unit.setMeasurementNameShort("generic"); unit.setMeasurementNameShortRu("рус"); unit.setMeasurementNameShortUz(uzbek); return unit;
    }
    private void role(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("receipt-user", "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    private BigDecimal n(String value) { return new BigDecimal(value); }
}
