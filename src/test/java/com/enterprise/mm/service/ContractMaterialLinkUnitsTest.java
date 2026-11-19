package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BadRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractMaterialLinkUnitsTest {
    @Test void importedPieceDisambiguatorMatchesItsSourceUnitCode() throws Exception {
        var fixture = fixture();
        var pair = fixture.path("pairs").get(0);
        var contract = fixtureUnit(fixture, pair.path("contractUnitId").asText());
        var stock = fixtureUnit(fixture, pair.path("stockUnitId").asText());
        assertThat(contract.path("shortName").asText()).isEqualTo("шт [1C 174]");
        assertThat(contract.path("longName").asText()).isEqualTo("Штука [1C 174]");
        assertThat(actualFactor(contract, stock)).isEqualByComparingTo("1");
    }

    @Test void frozenUnitPairsCoverEveryPreparedLink() throws Exception {
        var fixture = fixture();
        assertThat(fixture.path("units").size()).isEqualTo(32);
        assertThat(fixture.path("pairs").size()).isEqualTo(51);
        int links = 0;
        for (var pair : fixture.path("pairs")) {
            assertThat(actualFactor(fixtureUnit(fixture, pair.path("contractUnitId").asText()),
                fixtureUnit(fixture, pair.path("stockUnitId").asText())))
                .as("prepared unit pair %s", pair).isEqualByComparingTo(pair.path("factor").asText());
            links += pair.path("occurrences").asInt();
        }
        assertThat(links).isEqualTo(604);
    }

    @Test void disambiguatorsCannotHideWrongEvidenceNamesOrUnits() throws Exception {
        var fixture = fixture(); var pair = fixture.path("pairs").get(0);
        var original = fixtureUnit(fixture, pair.path("contractUnitId").asText());
        var stock = fixtureUnit(fixture, pair.path("stockUnitId").asText());
        for (String bad : new String[]{"шт [1C 175]", "шт [1c 174]", "шт [1C 174] extra", "шт [174]", "шт [1C 174] [1C 174]", "кг [1C 174]"}) {
            var changed = original.deepCopy(); changed.put("shortName", bad);
            assertThatThrownBy(() -> actualFactor(changed, stock)).as(bad).isInstanceOf(BadRequestException.class);
        }
        for (String field : new String[]{"code", "sourceId", "shortName", "fullName"}) {
            var changed = original.deepCopy(); changed.withObject("sourceUnit").put(field, "wrong");
            assertThatThrownBy(() -> actualFactor(changed, stock)).as(field).isInstanceOf(BadRequestException.class);
        }
        var missingCode = original.deepCopy(); missingCode.withObject("sourceUnit").remove("code");
        assertThatThrownBy(() -> actualFactor(missingCode, stock)).isInstanceOf(BadRequestException.class);
        var contradictory = original.deepCopy(); contradictory.put("longNameRu", "килограмм");
        assertThatThrownBy(() -> actualFactor(contradictory, stock)).isInstanceOf(BadRequestException.class);
    }

    @Test void decoratedKitUnitsStillRequireKitNamesAndEdo26() throws Exception {
        var fixture = fixture();
        var first = fixtureUnitBySourceCode(fixture, "107");
        for (String stockCode : new String[]{"50", "87"}) {
            var stock = fixtureUnitBySourceCode(fixture, stockCode);
            assertThat(actualFactor(first, stock)).isEqualByComparingTo("1");
            var badEdo = stock.deepCopy(); badEdo.withObject("sourceUnit").put("edoCode", "1");
            assertThatThrownBy(() -> actualFactor(first, badEdo)).isInstanceOf(BadRequestException.class);
            var badSuffix = first.deepCopy(); badSuffix.put("longName", "комплект [1C 50]");
            assertThatThrownBy(() -> actualFactor(badSuffix, stock)).isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> actualFactor(first, fixtureUnitBySourceCode(fixture, "174")))
            .isInstanceOf(BadRequestException.class);
    }

    @Test void exactPhysicalAliasesAndMassFactorsAreAccepted() {
        for (String label : new String[]{"шт", "шт.", "ШТ", "Штука"})
            assertThat(factor(unit(label), unit("шт"))).isEqualByComparingTo("1");
        assertThat(factor(unit("тонна"), unit("КГ"))).isEqualByComparingTo("0.001");
        assertThat(factor(unit("килограмм"), unit("ТН"))).isEqualByComparingTo("1000");
        for (String[] pair : new String[][]{{"Л", "литр"}, {"М КВ", "метр квадратный"}, {"М", "метр"}, {"М3", "кубический метр"}})
            assertThat(factor(unit(pair[0]), unit(pair[1]))).isEqualByComparingTo("1");
    }
    @Test void unknownOrConflictingPhysicalUnitsFailClosed() {
        for (String[] pair : new String[][]{{"КОМП", "шт"}, {"К-Т", "шт"}, {"м", "пог.м"}, {"КГ", "л"}, {"box", "box"}})
            assertThatThrownBy(() -> factor(unit(pair[0]), unit(pair[1]))).isInstanceOf(BadRequestException.class);
        UnitOfMeasure contradictory = unit("кг"); contradictory.setMeasurementNameLongRu("тонна");
        assertThatThrownBy(() -> factor(contradictory, unit("кг"))).isInstanceOf(BadRequestException.class);
        UnitOfMeasure same = unit("box");
        assertThat(factor(same, same)).isEqualByComparingTo("1");
    }
    @Test void snapshotSurvivesJsonStorageWithoutNumericTypeDrift() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        UnitOfMeasure unit = unit("кг"); unit.setVersion(0L);
        var snapshot = mapper.valueToTree(ContractMaterialLinkUnits.snapshot(unit));
        assertThat(mapper.readTree(mapper.writeValueAsString(snapshot))).isEqualTo(snapshot);
    }
    @Test void packagingAndLinearMetreLabelsCannotBorrowARecognizedCode() {
        UnitOfMeasure kit = unit("КОМП"); kit.setCode("EA");
        UnitOfMeasure linear = unit("пог.м"); linear.setCode("M");
        assertThatThrownBy(() -> factor(unit("шт"), kit)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> factor(unit("м"), linear)).isInstanceOf(BadRequestException.class);
    }
    @Test void sourceUnitIdsAreRequiredForConvertedAndIdenticalUnitLinks() {
        var stock = unit("кг"); var contract = unit("тонна");
        for (UnitOfMeasure target : new UnitOfMeasure[]{contract, stock}) {
            assertThatThrownBy(() -> ContractMaterialLinkUnits.factor(target, stock, null)).isInstanceOf(BadRequestException.class);
            var evidence = evidence(target, stock); evidence.withObject("sourceStockUnit").put("sourceId", "f".repeat(32));
            assertThatThrownBy(() -> ContractMaterialLinkUnits.factor(target, stock, evidence)).isInstanceOf(BadRequestException.class);
        }
    }
    private java.math.BigDecimal factor(UnitOfMeasure contract, UnitOfMeasure stock) {
        return ContractMaterialLinkUnits.factor(contract, stock, evidence(contract, stock));
    }
    private com.fasterxml.jackson.databind.JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/contract-material-unit-pairs.json")) {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
        }
    }
    private com.fasterxml.jackson.databind.node.ObjectNode fixtureUnit(com.fasterxml.jackson.databind.JsonNode fixture, String id) {
        for (var unit : fixture.path("units")) if (id.equals(unit.path("id").asText()))
            return (com.fasterxml.jackson.databind.node.ObjectNode) unit;
        throw new AssertionError("Missing fixture unit: " + id);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode fixtureUnitBySourceCode(com.fasterxml.jackson.databind.JsonNode fixture, String code) {
        for (var unit : fixture.path("units")) if (code.equals(unit.path("sourceUnit").path("code").asText()))
            return (com.fasterxml.jackson.databind.node.ObjectNode) unit;
        throw new AssertionError("Missing fixture source code: " + code);
    }
    private java.math.BigDecimal actualFactor(com.fasterxml.jackson.databind.JsonNode contract, com.fasterxml.jackson.databind.JsonNode stock) {
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        evidence.set("sourceContractUnit", contract.path("sourceUnit")); evidence.set("sourceStockUnit", stock.path("sourceUnit"));
        return ContractMaterialLinkUnits.factor(actualUnit(contract), actualUnit(stock), evidence);
    }
    private UnitOfMeasure actualUnit(com.fasterxml.jackson.databind.JsonNode data) {
        var unit = new UnitOfMeasure(); unit.setId(UUID.fromString(data.path("id").asText())); unit.setCode(data.path("code").asText());
        unit.setMeasurementNameShort(data.path("shortName").asText(null)); unit.setMeasurementNameLong(data.path("longName").asText(null));
        unit.setMeasurementNameShortRu(data.path("shortNameRu").asText(null)); unit.setMeasurementNameLongRu(data.path("longNameRu").asText(null));
        unit.setMeasurementNameShortUz(data.path("shortNameUz").asText(null)); unit.setMeasurementNameLongUz(data.path("longNameUz").asText(null));
        unit.setVersion(Long.valueOf(data.path("version").asText())); unit.setStockQuantityScale(data.path("stockQuantityScale").asInt());
        unit.setUnitType(com.enterprise.mm.enums.MeasurementUnitType.valueOf(data.path("unitType").asText()));
        return unit;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode evidence(UnitOfMeasure contract, UnitOfMeasure stock) {
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        evidence.putObject("sourceContractUnit").put("sourceId", contract.getId().toString().replace("-", ""));
        evidence.putObject("sourceStockUnit").put("sourceId", stock.getId().toString().replace("-", ""));
        return evidence;
    }
    private UnitOfMeasure unit(String name) {
        UnitOfMeasure unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setMeasurementNameShort(name);
        unit.setCode("1C-UOM-" + unit.getId().toString().replace("-", "")); return unit;
    }
}
