package com.enterprise.mm.service;

import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;

/** Deliberately small physical-unit vocabulary; packaging and linear metres are never inferred. */
final class ContractMaterialLinkUnits {
    private ContractMaterialLinkUnits() {}
    private static final Map<String, Set<String>> ALIASES = Map.of(
        "piece", Set.of("шт", "штука", "штук", "piece", "pieces", "pc", "pcs", "ea", "dona", "дона"),
        "kg", Set.of("кг", "килограмм", "килограммы", "kg", "kilogram", "kilograms"),
        "tonne", Set.of("т", "тн", "тонна", "тонны", "t", "tonne", "tonnes", "tonna"),
        "litre", Set.of("л", "литр", "литры", "l", "litre", "liter", "litr"),
        "metre", Set.of("м", "метр", "метры", "m", "metre", "meter"),
        "m2", Set.of("мкв", "квм", "м2", "m2", "метрквадратный", "квадратныйметр", "squaremetre", "squaremeter"),
        "m3", Set.of("м3", "m3", "метркубический", "кубическийметр", "cubicmetre", "cubicmeter"));

    /** Contract quantity represented by one material stock unit. */
    static BigDecimal factor(UnitOfMeasure contract, UnitOfMeasure stock, JsonNode evidence) {
        requireSourceIdentity(contract, evidence, "sourceContractUnit");
        requireSourceIdentity(stock, evidence, "sourceStockUnit");
        if (contract.getId() != null && contract.getId().equals(stock.getId())) return BigDecimal.ONE;
        if (evidence != null && verifiedKit(contract, evidence.path("sourceContractUnit"))
            && verifiedKit(stock, evidence.path("sourceStockUnit"))) return BigDecimal.ONE;
        String left = physicalUnit(contract, evidence.path("sourceContractUnit")),
            right = physicalUnit(stock, evidence.path("sourceStockUnit"));
        if (left.equals(right)) return BigDecimal.ONE;
        if (left.equals("tonne") && right.equals("kg")) return new BigDecimal("0.001");
        if (left.equals("kg") && right.equals("tonne")) return new BigDecimal("1000");
        throw new BadRequestException("Contract and stock units have no verified exact conversion");
    }

    private static void requireSourceIdentity(UnitOfMeasure unit, JsonNode evidence, String field) {
        JsonNode source = evidence == null ? null : evidence.get(field);
        String sourceId = source == null ? "" : source.path("sourceId").asText();
        if (source == null || !source.isObject() || !source.path("sourceId").isTextual()
            || !sourceId.matches("[a-f0-9]{32}") || unit.getCode() == null
            || !("1C-UOM-" + sourceId).equalsIgnoreCase(unit.getCode()))
            throw new BadRequestException("Source unit identity does not match the imported target unit: " + field);
    }

    /** A kit is not a piece; duplicate kit cards need their own Catalog41 EDO-26 source identity. */
    private static boolean verifiedKit(UnitOfMeasure unit, JsonNode source) {
        Set<String> fullNames = Set.of("комплект", "komplekt");
        Set<String> shortNames = Set.of("комп", "комплект", "кт", "к-т", "к/т", "komp", "komplekt");
        if (!"26".equals(source.path("edoCode").asText())
            || !fullNames.contains(normalize(source.path("fullName").asText()))
            || !shortNames.contains(normalize(source.path("shortName").asText()))) return false;
        List<String> full = Stream.of(primaryName(unit.getMeasurementNameLong(), source, "fullName"), unit.getMeasurementNameLongRu(), unit.getMeasurementNameLongUz())
            .filter(value -> value != null && !value.isBlank()).map(ContractMaterialLinkUnits::normalize).toList();
        List<String> abbreviated = Stream.of(primaryName(unit.getMeasurementNameShort(), source, "shortName"), unit.getMeasurementNameShortRu(), unit.getMeasurementNameShortUz())
            .filter(value -> value != null && !value.isBlank()).map(ContractMaterialLinkUnits::normalize).toList();
        return !full.isEmpty() && full.stream().allMatch(fullNames::contains)
            && !abbreviated.isEmpty() && abbreviated.stream().allMatch(shortNames::contains);
    }

    private static String physicalUnit(UnitOfMeasure unit, JsonNode source) {
        Set<String> identified = new HashSet<>();
        List<String> names = Stream.of(primaryName(unit.getMeasurementNameShort(), source, "shortName"),
            primaryName(unit.getMeasurementNameLong(), source, "fullName"), unit.getMeasurementNameShortRu(),
            unit.getMeasurementNameLongRu(), unit.getMeasurementNameShortUz(), unit.getMeasurementNameLongUz())
            .filter(value -> value != null && !value.isBlank()).map(ContractMaterialLinkUnits::normalize).toList();
        for (String name : names) {
            String physical = ALIASES.entrySet().stream().filter(entry -> entry.getValue().contains(name))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new BadRequestException("Unknown physical unit name requires an identical unit ID"));
            identified.add(physical);
        }
        if (unit.getCode() != null) ALIASES.forEach((physical, aliases) -> {
            if (aliases.contains(normalize(unit.getCode()))) identified.add(physical);
        });
        if (identified.size() != 1) throw new BadRequestException("Unknown or contradictory physical unit names require an identical unit ID");
        return identified.iterator().next();
    }

    /** The importer decorates only primary names; source identity has already been checked. */
    private static String primaryName(String value, JsonNode source, String nameField) {
        JsonNode code = source.path("code"), name = source.path(nameField);
        if (value != null && code.isTextual() && !code.asText().isBlank() && name.isTextual() && !name.asText().isBlank()) {
            String original = name.asText().strip();
            if (value.equals(original + " [1C " + code.asText().strip() + "]")) return original;
        }
        return value;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s.]+", "");
    }

    static UnitSnapshot snapshot(UnitOfMeasure unit) {
        return new UnitSnapshot(unit.getId(), unit.getVersion() == null ? null : unit.getVersion().toString(), unit.getCode(), unit.getMeasurementNameShort(),
            unit.getMeasurementNameLong(), unit.getMeasurementNameShortRu(), unit.getMeasurementNameLongRu(),
            unit.getMeasurementNameShortUz(), unit.getMeasurementNameLongUz(), unit.getUnitType() == null ? null : unit.getUnitType().name(),
            unit.getStockQuantityScale());
    }
    record UnitSnapshot(UUID id, String version, String code, String shortName, String longName, String shortNameRu,
        String longNameRu, String shortNameUz, String longNameUz, String unitType, Integer stockQuantityScale) {}
}
