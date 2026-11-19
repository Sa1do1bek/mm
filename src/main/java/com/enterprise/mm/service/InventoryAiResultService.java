package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.StorageBin;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAiResultService {
    private static final List<String> TEXT_FIELDS = List.of("name", "warehouseName", "commissionName",
        "storageLocationName", "storageBinName", "description");
    private static final Set<String> TYPES = Set.of("PLANNED", "UNSCHEDULED", "SAMPLE", "CYCLE", "EVENT");
    private static final Set<String> STATUSES = Set.of("EXTRACTED", "NEEDS_REVIEW", "NO_EXTRACTABLE_CONTENT");
    private static final Set<String> ALL_FIELDS = Set.of("name", "inventoryType", "globalWarehouse",
        "warehouseName", "commissionName", "storageLocationName", "storageBinName",
        "plannedStartDate", "plannedEndDate", "description");
    private final ObjectMapper mapper;
    private final WarehouseRepository warehouses;
    private final CommissionRepository commissions;
    private final StorageLocationRepository locations;
    private final StorageBinRepository bins;
    private final RoleDataScopeService scope;
    private final InventoryCommissionExtractionValidator commissionValidator;
    private final InventoryOrderExtractionValidator orderValidator;
    private final InventoryOrderScopedResultValidator scopedOrderValidator;

    public JsonNode validate(JsonNode response, UUID jobId, AnalysisRequest request) {
        if (request.orderAnalysisVersion() == 2) {
            scopedOrderValidator.validate(response, jobId, request);
            return response.deepCopy();
        }
        if (response == null || !response.isObject() || response.toString().length() > InventoryAiResponseLimits.expanded(4_000_000)
            || !jobId.toString().equals(response.path("analysisId").asText())
            || !STATUSES.contains(response.path("status").asText()) || !response.path("fields").isObject()) {
            throw invalid();
        }
        ObjectNode result = response.deepCopy();
        JsonNode fields = result.path("fields");
        fields.fieldNames().forEachRemaining(name -> { if (!ALL_FIELDS.contains(name)) throw invalid(); });
        JsonNode model = result.path("model");
        if (!model.isMissingNode() && !model.isNull() && (!model.isTextual() || model.asText().length() > InventoryAiResponseLimits.expanded(120))) throw invalid();
        for (String field : TEXT_FIELDS) validateField(fields.path(field), request, false, field.equals("description") ? 2000 : 240);
        validateField(fields.path("inventoryType"), request, false, 40);
        validateField(fields.path("globalWarehouse"), request, true, 0);
        validateField(fields.path("plannedStartDate"), request, false, 10);
        validateField(fields.path("plannedEndDate"), request, false, 10);
        String type = value(fields, "inventoryType");
        if (type != null && !TYPES.contains(type)) throw invalid();
        LocalDate start = date(value(fields, "plannedStartDate"));
        LocalDate end = date(value(fields, "plannedEndDate"));
        if (start != null && end != null && end.isBefore(start)) throw invalid();
        JsonNode facts = result.path("documentFacts");
        if (!facts.isMissingNode() && !facts.isNull()) {
            if (!facts.isObject()) throw invalid();
            JsonNode orderNumber = facts.path("orderNumber");
            if (!orderNumber.isMissingNode() && !orderNumber.isNull()
                && (!orderNumber.isTextual() || orderNumber.asText().length() > InventoryAiResponseLimits.expanded(80))) throw invalid();
            date(optionalText(facts, "orderDate"));
            date(optionalText(facts, "stockAsOfDate"));
        }
        JsonNode aiWarnings = result.path("warnings");
        if (!aiWarnings.isMissingNode()) {
            if (!aiWarnings.isArray())
                throw new IllegalArgumentException("MM AI inventory extraction warnings must be an array");
            if (aiWarnings.size() > InventoryAiResponseLimits.expanded(20))
                throw new IllegalArgumentException("MM AI inventory extraction warnings exceed the 60-item limit");
        }
        if (aiWarnings.isArray()) for (JsonNode warning : aiWarnings) {
            if (!warning.isTextual() || warning.asText().length() > InventoryAiResponseLimits.expanded(500)) throw invalid();
        }
        JsonNode commissionAnalysis = result.path("commissionAnalysis");
        if (request.commissionAnalysisVersion() == 1 && !commissionAnalysis.isObject()) throw invalid();
        if (!commissionAnalysis.isMissingNode() && !commissionAnalysis.isNull())
            commissionValidator.validate(commissionAnalysis, request);
        JsonNode orderAnalysis = result.path("orderAnalysis");
        if (request.orderAnalysisVersion() == 1) {
            if (!orderAnalysis.isObject() || !commissionAnalysis.isObject()) throw invalid();
            orderValidator.validate(orderAnalysis, commissionAnalysis, request);
        }
        boolean hasSuggestion = java.util.stream.Stream.concat(TEXT_FIELDS.stream(),
            java.util.stream.Stream.of("inventoryType", "globalWarehouse", "plannedStartDate", "plannedEndDate"))
            .anyMatch(name -> !fields.path(name).isNull() && !fields.path(name).isMissingNode());
        if ("NO_EXTRACTABLE_CONTENT".equals(result.path("status").asText()) && hasSuggestion) throw invalid();
        if ("EXTRACTED".equals(result.path("status").asText()) && !hasSuggestion) throw invalid();
        return result;
    }

    public JsonNode resolve(JsonNode validated) {
        if (validated == null || !validated.isObject()) return validated;
        ObjectNode result = validated.deepCopy();
        JsonNode fields = result.path("fields");
        JsonNode aiWarnings = result.path("warnings");
        ArrayNode warnings = aiWarnings.isArray() ? (ArrayNode) aiWarnings : mapper.createArrayNode();
        ObjectNode ids = mapper.createObjectNode();
        UUID warehouseId = fields.path("globalWarehouse").path("value").asBoolean(false)
            ? null : resolveWarehouse(value(fields, "warehouseName"));
        if (warehouseId != null) ids.put("warehouseId", warehouseId.toString());
        else if (value(fields, "warehouseName") != null) warnings.add("Warehouse name was not matched uniquely in the authorized company scope.");
        UUID commissionId = resolveCommission(value(fields, "commissionName"));
        if (commissionId != null) ids.put("commissionId", commissionId.toString());
        else if (value(fields, "commissionName") != null) warnings.add("Commission name was not matched uniquely to an active inventory commission.");
        if (warehouseId != null) {
            UUID locationId = unique(locations.findByTenantIdAndCompanyIdAndWarehouseIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), warehouseId).stream()
                .filter(location -> "ACTIVE".equals(location.getStatus())).toList(),
                value(fields, "storageLocationName"), StorageLocation::getName, StorageLocation::getCode, StorageLocation::getId);
            if (locationId != null) ids.put("storageLocationId", locationId.toString());
            else if (value(fields, "storageLocationName") != null) warnings.add("Storage location was not matched uniquely.");
            if (locationId != null) {
                UUID binId = unique(bins.findByTenantIdAndStorageLocationIdAndIsDeletedFalse(TenantContext.currentTenant(), locationId).stream()
                    .filter(bin -> CompanyContext.currentCompany().equals(bin.getCompanyId()) && "ACTIVE".equals(bin.getStatus())).toList(),
                    value(fields, "storageBinName"), StorageBin::getName, StorageBin::getCode, StorageBin::getId);
                if (binId != null) ids.put("storageBinId", binId.toString());
                else if (value(fields, "storageBinName") != null) warnings.add("Storage bin was not matched uniquely.");
            }
        }
        result.set("resolvedIds", ids);
        result.set("warnings", warnings);
        return result;
    }

    private void validateField(JsonNode field, AnalysisRequest request, boolean booleanValue, int maxLength) {
        if (field.isMissingNode() || field.isNull()) return;
        JsonNode value = field.path("value");
        if (!field.isObject() || (booleanValue ? !value.isBoolean() : !value.isTextual() || value.asText().isBlank()
            || value.asText().length() > InventoryAiResponseLimits.expanded(maxLength))) throw invalid();
        JsonNode confidence = field.path("confidence");
        if (!confidence.isNumber() || confidence.asDouble() < 0 || confidence.asDouble() > 1) throw invalid();
        JsonNode sources = field.path("sources");
        if (!sources.isArray() || sources.isEmpty() || sources.size() > InventoryAiResponseLimits.expanded(10)) throw invalid();
        for (JsonNode source : sources) {
            if (!source.isObject() || !source.path("page").isIntegralNumber() || source.path("page").asInt() < 1
                || !source.path("quote").isTextual() || source.path("quote").asText().isBlank()
                || source.path("quote").asText().length() > InventoryAiResponseLimits.expanded(400)
                || request.documents().stream().noneMatch(file -> file.fileId().toString().equals(source.path("fileId").asText()))) {
                throw invalid();
            }
        }
    }

    private UUID resolveWarehouse(String name) {
        if (name == null) return null;
        return unique(warehouses.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(warehouse -> "ACTIVE".equals(warehouse.getStatus()) && scope.canAccessWarehouse(warehouse.getId())).toList(),
            name, Warehouse::getName, Warehouse::getCode, Warehouse::getId);
    }

    private UUID resolveCommission(String name) {
        if (name == null) return null;
        LocalDate today = LocalDate.now();
        return unique(commissions.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(commission -> commission.getType() == CommissionType.INVENTORY && commission.isActive()
                && (commission.getValidFrom() == null || !commission.getValidFrom().isAfter(today))
                && (commission.getValidTo() == null || !commission.getValidTo().isBefore(today))).toList(),
            name, Commission::getName, Commission::getCode, Commission::getId);
    }

    private <T> UUID unique(List<T> records, String text, Function<T, String> name,
        Function<T, String> code, Function<T, UUID> id) {
        if (text == null) return null;
        List<T> matches = records.stream().filter(record -> same(text, name.apply(record)) || same(text, code.apply(record))).toList();
        return matches.size() == 1 ? id.apply(matches.get(0)) : null;
    }

    private boolean same(String left, String right) {
        return right != null && left.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
            .equals(right.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    private String value(JsonNode fields, String name) {
        JsonNode value = fields.path(name).path("value");
        return value.isTextual() ? value.asText() : null;
    }

    private String optionalText(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText();
    }

    private LocalDate date(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid(); }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("MM AI inventory extraction response is invalid");
    }
}
