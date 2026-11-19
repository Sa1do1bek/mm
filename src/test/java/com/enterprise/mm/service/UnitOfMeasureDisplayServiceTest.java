package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.UnitOfMeasureLabels;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.mapper.*;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

class UnitOfMeasureDisplayServiceTest {
    UnitOfMeasureRepository repository = mock(UnitOfMeasureRepository.class);
    UnitOfMeasureDisplayService display = new UnitOfMeasureDisplayService(repository);
    UUID company = UUID.randomUUID();
    UnitOfMeasure pieces, boxes;
    Material material;

    @BeforeEach void setup() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        pieces = unit("1C-UOM-8115001a4bcf769a11dd6917e608c590", "шт.");
        boxes = unit("AMM-Reference61-b35400155d157a0111f0206c0ec0cb1b", "кор.");
        material = scoped(new Material()); material.setUnitOfMeasureId(pieces.getId()); material.setUnitOfMeasure(pieces);
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); LocaleContextHolder.resetLocaleContext(); }

    @Test void procurementMappersExposeShortNameWithoutReplacingDocumentUnit() {
        var pr = scoped(new PurchaseRequestItem()); pr.setMaterial(material); pr.setUnit(pieces.getCode());
        var po = scoped(new PurchaseOrderItem()); po.setMaterial(material); po.setUnit(pieces.getCode());
        var rfq = scoped(new RFQItem()); rfq.setMaterial(material); rfq.setUnit(pieces.getCode());
        var quote = scoped(new SupplierQuotationItem()); quote.setMaterial(material); quote.setUnit(pieces.getCode());
        var prResponse = new PurchaseRequestItemMapper(display).toResponse(pr);
        assertThat(prResponse.getUnit()).isEqualTo(pieces.getCode());
        assertThat(prResponse.getUnitShortName()).isEqualTo("шт.");
        assertThat(new PurchaseOrderItemMapper(display).toResponse(po).getUnitShortName()).isEqualTo("шт.");
        assertThat(new RFQItemMapper(display).toResponse(rfq).getUnitShortName()).isEqualTo("шт.");
        assertThat(new SupplierQuotationItemMapper(display).toResponse(quote).getUnitShortName()).isEqualTo("шт.");
        assertThat(new ObjectMapper().valueToTree(prResponse).path("unitShortName").asText()).isEqualTo("шт.");
        verifyNoInteractions(repository);
    }

    @Test void differentDocumentUnitDoesNotUseMaterialsStockUnit() {
        when(repository.findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(eq("unit-display"), eq(company), anyCollection()))
            .thenReturn(List.of(boxes));
        var po = scoped(new PurchaseOrderItem()); po.setMaterial(material); po.setUnit(boxes.getCode());
        assertThat(new PurchaseOrderItemMapper(display).toResponse(po).getUnitShortName()).isEqualTo("кор.");
        verify(repository).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse("unit-display", company, List.of(boxes.getCode()));
    }

    @Test void explicitContractUnitIdWinsAndMissingIdNeverFallsBackToDifferentCode() {
        when(repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("unit-display", company, boxes.getId()))
            .thenReturn(Optional.of(boxes));
        var spec = scoped(new ContractSpecification()); spec.setUnitOfMeasureId(boxes.getId()); spec.setUnit(pieces.getCode());
        assertThat(new ContractSpecificationMapper(display).toResponse(spec).getUnitShortName()).isEqualTo("кор.");
        assertThat(display.shortName(spec, UUID.randomUUID(), pieces.getCode())).isNull();
    }

    @Test void batchLookupAndRequestMemoizationKeepScopeAndLanguageSeparate() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(repository.findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(eq("unit-display"), eq(company), anyCollection()))
            .thenReturn(List.of(pieces, boxes));
        var labels = display.shortNames("unit-display", company, List.of(pieces.getCode(), boxes.getCode()));
        assertThat(labels).containsEntry(pieces.getCode(), "шт.").containsEntry(boxes.getCode(), "кор.");
        assertThat(display.shortName("unit-display", company, null, pieces.getCode())).isEqualTo("шт.");
        verify(repository, times(1)).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(eq("unit-display"), eq(company), anyCollection());
        UUID foreign = UUID.randomUUID();
        assertThat(display.shortName("unit-display", foreign, null, pieces.getCode())).isNull();
        verify(repository).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse("unit-display", foreign, List.of(pieces.getCode()));
        pieces.setMeasurementNameShortRu("ед."); LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        assertThat(display.shortName("unit-display", company, null, pieces.getCode())).isEqualTo("ед.");
        verify(repository, times(2)).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(eq("unit-display"), eq(company), anyCollection());
    }

    @Test void ambiguousOrMissingUnitsAreNullAndTechnicalIdsNeverBecomeLabels() {
        var conflict = unit(pieces.getCode(), "кг");
        when(repository.findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(anyString(), any(), anyCollection()))
            .thenReturn(List.of(pieces, conflict));
        assertThat(display.shortName("unit-display", company, null, pieces.getCode())).isNull();
        assertThat(display.shortName("unit-display", company, null, null)).isNull();
        assertThat(UnitOfMeasureLabels.shortLabel(pieces.getCode())).isNull();
        assertThat(UnitOfMeasureLabels.shortLabel(boxes.getCode())).isNull();
        pieces.setMeasurementNameShort(null); pieces.setName(pieces.getCode());
        assertThat(UnitOfMeasureLabels.localizedShort(pieces, Locale.ENGLISH)).isNull();
    }

    @Test void materialFromOtherCompanyCannotSupplyDisplayLabel() {
        pieces.setCompanyId(UUID.randomUUID());
        var owner = scoped(new PurchaseOrderItem());
        assertThat(display.shortName(owner, pieces.getCode(), material)).isNull();
        verify(repository).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse("unit-display", company, List.of(pieces.getCode()));
    }

    @Test void marketPriceResultGetsCatalogLabelsWithoutChangingStoredPayloadOrUnits() throws Exception {
        when(repository.findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(eq("unit-display"), eq(company), anyCollection()))
            .thenReturn(List.of(pieces, boxes));
        var mapper = new ObjectMapper();
        var input = mapper.createObjectNode(); input.put("unitOfMeasureCode", pieces.getCode()); input.put("quantity", "1000");
        input.putObject("material").put("unitOfMeasureCode", pieces.getCode());
        var offers = input.putArray("offers"); offers.addObject().put("unitOfMeasureCode", boxes.getCode());
        offers.addObject().put("unitOfMeasureCode", "UNKNOWN-UNIT").put("unitOfMeasureShortName", "untrusted label");
        var result = (com.fasterxml.jackson.databind.JsonNode) display.marketPriceResult(input, "unit-display", company);
        assertThat(result.path("unitOfMeasureShortName").asText()).isEqualTo("шт.");
        assertThat(result.path("material").path("unitOfMeasureShortName").asText()).isEqualTo("шт.");
        assertThat(result.path("offers").get(0).path("unitOfMeasureShortName").asText()).isEqualTo("кор.");
        assertThat(result.path("offers").get(1).path("unitOfMeasureShortName").isNull()).isTrue();
        assertThat(result.path("quantity").asText()).isEqualTo("1000");
        assertThat(input.has("unitOfMeasureShortName")).isFalse();
        verify(repository, times(1)).findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(anyString(), any(), anyCollection());
    }

    @Test void assetAcceptanceLineUsesItsDocumentUnitWithoutChangingQuantity() {
        var line = scoped(new AssetAcceptanceLine()); line.setMaterial(material); line.setUnit(pieces.getCode());
        line.setAcceptedQuantity(new java.math.BigDecimal("2"));
        var response = new AssetFoundationMapper(display).toResponse(line, List.of());
        assertThat(response.getUnit()).isEqualTo(pieces.getCode());
        assertThat(response.getUnitShortName()).isEqualTo("шт.");
        assertThat(response.getAcceptedQuantity()).isEqualByComparingTo("2");
    }

    @Test void mixedReceiptQueueKeepsBothDisplayUnitsWhenCopied() {
        var mapper = new ObjectMapper();
        var queue = mapper.convertValue(Map.of("unit", pieces.getCode(), "openQuantityUom", boxes.getCode()),
            com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueItem.class)
            .withOpenQuantityUomShortName("кор.").withUnitShortName("шт.");
        assertThat(queue.unitShortName()).isEqualTo("шт.");
        assertThat(queue.openQuantityUomShortName()).isEqualTo("кор.");
        assertThat(queue.openQuantityUom()).isEqualTo(boxes.getCode());
        var json = mapper.valueToTree(queue);
        assertThat(json.path("openQuantityUomShortName").asText()).isEqualTo("кор.");
    }

    @Test void existingStockLabelsGetExplicitAliasAndImportedCodesAreNeverAliases() {
        var mapper = new ObjectMapper();
        var response = new com.enterprise.mm.payload.StockBalanceResponse();
        response.setUnitCode(pieces.getCode()); response.setUnitLabel("шт.");
        assertThat(mapper.valueToTree(response).path("unitShortName").asText()).isEqualTo("шт.");
        response.setUnitLabel(pieces.getCode());
        assertThat(response.getUnitShortName()).isNull();
    }

    @Test void computedAliasesDoNotBreakStoredIdempotentResponseDeserialization() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var reservation = new com.enterprise.mm.payload.MaterialReservationResponse();
        reservation.setUnitOfMeasure("шт."); reservation.setQuantity(new java.math.BigDecimal("3"));
        String stored = mapper.writeValueAsString(reservation);
        assertThat(stored).contains("unitOfMeasureShortName");
        var restored = mapper.readValue(stored, com.enterprise.mm.payload.MaterialReservationResponse.class);
        assertThat(restored.getUnitOfMeasureShortName()).isEqualTo("шт.");
        assertThat(restored.getQuantity()).isEqualByComparingTo("3");
        var stock = new com.enterprise.mm.payload.StockBalanceResponse(); stock.setUnitLabel("шт.");
        assertThat(mapper.readValue(mapper.writeValueAsString(stock), com.enterprise.mm.payload.StockBalanceResponse.class)
            .getUnitShortName()).isEqualTo("шт.");
    }

    UnitOfMeasure unit(String code, String label) {
        var unit = scoped(new UnitOfMeasure()); unit.setCode(code); unit.setName(label); unit.setMeasurementNameShort(label); return unit;
    }
    <T extends com.enterprise.mm.common.BusinessEntity> T scoped(T entity) {
        entity.setId(UUID.randomUUID()); entity.setTenantId("unit-display"); entity.setCompanyId(company); entity.setStatus("ACTIVE"); return entity;
    }
}
