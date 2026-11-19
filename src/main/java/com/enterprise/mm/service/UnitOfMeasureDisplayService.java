package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.UnitOfMeasureLabels;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Read-only display metadata. Never changes a document's unit identity or quantities. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnitOfMeasureDisplayService {
    private static final String CACHE = UnitOfMeasureDisplayService.class.getName();
    private final UnitOfMeasureRepository units;

    public String shortName(BusinessEntity owner, UUID unitId, String unitCode) {
        if (owner == null || owner.getTenantId() == null || owner.getCompanyId() == null) return null;
        return shortName(owner.getTenantId(), owner.getCompanyId(), unitId, unitCode);
    }

    public String shortName(BusinessEntity owner, String documentUnit, Material material) {
        if (owner == null || owner.getTenantId() == null || owner.getCompanyId() == null) return null;
        var unit = material == null ? null : material.getUnitOfMeasure();
        if (unit != null && !unit.isDeleted() && Objects.equals(owner.getTenantId(), unit.getTenantId())
            && Objects.equals(owner.getCompanyId(), unit.getCompanyId())
            && (documentUnit == null || documentUnit.isBlank() || matches(documentUnit, unit)))
            return label(unit);
        return shortName(owner, null, documentUnit);
    }

    public String shortName(String tenant, UUID company, UUID unitId, String unitCode) {
        if (tenant == null || company == null) return null;
        if (unitId == null) return unitCode == null || unitCode.isBlank() ? null
            : shortNames(tenant, company, List.of(unitCode)).get(unitCode);
        Key key = new Key(tenant, company, LocaleContextHolder.getLocale().toLanguageTag(), unitId.toString());
        Map<Key, Optional<String>> cache = cache();
        return cache.computeIfAbsent(key, ignored -> units.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenant, company, unitId)
            .map(this::label)).orElse(null);
    }

    /** Batch lookup for paginated rows and immutable snapshots; ambiguous codes have no guessed label. */
    public Map<String, String> shortNames(String tenant, UUID company, Collection<String> codes) {
        if (tenant == null || company == null || codes == null) return Map.of();
        var requested = codes.stream().filter(Objects::nonNull).filter(c -> !c.isBlank()).distinct().toList();
        Map<Key, Optional<String>> cache = cache();
        String language = LocaleContextHolder.getLocale().toLanguageTag();
        var missing = requested.stream().filter(c -> !cache.containsKey(new Key(tenant, company, language, "code:" + c))).toList();
        if (!missing.isEmpty()) {
            Map<String, Set<String>> labels = new HashMap<>();
            for (var unit : units.findByTenantIdAndCompanyIdAndCodeInAndIsDeletedFalse(tenant, company, missing)) {
                String label = label(unit);
                if (label != null) labels.computeIfAbsent(unit.getCode(), ignored -> new HashSet<>()).add(label);
            }
            for (String code : missing) {
                Set<String> found = labels.getOrDefault(code, Set.of());
                cache.put(new Key(tenant, company, language, "code:" + code),
                    found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty());
            }
        }
        Map<String, String> result = new HashMap<>();
        for (String code : requested) cache.get(new Key(tenant, company, language, "code:" + code))
            .ifPresent(label -> result.put(code, label));
        return result;
    }

    /** Add catalog labels to market-price output only; provider payloads and stored evidence stay unchanged. */
    public Object marketPriceResult(Object payload, String tenant, UUID company) {
        if (!(payload instanceof com.fasterxml.jackson.databind.node.ObjectNode original)) return payload;
        var result = original.deepCopy();
        List<com.fasterxml.jackson.databind.node.ObjectNode> rows = new ArrayList<>();
        rows.add(result);
        if (result.get("material") instanceof com.fasterxml.jackson.databind.node.ObjectNode material) rows.add(material);
        if (result.path("offers").isArray()) for (var offer : result.path("offers")) {
            if (rows.size() >= 501) break;
            if (offer instanceof com.fasterxml.jackson.databind.node.ObjectNode object) rows.add(object);
        }
        var codes = rows.stream().map(this::resultUnitCode).filter(Objects::nonNull).toList();
        var labels = shortNames(tenant, company, codes);
        for (var row : rows) {
            String code = resultUnitCode(row);
            if (code == null) continue;
            String label = labels.get(code);
            if (label == null) row.putNull("unitOfMeasureShortName");
            else row.put("unitOfMeasureShortName", label);
        }
        return result;
    }

    private String resultUnitCode(com.fasterxml.jackson.databind.node.ObjectNode row) {
        var code = row.has("unitOfMeasureCode") ? row.get("unitOfMeasureCode") : row.get("unit_of_measure_code");
        return code != null && code.isTextual() && !code.asText().isBlank() && code.asText().length() <= 240
            ? code.asText() : null;
    }

    private boolean matches(String value, UnitOfMeasure unit) {
        return java.util.stream.Stream.of(unit.getCode(), unit.getMeasurementNameShort(), unit.getMeasurementNameShortRu(),
            unit.getMeasurementNameShortUz(), unit.getMeasurementNameLong()).anyMatch(s -> s != null && s.equalsIgnoreCase(value.trim()));
    }

    private String label(UnitOfMeasure unit) {
        return UnitOfMeasureLabels.localizedShort(unit, LocaleContextHolder.getLocale());
    }

    @SuppressWarnings("unchecked")
    private Map<Key, Optional<String>> cache() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return new HashMap<>();
        var cache = (Map<Key, Optional<String>>) request.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST);
        if (cache == null) { cache = new HashMap<>(); request.setAttribute(CACHE, cache, RequestAttributes.SCOPE_REQUEST); }
        return cache;
    }
    private record Key(String tenant, UUID company, String language, String identity) {}
}
