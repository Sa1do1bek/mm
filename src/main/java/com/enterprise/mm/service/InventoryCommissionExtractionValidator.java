package com.enterprise.mm.service;

import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates the versioned extraction block before it is stored and shown to a planner. */
@Component
public class InventoryCommissionExtractionValidator {
    private static final Set<String> KINDS = Set.of("CENTRAL", "LOCAL", "SPECIALIZED", "REGIONAL", "UNKNOWN");
    private static final Set<String> MENTION_KINDS = Set.of("DEPARTMENT", "ORG_UNIT", "WAREHOUSE");
    private static final Set<String> MODES = Set.of("DEPARTMENTS", "WAREHOUSES");
    private static final Set<String> COMPLETENESS = Set.of("COMPLETE", "PARTIAL", "UNKNOWN");
    private static final Set<String> ROLES = Set.of("CHAIRMAN", "MEMBER", "COUNTER", "CHECKER", "ACCOUNTANT",
        "STOREKEEPER", "SECRETARY", "AUDITOR");

    public void validate(JsonNode block, AnalysisRequest request) {
        if (!block.isObject() || !block.path("schemaVersion").isIntegralNumber()
            || block.path("schemaVersion").asInt() != 1) invalid();
        JsonNode suggested = block.path("suggestedScope");
        if (!suggested.isObject() || !suggested.path("reviewRequired").isBoolean()) invalid();
        JsonNode mode = suggested.path("mode");
        if (!mode.isNull() && (!mode.isTextual() || !MODES.contains(mode.asText()))) invalid();
        names(suggested.path("names"), 100);
        if (mode.isNull() && !suggested.path("names").isEmpty()) invalid();
        if (!mode.isNull() && suggested.path("names").isEmpty()) invalid();
        mentions(block.path("scopeMentions"), request, 500);
        JsonNode commissions = block.path("commissions");
        if (!commissions.isArray() || commissions.size() > InventoryAiResponseLimits.expanded(300)) invalid();
        Set<String> keys = new HashSet<>();
        int memberCount = 0;
        for (JsonNode commission : commissions) {
            if (!commission.isObject()) invalid();
            String sourceKey = required(commission.path("sourceKey"), 160);
            if (!keys.add(sourceKey) || !KINDS.contains(required(commission.path("kind"), 30))) invalid();
            required(commission.path("name"), 240);
            if (!Set.of("DOCUMENT", "DERIVED_LABEL").contains(required(commission.path("nameOrigin"), 30))) invalid();
            JsonNode appointment = commission.path("appointmentNumber");
            if (!appointment.isMissingNode() && !appointment.isNull()
                && (!appointment.isIntegralNumber() || appointment.asInt() < 1)) invalid();
            JsonNode scope = commission.path("sourceScope");
            if (!scope.isObject()) invalid();
            names(scope.path("departmentNames"), 100);
            names(scope.path("orgUnitNames"), 100);
            names(scope.path("warehouseNames"), 100);
            mentions(commission.path("scopeMentions"), request, 100);
            if (!COMPLETENESS.contains(required(commission.path("rosterCompleteness"), 20))) invalid();
            citations(commission.path("sources"), request, 20, true);
            citations(commission.path("completenessSources"), request, 20, false);
            date(commission.path("validFrom"));
            date(commission.path("validTo"));
            if (commission.path("validFrom").isTextual() && commission.path("validTo").isTextual()
                && LocalDate.parse(commission.path("validTo").asText()).isBefore(LocalDate.parse(commission.path("validFrom").asText()))) invalid();
            warnings(commission.path("warnings"), 30);
            JsonNode members = commission.path("members");
            if (!members.isArray() || members.size() > InventoryAiResponseLimits.expanded(100)) invalid();
            memberCount += members.size();
            if (memberCount > InventoryAiResponseLimits.expanded(1000)) invalid();
            for (JsonNode member : members) {
                if (!member.isObject()) invalid();
                required(member.path("fullName"), 240);
                optional(member.path("position"), 240);
                role(member.path("documentRole"));
                role(member.path("erpRoleSuggestion"));
                citations(member.path("sources"), request, 10, true);
                citations(member.path("roleSources"), request, 10, false);
            }
        }
        JsonNode recommended = block.path("recommendedSourceKeys");
        if (!recommended.isArray() || recommended.size() > commissions.size()) invalid();
        for (JsonNode key : recommended) if (!keys.contains(required(key, 160))) invalid();
        warnings(block.path("warnings"), 100);
    }

    private void mentions(JsonNode mentions, AnalysisRequest request, int max) {
        if (!mentions.isArray() || mentions.size() > InventoryAiResponseLimits.expanded(max)) invalid();
        for (JsonNode mention : mentions) {
            if (!mention.isObject() || !MENTION_KINDS.contains(required(mention.path("kind"), 30))) invalid();
            required(mention.path("name"), 240);
            citations(mention.path("sources"), request, 10, true);
        }
    }

    private void citations(JsonNode sources, AnalysisRequest request, int max, boolean required) {
        if (!sources.isArray() || sources.size() > InventoryAiResponseLimits.expanded(max) || required && sources.isEmpty()) invalid();
        for (JsonNode source : sources) {
            if (!source.isObject() || !source.path("page").isIntegralNumber() || source.path("page").asInt() < 1
                || request.documents().stream().noneMatch(file -> file.fileId().toString().equals(source.path("fileId").asText()))) invalid();
            required(source.path("quote"), 500);
        }
    }

    private void names(JsonNode names, int max) {
        if (!names.isArray() || names.size() > InventoryAiResponseLimits.expanded(max)) invalid();
        for (JsonNode name : names) required(name, 240);
    }

    private void warnings(JsonNode warnings, int max) {
        if (!warnings.isArray() || warnings.size() > InventoryAiResponseLimits.expanded(max)) invalid();
        for (JsonNode warning : warnings) required(warning, 500);
    }

    private void role(JsonNode role) {
        if (!role.isNull() && (!role.isTextual() || !ROLES.contains(role.asText()))) invalid();
    }

    private void date(JsonNode date) {
        if (date.isMissingNode() || date.isNull()) return;
        if (!date.isTextual()) invalid();
        try { LocalDate.parse(date.asText()); }
        catch (DateTimeParseException ex) { invalid(); }
    }

    private void optional(JsonNode value, int max) {
        if (!value.isMissingNode() && !value.isNull()) required(value, max);
    }

    private String required(JsonNode value, int max) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > InventoryAiResponseLimits.expanded(max)) invalid();
        return value.asText();
    }

    private void invalid() { throw new IllegalArgumentException("MM AI commission extraction response is invalid"); }
}
