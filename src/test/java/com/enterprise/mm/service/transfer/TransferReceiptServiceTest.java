package com.enterprise.mm.service.transfer;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferReceiptServiceTest {
    final TransferTransitLedgerService transit = mock(TransferTransitLedgerService.class);
    final StorageLocationRepository locations = mock(StorageLocationRepository.class);
    final StorageBinRepository bins = mock(StorageBinRepository.class);
    final MaterialRepository materials = mock(MaterialRepository.class);
    final TransferReceiptService service = new TransferReceiptService(transit, locations, bins, materials);
    final TransferDocument document = new TransferDocument();
    final List<TransferShipmentAllocation> allocations = new ArrayList<>();
    final StorageLocation location = new StorageLocation();
    final UUID shipmentId = UUID.randomUUID(), lineId = UUID.randomUUID(), batchId = UUID.randomUUID(), materialId = UUID.randomUUID();

    @BeforeEach void setup() {
        TenantContext.setTenant("receipt-test"); CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        document.setId(UUID.randomUUID()); document.setDestinationWarehouseId(UUID.randomUUID());
        location.setId(UUID.randomUUID()); location.setWarehouseId(document.getDestinationWarehouseId()); scoped(location);
        when(locations.findByIdAndIsDeletedFalse(location.getId())).thenReturn(Optional.of(location));
        when(locations.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), CompanyContext.currentCompany(), document.getDestinationWarehouseId())).thenReturn(List.of(location));
        allocation("5"); allocation("3");
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @ParameterizedTest @CsvSource({"6,2", "8,0", "0,8"})
    void fullRowSplitsGoodAndDamagedAcrossCostLayersWithoutLosingQuantities(String good, String damaged) {
        var request = request(good, damaged); var result = service.prepare(document, request, allocations);
        assertThat(sum(result, false)).isEqualByComparingTo(good);
        assertThat(sum(result, true)).isEqualByComparingTo(damaged);
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().input().getReceivedQuantity().add(result.getFirst().input().getDamagedQuantity())).isEqualByComparingTo("5");
        assertThat(result.getLast().input().getReceivedQuantity().add(result.getLast().input().getDamagedQuantity())).isEqualByComparingTo("3");
        verify(transit, never()).record(any(), any(), any(), any(), any());
    }

    @ParameterizedTest @CsvSource({"9,0,quantityExceeded", "6,3,quantityExceeded", "6,0,fullRowRequired", "0,0,quantityRequired", "-1,9,quantityInvalid", "7.5,0.5,quantityInvalid"})
    void rejectsInvalidFullRowAndReturnsFieldContext(String good, String bad, String code) {
        var request = request(good, bad);
        assertThatThrownBy(() -> service.prepare(document, request, allocations)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt." + code);
            assertThat(ex.getContext()).containsEntry("rowIndex", 0).containsEntry("rowKey", request.getLines().getFirst().getRowKey());
            assertThat(ex.getContext()).containsKey("field");
        });
    }

    @Test void legacyPartialReceiptKeepsOptionalLocationAndNullModeSemantics() {
        var request = request("2", "0"); request.setReceiptMode(null);
        var input = request.getLines().getFirst(); input.setRowKey(null); input.setShipmentAllocationId(allocations.getFirst().getId()); input.setStorageLocationId(null);
        assertThat(sum(service.prepare(document, request, allocations), false)).isEqualByComparingTo("2");
    }

    @Test void rowKeyIsScopedToAvailableAllocationsAndCannotBeCombinedWithLegacyIds() {
        var request = request("8", "0"); var input = request.getLines().getFirst(); input.setRowKey(UUID.randomUUID().toString());
        rejects(request, "rowInvalid");
        input.setRowKey(key()); input.setShipmentAllocationId(allocations.getFirst().getId()); rejects(request, "rowInvalid");
    }

    @Test void rejectsBlankKeyInsteadOfFallingBackToLegacySelection() {
        var request = request("5", "0"); var input = request.getLines().getFirst(); input.setRowKey(" "); input.setShipmentAllocationId(allocations.getFirst().getId());
        rejects(request, "rowInvalid");
    }

    @Test void duplicateGroupAndMixedGroupAllocationSelectionsAreRejected() {
        var request = request("8", "0"); request.setLines(List.of(request.getLines().getFirst(), request.getLines().getFirst())); rejects(request, "duplicateRow");
        request = request("8", "0"); var legacy = new TransferReceiveRequest.Line(); legacy.setShipmentAllocationId(allocations.getFirst().getId()); legacy.setReceivedQuantity(n("1"));
        request.setLines(List.of(request.getLines().getFirst(), legacy)); rejects(request, "duplicateRow");
    }

    @Test void cumulativeLegacyReceiptsCannotExceedOneAllocationAcrossDifferentLocations() {
        var request = request("4", "0"); request.setReceiptMode(TransferReceiptDtos.ReceiptMode.PARTIAL);
        var first = request.getLines().getFirst(); first.setRowKey(null); first.setShipmentAllocationId(allocations.getFirst().getId());
        var second = new TransferReceiveRequest.Line(); second.setShipmentAllocationId(allocations.getFirst().getId()); second.setReceivedQuantity(n("2"));
        request.setLines(List.of(first, second)); rejects(request, "quantityExceeded");
    }

    @Test void legacyLineSelectionRemainsAmbiguousAfterAnEarlierInputConsumesOneOfItsAllocations() {
        var request = request("5", "0"); request.setReceiptMode(null);
        var first = request.getLines().getFirst(); first.setRowKey(null); first.setShipmentAllocationId(allocations.getFirst().getId());
        var second = new TransferReceiveRequest.Line(); second.setTransferLineId(lineId); second.setReceivedQuantity(n("3"));
        request.setLines(List.of(first, second));
        assertThatThrownBy(() -> service.prepare(document, request, allocations)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt.rowInvalid");
            assertThat(ex.getContext()).containsEntry("rowIndex", 1).containsEntry("transferLineId", lineId);
        });
    }

    @Test void legacyLineSelectionWorksWhenOnlyOneAllocationWasUnresolvedBeforeTheCommand() {
        when(transit.balance(allocations.getFirst().getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(BigDecimal.ZERO, BigDecimal.ZERO));
        var request = request("2", "0"); request.setReceiptMode(null);
        var input = request.getLines().getFirst(); input.setRowKey(null); input.setShipmentAllocationId(null); input.setTransferLineId(lineId); input.setStorageLocationId(null);
        var result = service.prepare(document, request, allocations);
        assertThat(result).hasSize(1); assertThat(result.getFirst().allocation().getId()).isEqualTo(allocations.getLast().getId());
    }

    @Test void legacyAllocationCanStillBeSplitAcrossLocationsWithinItsOriginalLimit() {
        var request = request("2", "0"); request.setReceiptMode(null);
        var first = request.getLines().getFirst(); first.setRowKey(null); first.setShipmentAllocationId(allocations.getFirst().getId());
        var second = new TransferReceiveRequest.Line(); second.setShipmentAllocationId(allocations.getFirst().getId()); second.setReceivedQuantity(n("3"));
        request.setLines(List.of(first, second));
        var result = service.prepare(document, request, allocations); assertThat(result).hasSize(2);
        assertThat(sum(result, false)).isEqualByComparingTo("5");
    }

    @Test void damagedStockRequiresNonblankBoundedReason() {
        var request = request("6", "2"); var input = request.getLines().getFirst(); input.setConditionEvidence("  "); rejects(request, "reasonRequired");
        input.setShortageReason("legacy explanation"); assertThat(service.prepare(document, request, allocations)).isNotEmpty();
        input.setConditionEvidence("x".repeat(2001)); rejects(request, "reasonTooLong");
    }

    @Test void fullReceiptRequiresActiveDestinationLocation() {
        var request = request("8", "0"); var input = request.getLines().getFirst(); input.setStorageLocationId(null); rejects(request, "locationRequired");
        input.setStorageLocationId(location.getId()); location.setStatus("INACTIVE"); rejects(request, "locationInvalid");
        location.setStatus("ACTIVE"); location.setWarehouseId(UUID.randomUUID()); rejects(request, "locationInvalid");
        location.setWarehouseId(document.getDestinationWarehouseId()); location.setCompanyId(UUID.randomUUID()); rejects(request, "locationInvalid");
    }

    @Test void activeBinsMakeBinMandatoryAndBinMustBelongToSelectedLocation() {
        var bin = bin(); var request = request("8", "0"); rejects(request, "binRequired");
        request.getLines().getFirst().setStorageBinId(bin.getId()); assertThat(service.prepare(document, request, allocations)).isNotEmpty();
        bin.setStorageLocationId(UUID.randomUUID()); rejects(request, "binInvalid");
        bin.setStorageLocationId(location.getId()); bin.setStatus("INACTIVE"); rejects(request, "binInvalid");
    }

    @Test void quantityScalePreservesSevenDecimalPlacesAndRejectsExtraDigits() {
        allocations.forEach(a -> a.setQuantityScale(7));
        var request = request("1.1234567", "0"); request.setReceiptMode(TransferReceiptDtos.ReceiptMode.PARTIAL);
        assertThat(sum(service.prepare(document, request, allocations), false)).isEqualByComparingTo("1.1234567");
        request.getLines().getFirst().setReceivedQuantity(n("1.12345678")); rejects(request, "quantityInvalid");
    }

    @Test void serialAcceptedAndDamagedSelectionsMustBeDistinctAndMatchQuantities() {
        var material = new Material(); material.setSerialTrackingPolicy(com.enterprise.mm.enums.SerialTrackingPolicy.REQUIRED);
        when(materials.findByIdAndIsDeletedFalse(materialId)).thenReturn(Optional.of(material));
        allocations.forEach(a -> a.setSerialNumber("SERIAL"));
        var request = request("6", "2"); request.getLines().getFirst().setRowKey(key());
        rejects(request, "serialCountMismatch");
        var good = java.util.stream.IntStream.range(0, 6).mapToObj(i -> UUID.randomUUID()).toList();
        var bad = java.util.stream.IntStream.range(0, 2).mapToObj(i -> UUID.randomUUID()).toList();
        request.getLines().getFirst().setMaterialUnitIds(good); request.getLines().getFirst().setDamagedMaterialUnitIds(bad);
        var result = service.prepare(document, request, allocations);
        assertThat(result.stream().flatMap(r -> r.input().getMaterialUnitIds().stream()).toList()).containsExactlyElementsOf(good);
        assertThat(result.stream().flatMap(r -> r.input().getDamagedMaterialUnitIds().stream()).toList()).containsExactlyElementsOf(bad);
        request.getLines().getFirst().setDamagedMaterialUnitIds(List.of(good.getFirst(), UUID.randomUUID())); rejects(request, "serialDuplicate");
    }

    @Test void optionalBatchSerialPreservesLegacyReceiptsWithoutPhysicalUnitSelection() {
        allocations.forEach(a -> a.setSerialNumber("LEGACY-SERIAL"));
        var request = request("6", "2");
        assertThat(service.prepare(document, request, allocations)).hasSize(2);
        request.getLines().getFirst().setReceivedQuantity(n("5.5"));
        request.getLines().getFirst().setDamagedQuantity(n("2.5")); rejects(request, "quantityInvalid");
    }

    @Test void contextGroupsCostLayersKeepsClearedRowsAndUsesUzbekLabels() {
        var response = response(); var context = service.describe(response);
        assertThat(context.canReceive()).isTrue(); assertThat(context.rows()).hasSize(1);
        var row = context.rows().getFirst(); assertThat(row.maxReceivableQuantity()).isEqualByComparingTo("8");
        assertThat(row.unitLabel()).isEqualTo("dona"); assertThat(row.editable()).isTrue(); assertThat(row.rowKey()).isEqualTo(key());
        assertThat(context.locations()).hasSize(1); assertThat(context.defaultReceiptMode()).isEqualTo(TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS);
        response.setAllowedActions(List.of()); assertThat(service.describe(response).rows().getFirst().editable()).isFalse();
    }

    @Test void noActiveLocationsBlocksNewFormAndDoesNotSuggestInactiveDefaults() {
        location.setStatus("INACTIVE"); var response = response(); response.setDestinationLocationId(location.getId());
        var context = service.describe(response); assertThat(context.canReceive()).isFalse();
        assertThat(context.disabledReason()).isEqualTo("NO_ACTIVE_RECEIPT_LOCATION");
        assertThat(context.rows().getFirst().defaultStorageLocationId()).isNull();
    }

    @Test void unifiedDetailRetainsUndispatchedPositionsWithoutAllowingReceipt() {
        var response = response(); var pending = new TransferDocumentLineResponse(); pending.setId(UUID.randomUUID());
        pending.setMaterialId(UUID.randomUUID()); pending.setMaterialName("Pending material"); pending.setUnitOfMeasure("kg"); pending.setPlannedQuantity(n("45"));
        response.setLines(List.of(response.getLines().getFirst(), pending));
        var row = service.describe(response).rows().getLast();
        assertThat(row.transferLineId()).isEqualTo(pending.getId()); assertThat(row.plannedQuantity()).isEqualByComparingTo("45");
        assertThat(row.editable()).isFalse(); assertThat(row.maxReceivableQuantity()).isZero();
        assertThat(row.disabledReason()).isEqualTo("NOT_DISPATCHED"); assertThat(row.shipmentId()).isNull();
        var request = request("1", "0"); request.getLines().getFirst().setRowKey(row.rowKey()); rejects(request, "rowInvalid");
    }

    @Test void clearedReceiptRowRemainsVisibleWithActualReceiptHistoryAndDisabledInputs() {
        var response = response();
        var history = new TransferShipmentDtos.Clearance(UUID.randomUUID(), "GOOD_RECEIPT", n("8"), null, LocalDate.now(), null, null, null, null, null);
        var allocation = new TransferShipmentDtos.Allocation(allocations.getFirst().getId(), lineId, batchId, "BATCH", null, null,
            n("8"), BigDecimal.ZERO, null, "PCS", 0, List.of(history));
        response.setShipments(List.of(new TransferShipmentDtos.Shipment(shipmentId, LocalDate.now(), List.of(allocation))));
        var row = service.describe(response).rows().getFirst();
        assertThat(row.maxReceivableQuantity()).isZero();
        assertThat(row.receivedQuantityToDate()).isEqualByComparingTo("8");
        assertThat(row.editable()).isFalse(); assertThat(row.disabledReason()).isEqualTo("ALREADY_CLEARED");
    }

    @Test void differentShipmentsOrBatchesHaveDifferentStableRowKeys() {
        assertThat(key()).isNotEqualTo(TransferReceiptService.rowKey(UUID.randomUUID(), lineId, batchId, null));
        assertThat(key()).isNotEqualTo(TransferReceiptService.rowKey(shipmentId, lineId, UUID.randomUUID(), null));
    }

    @Test void legacyPayloadSerializationStaysByteCompatibleForExistingIdempotencyHashes() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String oldPayload = "{\"receivedDate\":\"2026-10-27\",\"expectedVersion\":12,\"lines\":[{\"transferLineId\":null,\"shipmentAllocationId\":\"00000000-0000-0000-0000-000000000001\",\"conditionEvidence\":null,\"attachmentIds\":null,\"receivedQuantity\":6,\"damagedQuantity\":0,\"shortageReason\":null,\"storageLocationId\":null,\"storageBinId\":null,\"materialUnitIds\":null}]}";
        var request = json.readValue(oldPayload, TransferReceiveRequest.class);
        assertThat(json.writeValueAsString(request)).isEqualTo(oldPayload);
        request.setReceiptMode(TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS);
        request.getLines().getFirst().setRowKey(key());
        assertThat(json.writeValueAsString(request)).contains("FULL_SELECTED_ROWS", key()).isNotEqualTo(oldPayload);
    }

    @Test void httpValidationReturnsRowAndFieldAndStaleVersionHas409Context() throws Exception {
        var facade = mock(com.enterprise.mm.service.TransferDocumentService.class);
        when(facade.receive(eq(document.getId()), any(), anyString())).thenAnswer(inv -> {
            service.prepare(document, inv.getArgument(1), allocations); return new TransferDocumentResponse();
        });
        var controller = new com.enterprise.mm.controller.TransferDocumentController(facade,
            mock(com.enterprise.mm.service.approval.ApprovalOperationSubmissionService.class),
            mock(TransferSubmissionService.class), mock(TransferLegacyReconciliationService.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var context = service.describe(response());
        when(facade.receiptContext(document.getId())).thenReturn(context);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/transfers/{id}/receive-context", document.getId()).accept("application/json"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rows[0].maxReceivableQuantity").value(8))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rows[0].unitLabel").value("dona"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.defaultReceiptMode").value("FULL_SELECTED_ROWS"));
        var request = request("9", "0");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/transfers/{id}/receive", document.getId())
                .header("Idempotency-Key", "test-key").contentType("application/json").accept("application/json").content(json.writeValueAsBytes(request)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("transfers.receipt.quantityExceeded"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.rowKey").value(key()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.field").value("receivedQuantity"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.maxQuantity").value(8));
        doThrow(new com.enterprise.mm.error.TransferReceiptConflictException(8L)).when(facade).receive(eq(document.getId()), any(), anyString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/transfers/{id}/receive", document.getId())
                .header("Idempotency-Key", "test-key").contentType("application/json").accept("application/json").content(json.writeValueAsBytes(request("8", "0"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("transfers.receipt.staleVersion"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.currentVersion").value(8));
    }

    private TransferDocumentResponse response() {
        var response = new TransferDocumentResponse(); response.setDestinationWarehouseId(document.getDestinationWarehouseId()); response.setVersion(7L); response.setAllowedActions(List.of("receive"));
        var line = new TransferDocumentLineResponse(); line.setId(lineId); line.setMaterialId(materialId); line.setMaterialName("Pump"); line.setUnitOfMeasure("dona"); line.setPlannedQuantity(n("10")); response.setLines(List.of(line));
        response.setShipments(List.of(new TransferShipmentDtos.Shipment(shipmentId, LocalDate.now(), allocations.stream().map(a ->
            new TransferShipmentDtos.Allocation(a.getId(), lineId, batchId, "BATCH", null, null, a.getDispatchedQuantity(), a.getDispatchedQuantity(), null, "PCS", 0)).toList())));
        return response;
    }
    private TransferReceiveRequest request(String good, String bad) {
        var request = new TransferReceiveRequest(); request.setReceiptMode(TransferReceiptDtos.ReceiptMode.FULL_SELECTED_ROWS); request.setExpectedVersion(7L); request.setReceivedDate(LocalDate.now());
        var input = new TransferReceiveRequest.Line(); input.setRowKey(key()); input.setReceivedQuantity(n(good)); input.setDamagedQuantity(n(bad)); input.setConditionEvidence("Damaged packaging"); input.setStorageLocationId(location.getId());
        request.setLines(List.of(input)); return request;
    }
    private void allocation(String quantity) {
        var a = new TransferShipmentAllocation(); a.setId(UUID.randomUUID()); a.setShipmentId(shipmentId); a.setTransferLineId(lineId); a.setSourceStockBatchId(batchId); a.setMaterialId(materialId); a.setQuantityScale(0); a.setDispatchedQuantity(n(quantity)); allocations.add(a);
        when(transit.balance(a.getId())).thenReturn(new TransferTransitLedgerService.TransitBalance(n(quantity), n(quantity).multiply(n("100"))));
    }
    private StorageBin bin() {
        var bin = new StorageBin(); bin.setId(UUID.randomUUID()); bin.setWarehouseId(document.getDestinationWarehouseId()); bin.setStorageLocationId(location.getId()); scoped(bin);
        when(bins.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), document.getDestinationWarehouseId())).thenReturn(List.of(bin));
        when(bins.findByIdAndIsDeletedFalse(bin.getId())).thenReturn(Optional.of(bin)); return bin;
    }
    private void scoped(BusinessEntity entity) { entity.setTenantId(TenantContext.currentTenant()); entity.setCompanyId(CompanyContext.currentCompany()); entity.setStatus("ACTIVE"); }
    private String key() { return TransferReceiptService.rowKey(shipmentId, lineId, batchId, allocations.isEmpty() ? null : allocations.getFirst().getSerialNumber()); }
    private BigDecimal n(String s) { return new BigDecimal(s); }
    private BigDecimal sum(List<TransferReceiptService.Prepared> rows, boolean bad) { return rows.stream().map(r -> bad ? r.input().getDamagedQuantity() : r.input().getReceivedQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private void rejects(TransferReceiveRequest request, String code) { assertThatThrownBy(() -> service.prepare(document, request, allocations)).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getMessageKey()).isEqualTo("transfers.receipt." + code)); }
}
