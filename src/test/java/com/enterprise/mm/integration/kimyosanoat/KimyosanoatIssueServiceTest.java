package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.ReferenceMapping;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.StockMovementDocumentCreateRequest;
import com.enterprise.mm.payload.StockMovementDocumentResponse;
import com.enterprise.mm.payload.StockMovementPostRequest;
import com.enterprise.mm.payload.StockMovementStatusChangeRequest;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.StockMovementDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class KimyosanoatIssueServiceTest {
    private static final UUID WAREHOUSE_1C_ID = UUID.fromString("671308d4-9ed8-11df-8030-001a4bcf769a");
    private final UUID company = UUID.randomUUID(), warehouse = UUID.randomUUID(), material = UUID.randomUUID(), position = UUID.randomUUID(), movement = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final KimyosanoatClient client = mock(KimyosanoatClient.class);
    private final RoleDataScopeService access = mock(RoleDataScopeService.class);
    private final ReferenceMappingRepository mappings = mock(ReferenceMappingRepository.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final StockBalanceRepository balances = mock(StockBalanceRepository.class);
    private final StockMovementDocumentService movements = mock(StockMovementDocumentService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> invocation.getMethod().getName().equals("update") ? 1 : RETURNS_DEFAULTS.answer(invocation));
    private final KimyosanoatIssueService service = new KimyosanoatIssueService(client, access, mappings, warehouses, materials, balances, movements, jdbc, json);
    private ObjectNode source;

    @BeforeEach void setup() {
        TenantContext.setTenant("tenant"); CompanyContext.setCompany(company);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", ""));
        when(access.availabilityWarehouseId(warehouse)).thenReturn(warehouse);
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("tenant", company, warehouse)).thenReturn(Optional.of(new Warehouse()));
        var warehouseMapping = new ReferenceMapping(); warehouseMapping.setStatus("ACTIVE"); warehouseMapping.setExternalSystem("KIMYOSANOAT"); warehouseMapping.setExternalCode("12");
        when(mappings.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndIsDeletedFalseOrderByPriorityAsc("tenant", company, "WAREHOUSE", warehouse)).thenReturn(List.of(warehouseMapping));
        var productMapping = new ReferenceMapping(); productMapping.setStatus("ACTIVE"); productMapping.setExternalSystem("KIMYOSANOAT"); productMapping.setReferenceId(material);
        when(mappings.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse("tenant", company, "MATERIAL", "KIMYOSANOAT", "9", "MANAGEMENT")).thenReturn(Optional.of(productMapping));
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setTenantId("tenant"); unit.setCompanyId(company); unit.setCode("kg");
        var product = new Material(); product.setTenantId("tenant"); product.setCompanyId(company); product.setStatus("ACTIVE"); product.setUnitOfMeasure(unit); product.setUnitOfMeasureId(unit.getId());
        when(materials.findByIdAndIsDeletedFalse(material)).thenReturn(Optional.of(product));
        var stock = new StockBalance(); stock.setId(position); stock.setTenantId("tenant"); stock.setCompanyId(company); stock.setWarehouseId(warehouse); stock.setMaterialId(material);
        stock.setStockBatchId(UUID.randomUUID()); stock.setAvailableQuantity(new BigDecimal("200"));
        when(balances.findByIdAndIsDeletedFalseForUpdate(position)).thenReturn(Optional.of(stock));
        when(jdbc.queryForList(contains("warehouse_source_profiles"), eq(UUID.class), eq("tenant"), eq(company), eq("3203"), eq("3203")))
            .thenReturn(List.of(material));
        source = json.createObjectNode().put("id", 41).put("fromWarehouseId", 12).put("productId", 9).put("productNCode", "3203")
            .put("lastStatus", "EXIT_ACCEPTED").put("confirmedNettoKg", 125.5).put("measureUnit", "kg").put("buyerName", "Buyer");
        source.putArray("confirmedStatuses").add("EXIT_ACCEPTED");
        var page = json.createObjectNode(); page.putArray("content").add(source); page.put("totalElements", 1).put("totalPages", 1);
        when(client.list(12, 0, 20)).thenReturn(page);
        when(client.detail(12, 41)).thenAnswer(call -> source.deepCopy());
        var created = new StockMovementDocumentResponse(); created.setId(movement); created.setVersion(0L);
        var approved = new StockMovementDocumentResponse(); approved.setId(movement); approved.setVersion(2L);
        when(movements.createKimyosanoatIssue(any())).thenReturn(created);
        when(movements.changeStatus(eq(movement), any())).thenReturn(created, approved);
        when(movements.postKimyosanoatIssue(eq(movement), any(), any())).thenReturn(approved);
    }
    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }
    private KimyosanoatIssueService.Document listed() { return service.list(warehouse, 0, 20).content().get(0); }

    private void autoWarehouseMapping() {
        when(jdbc.queryForList(contains("select distinct master_id from warehouse_source_master_links"), eq(UUID.class),
            eq("tenant"), eq(company), eq("8030001a4bcf769a11df9ed8671308d4"))).thenReturn(List.of(warehouse));
    }

    @Test void automaticIssuePostsOnlyWhenOnePositionCoversTheQuantity() {
        autoWarehouseMapping();
        when(jdbc.queryForList(contains("select id from stock_balances"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq(material), eq(new BigDecimal("125.5")))).thenReturn(List.of(position));
        var result = service.autoApprove(UUID.randomUUID(), 41, WAREHOUSE_1C_ID, source, List.of(12L));
        assertThat(result.movementDocumentId()).isEqualTo(movement);
        verify(movements).postKimyosanoatIssue(eq(movement), any(StockMovementPostRequest.class), eq("kimyosanoat-issue-41"));
        verify(access, never()).assertWarehouseAccess(any());
    }

    @Test void multipleCoveringPositionsRequireManualReview() {
        autoWarehouseMapping();
        when(jdbc.queryForList(contains("select id from stock_balances"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq(material), eq(new BigDecimal("125.5")))).thenReturn(List.of(position, UUID.randomUUID()));
        assertThatThrownBy(() -> service.autoApprove(UUID.randomUUID(), 41, WAREHOUSE_1C_ID, source, List.of(12L)))
            .isInstanceOf(KimyosanoatAutoReviewException.class)
            .extracting("reasonCode").isEqualTo("STOCK_POSITION_AMBIGUOUS");
        verifyNoInteractions(movements);
    }

    @Test void insufficientOwnedStockRequiresManualReview() {
        autoWarehouseMapping();
        when(jdbc.queryForList(contains("select id from stock_balances"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq(material), eq(new BigDecimal("125.5")))).thenReturn(List.of());
        assertThatThrownBy(() -> service.autoApprove(UUID.randomUUID(), 41, WAREHOUSE_1C_ID, source, List.of(12L)))
            .isInstanceOf(KimyosanoatAutoReviewException.class)
            .extracting("reasonCode").isEqualTo("STOCK_INSUFFICIENT");
        verifyNoInteractions(movements);
    }

    @Test void postsExactSourceQuantityFromSelectedWarehousePosition() {
        var document = listed();
        var result = service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint()));
        assertThat(result.movementDocumentId()).isEqualTo(movement);
        var capture = ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(movements).createKimyosanoatIssue(capture.capture());
        assertThat(capture.getValue().getWarehouseId()).isEqualTo(warehouse);
        assertThat(capture.getValue().getItems().get(0).getQuantity()).isEqualByComparingTo("125.5");
        assertThat(capture.getValue().getItems().get(0).getMaterialId()).isEqualTo(material);
        verify(movements, times(2)).changeStatus(eq(movement), any(StockMovementStatusChangeRequest.class));
        verify(movements).postKimyosanoatIssue(eq(movement), any(StockMovementPostRequest.class), eq("kimyosanoat-issue-41"));
    }
    @Test void staleSourceNeverPosts() {
        var document = listed(); source.put("confirmedNettoKg", 126);
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint()))).isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void repeatedApprovalReturnsExistingMovementWithoutAnotherStockChange() throws Exception {
        var document = listed();
        var row = mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn("tenant");
        when(row.getObject(2, UUID.class)).thenReturn(company);
        when(row.getObject(3, UUID.class)).thenReturn(warehouse);
        when(row.getObject(4, UUID.class)).thenReturn(material);
        when(row.getObject(5, UUID.class)).thenReturn(movement);
        when(row.getString(6)).thenReturn(document.fingerprint());
        when(row.getString(7)).thenReturn("POSTED");
        doAnswer(call -> ((ResultSetExtractor<?>) call.getArgument(1)).extractData(row))
            .when(jdbc).query(anyString(), any(ResultSetExtractor.class), eq(41L));
        var result = service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint()));
        assertThat(result.alreadyPosted()).isTrue();
        assertThat(result.movementDocumentId()).isEqualTo(movement);
        verifyNoInteractions(movements);
        verify(balances, never()).findByIdAndIsDeletedFalseForUpdate(any());
    }
    @Test void insufficientStockNeverPosts() {
        var document = listed();
        var stock = balances.findByIdAndIsDeletedFalseForUpdate(position).orElseThrow(); stock.setAvailableQuantity(BigDecimal.ONE);
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(movements);
    }
    @Test void warehouseDenialHappensBeforeSourceRead() {
        when(access.availabilityWarehouseId(warehouse)).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.list(warehouse, 0, 20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(client);
    }
    @Test void sourceCannotReturnAnotherWarehousesDocument() {
        source.put("fromWarehouseId", 13);
        assertThatThrownBy(() -> service.list(warehouse, 0, 20)).isInstanceOf(BusinessException.class);
    }
    @Test void matchesExactNCodeAgainstTheImported1cProfile() {
        UUID importedMaterial = UUID.randomUUID();
        source.put("productNCode", "01226");
        when(jdbc.queryForList(contains("warehouse_source_profiles"), eq(UUID.class), eq("tenant"), eq(company),
            eq("01226"), eq("01226"))).thenReturn(List.of(importedMaterial));
        assertThat(listed().materialId()).isEqualTo(importedMaterial);
    }
    @Test void unresolvedCodeNeverFallsBackToAnotherMapping() {
        source.put("productNCode", "9999");
        assertThat(listed().state()).isEqualTo("MAPPING_REQUIRED");
    }
    @Test void missingCodeDoesNotUseLegacyMapping() {
        source.putNull("productNCode");
        assertThat(listed().state()).isEqualTo("MAPPING_REQUIRED");
    }
    @Test void duplicateSourceCodesDoNotSelectEitherMaterial() {
        when(jdbc.queryForList(contains("warehouse_source_profiles"), eq(UUID.class), eq("tenant"), eq(company),
            eq("3203"), eq("3203"))).thenReturn(List.of(material, UUID.randomUUID()));
        assertThat(listed().state()).isEqualTo("MAPPING_REQUIRED");
    }
    @Test void changedProductCodeInvalidatesApproval() {
        var doc = listed();
        source.put("productNCode", "3204");
        assertThatThrownBy(() -> service.approve(41,
            new KimyosanoatIssueService.ApproveRequest(warehouse, position, doc.fingerprint())))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void convertsKilogramsToTonnesWithoutRounding() {
        var product = materials.findByIdAndIsDeletedFalse(material).orElseThrow();
        product.getUnitOfMeasure().setCode("1C-TON"); product.getUnitOfMeasure().setMeasurementNameShortRu("Т");
        var document = listed();
        assertThat(document.quantityKg()).isEqualTo("125.5");
        assertThat(document.quantityStock()).isEqualTo("0.1255");
        service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint()));
        var capture = ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(movements).createKimyosanoatIssue(capture.capture());
        assertThat(capture.getValue().getItems().getFirst().getQuantity()).isEqualByComparingTo("0.1255");
    }
    @Test void rejectsAConversionThatExceedsTheStockUnitPrecision() {
        var product = materials.findByIdAndIsDeletedFalse(material).orElseThrow();
        product.getUnitOfMeasure().setCode("1C-TON"); product.getUnitOfMeasure().setMeasurementNameShortRu("Т");
        source.put("confirmedNettoKg", 125.55);
        assertThat(listed().state()).isEqualTo("UNSUPPORTED");
        verifyNoInteractions(movements);
    }
    @Test void changedMaterialUnitRequiresRefreshBeforeApproval() {
        var document = listed();
        var unit = materials.findByIdAndIsDeletedFalse(material).orElseThrow().getUnitOfMeasure();
        unit.setCode("1C-TON"); unit.setMeasurementNameShortRu("Т");
        assertThatThrownBy(() -> service.approve(41,
            new KimyosanoatIssueService.ApproveRequest(warehouse, position, document.fingerprint())))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void importedWarehouseIsResolvedByIts1cIdentityWithoutManualMapping() {
        when(mappings.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndReferenceIdAndIsDeletedFalseOrderByPriorityAsc(
            "tenant", company, "WAREHOUSE", warehouse)).thenReturn(List.of());
        when(jdbc.queryForList(contains("warehouse_source_master_links"), eq(String.class), eq("tenant"), eq(company), eq(warehouse)))
            .thenReturn(List.of("00112233445566778899aabbccddeeff"));
        when(client.resolveWarehouses("00112233445566778899aabbccddeeff")).thenReturn(List.of(12L));
        var importedPage = json.createObjectNode(); importedPage.putArray("content").add(source);
        when(client.listBy1c("00112233445566778899aabbccddeeff", 0, 20)).thenReturn(importedPage);
        assertThat(listed().state()).isEqualTo("PENDING");
    }
    @Test void same1cWarehouseIncludesAndApprovesEitherSourceWarehouse() {
        String identity = "00112233445566778899aabbccddeeff";
        when(jdbc.queryForList(contains("warehouse_source_master_links"), eq(String.class), eq("tenant"), eq(company), eq(warehouse)))
            .thenReturn(List.of(identity));
        when(client.resolveWarehouses(identity)).thenReturn(List.of(12L, 13L));
        var second = source.deepCopy().put("id", 42).put("fromWarehouseId", 13);
        var page = json.createObjectNode(); page.putArray("content").add(source).add(second); page.put("totalElements", 2).put("totalPages", 1);
        when(client.listBy1c(identity, 0, 20)).thenReturn(page);
        when(client.detailBy1c(identity, 42)).thenReturn(second);
        var documents = service.list(warehouse, 0, 20).content();
        assertThat(documents).extracting(KimyosanoatIssueService.Document::sourceId).containsExactly("41", "42");
        service.approve(42, new KimyosanoatIssueService.ApproveRequest(warehouse, position, documents.get(1).fingerprint()));
        var capture = ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(movements).createKimyosanoatIssue(capture.capture());
        assertThat(capture.getValue().getWarehouseId()).isEqualTo(warehouse);
    }
    @Test void same1cWarehouseRejectsUnrelatedSourceWarehouse() {
        String identity = "00112233445566778899aabbccddeeff";
        when(jdbc.queryForList(contains("warehouse_source_master_links"), eq(String.class), eq("tenant"), eq(company), eq(warehouse)))
            .thenReturn(List.of(identity));
        when(client.resolveWarehouses(identity)).thenReturn(List.of(12L, 13L));
        source.put("fromWarehouseId", 14);
        var page = json.createObjectNode(); page.putArray("content").add(source);
        when(client.listBy1c(identity, 0, 20)).thenReturn(page);
        assertThatThrownBy(() -> service.list(warehouse, 0, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(movements);
    }
    @Test void approvalRejectsAnIssueFromOutsideTheMatchedSourceWarehouses() {
        String identity = "00112233445566778899aabbccddeeff";
        when(jdbc.queryForList(contains("warehouse_source_master_links"), eq(String.class), eq("tenant"), eq(company), eq(warehouse)))
            .thenReturn(List.of(identity));
        when(client.resolveWarehouses(identity)).thenReturn(List.of(12L, 13L));
        var unrelated = source.deepCopy().put("fromWarehouseId", 14);
        when(client.detailBy1c(identity, 41)).thenReturn(unrelated);
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, "fingerprint")))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void ambiguousImportedWarehouseIdentityNeverReadsSource() {
        when(jdbc.queryForList(contains("warehouse_source_master_links"), eq(String.class), eq("tenant"), eq(company), eq(warehouse)))
            .thenReturn(List.of("111", "222"));
        assertThatThrownBy(() -> service.list(warehouse, 0, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }

    private void enableMxikMatch() {
        source.put("productNCode", "9999").put("mxikCode", "12345678901234567");
        var product = materials.findByIdAndIsDeletedFalse(material).orElseThrow();
        product.setName("PVX material"); product.setCode("3233"); product.setMxikCode("12345678901234567");
        when(jdbc.queryForList(contains("join stock_balances b"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq("12345678901234567"), eq("12345678901234567"))).thenReturn(List.of(material));
    }
    @Test void matchesMxikWithUserSelectedStockAndKeepsTheSourceQuantity() {
        enableMxikMatch();
        var matches = service.materialCandidates(warehouse, 41, listed().matchFingerprint());
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().match()).isEqualTo("MXIK");
        assertThat(matches.getFirst().quantityStock()).isEqualTo("125.5");
        service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, matches.getFirst().fingerprint(), material));
        var capture = ArgumentCaptor.forClass(StockMovementDocumentCreateRequest.class);
        verify(movements).createKimyosanoatIssue(capture.capture());
        assertThat(capture.getValue().getItems().getFirst().getMaterialId()).isEqualTo(material);
        assertThat(capture.getValue().getItems().getFirst().getQuantity()).isEqualByComparingTo("125.5");
    }
    @Test void selectedMaterialMustStillMatchAndHaveWarehouseStock() {
        enableMxikMatch();
        var candidate = service.materialCandidates(warehouse, 41, listed().matchFingerprint()).getFirst();
        when(jdbc.queryForList(contains("join stock_balances b"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq("12345678901234567"), eq("12345678901234567"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, candidate.fingerprint(), material)))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void changedMxikInvalidatesASelection() {
        enableMxikMatch();
        var candidate = service.materialCandidates(warehouse, 41, listed().matchFingerprint()).getFirst();
        source.put("mxikCode", "98765432109876543");
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, candidate.fingerprint(), material)))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void selectedMaterialNameChangeRequiresReviewAgain() {
        enableMxikMatch();
        var candidate = service.materialCandidates(warehouse, 41, listed().matchFingerprint()).getFirst();
        materials.findByIdAndIsDeletedFalse(material).orElseThrow().setName("Changed material");
        assertThatThrownBy(() -> service.approve(41, new KimyosanoatIssueService.ApproveRequest(warehouse, position, candidate.fingerprint(), material)))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void neverOffersCandidatesForUnweighedDocuments() {
        enableMxikMatch();
        source.remove("confirmedNettoKg");
        assertThatThrownBy(() -> service.materialCandidates(warehouse, 41, listed().matchFingerprint())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void changedMxikBeforeCandidateLookupRequiresRefresh() {
        enableMxikMatch();
        var document = listed();
        source.put("mxikCode", "98765432109876543");
        assertThatThrownBy(() -> service.materialCandidates(warehouse, 41, document.matchFingerprint()))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(movements);
    }
    @Test void candidateLookupRejectsWarehouseAccessBeforeSourceRead() {
        when(access.availabilityWarehouseId(warehouse)).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.materialCandidates(warehouse, 41, "fingerprint"))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(client);
    }
    @Test void recognizesASeventeenDigitMxikWhenOneLeadingZeroWasDropped() {
        enableMxikMatch();
        source.put("mxikCode", "2905001002000000");
        materials.findByIdAndIsDeletedFalse(material).orElseThrow().setMxikCode("02905001002000000");
        when(jdbc.queryForList(contains("join stock_balances b"), eq(UUID.class), eq("tenant"), eq(company),
            eq(warehouse), eq("2905001002000000"), eq("02905001002000000"))).thenReturn(List.of(material));
        var match = service.materialCandidates(warehouse, 41, listed().matchFingerprint()).getFirst();
        assertThat(match.match()).isEqualTo("MXIK_NORMALIZED");
        assertThat(match.mxikCode()).isEqualTo("02905001002000000");
    }
}
