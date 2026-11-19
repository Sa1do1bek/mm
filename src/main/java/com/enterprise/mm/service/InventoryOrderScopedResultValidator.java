package com.enterprise.mm.service;

import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates the published scoped v2 contract before an AI result is saved. */
@Component
public class InventoryOrderScopedResultValidator {
    private static final Set<String> HEADER = Set.of("orderTitle", "orderNumber", "orderDate",
        "stockAsOfDate", "plannedStartDate", "plannedEndDate", "accountingSubmissionDeadline");
    private static final Set<String> TASKS = Set.of("STOCK_COUNT", "FIXED_ASSET_CHECK", "FINANCIAL_CHECK",
        "CONSTRUCTION_CHECK", "UNKNOWN");
    private static final Set<String> COMPLETENESS = Set.of("COMPLETE", "PARTIAL", "UNKNOWN");

    public void validate(JsonNode response, UUID jobId, AnalysisRequest request) {
        if (request.scope() == null) fail("request.scope", "required");
        object(response, "$", Set.of("analysisId", "status", "model", "scope", "orderAnalysis", "warnings"));
        if (!jobId.toString().equals(response.path("analysisId").asText())) fail("analysisId", "request ID");
        if (response.toString().getBytes(StandardCharsets.UTF_8).length > InventoryAiResponseLimits.expanded(2_097_152))
            fail("$", "response within size limit");
        choice(response.path("status"), "status", Set.of("EXTRACTED", "NEEDS_REVIEW", "NO_EXTRACTABLE_CONTENT"));
        text(response.path("model"), "model", 2000);
        warnings(response.path("warnings"), "warnings", 20);

        JsonNode scope = response.path("scope");
        object(scope, "scope", Set.of("mode", "departmentIds", "scopeResolution", "targetResolutions"));
        if (!request.scope().mode().equals(scope.path("mode").asText())) fail("scope.mode", "requested mode");
        choice(scope.path("scopeResolution"), "scope.scopeResolution", Set.of("MATCHED", "AMBIGUOUS", "NOT_FOUND"));
        Set<String> allowed = new HashSet<>();
        request.scope().departmentIds().forEach(id -> allowed.add(id.toString()));
        Set<String> returned = ids(scope.path("departmentIds"), "scope.departmentIds", 100, allowed, false);
        if (!returned.equals(allowed)) fail("scope.departmentIds", "requested departments");
        JsonNode resolutions = array(scope.path("targetResolutions"), "scope.targetResolutions", 100, true);
        Set<String> resolved = new HashSet<>();
        for (int i = 0; i < resolutions.size(); i++) {
            String path = "scope.targetResolutions[" + i + "]";
            JsonNode item = resolutions.get(i);
            object(item, path, Set.of("departmentId", "resolution"));
            String id = item.path("departmentId").asText();
            if (!allowed.contains(id) || !resolved.add(id)) fail(path + ".departmentId", "unique requested department");
            choice(item.path("resolution"), path + ".resolution", Set.of("MATCHED", "AMBIGUOUS", "NOT_FOUND"));
        }
        if (resolutions.size() > 0 && !resolved.equals(allowed)) fail("scope.targetResolutions", "all requested departments");

        JsonNode order = response.path("orderAnalysis");
        object(order, "orderAnalysis", Set.of("schemaVersion", "header", "centralCommissionSourceKey",
            "chairAssignments", "workItems", "commissionRosters", "approvalDirections",
            "consentSignatories", "coverage", "warnings"));
        if (order.path("schemaVersion").asInt() != 2) fail("orderAnalysis.schemaVersion", "2");
        header(order.path("header"), request);
        warnings(order.path("warnings"), "orderAnalysis.warnings", 1000);

        Set<String> chairs = new HashSet<>(), rosters = new HashSet<>(), works = new HashSet<>();
        JsonNode assignments = array(order.path("chairAssignments"), "orderAnalysis.chairAssignments", 200, false);
        for (int i = 0; i < assignments.size(); i++) {
            String path = "orderAnalysis.chairAssignments[" + i + "]";
            JsonNode item = assignments.get(i);
            object(item, path, Set.of("sourceKey", "annexNumber", "itemNumber", "objectMention", "objectMentions",
                "matchedDepartmentId", "matchedDepartmentIds", "chairName", "chairPosition",
                "otherMembersRequireLocalOrder", "sources"));
            key(item, path, chairs);
            text(item.path("chairName"), path + ".chairName", 2000);
            if (!item.path("otherMembersRequireLocalOrder").asBoolean(false)) fail(path + ".otherMembersRequireLocalOrder", "true");
            objects(item.path("objectMentions"), path + ".objectMentions", request, true);
            objectMention(item.path("objectMention"), path + ".objectMention", request);
            departmentIds(item, path, request, allowed);
            if ("DEPARTMENTS".equals(request.scope().mode()) && !targetNameMatches(item, request))
                fail(path + ".objectMention.name", "authorized department name or alias");
            citations(item.path("sources"), path + ".sources", request, true);
        }
        JsonNode commissionRosters = array(order.path("commissionRosters"), "orderAnalysis.commissionRosters", 200, false);
        for (int i = 0; i < commissionRosters.size(); i++) {
            String path = "orderAnalysis.commissionRosters[" + i + "]";
            JsonNode item = commissionRosters.get(i);
            object(item, path, Set.of("sourceKey", "kind", "name", "nameOrigin", "objectMentions", "members",
                "rosterCompleteness", "createEligible", "sources", "matchedDepartmentIds",
                "completenessSources", "validFrom", "validTo", "warnings"));
            key(item, path, rosters);
            choice(item.path("kind"), path + ".kind", Set.of("CENTRAL", "LOCAL", "SPECIALIZED", "REGIONAL", "UNKNOWN"));
            choice(item.path("nameOrigin"), path + ".nameOrigin", Set.of("DOCUMENT", "DERIVED_LABEL"));
            text(item.path("name"), path + ".name", 2000);
            choice(item.path("rosterCompleteness"), path + ".rosterCompleteness", COMPLETENESS);
            objects(item.path("objectMentions"), path + ".objectMentions", request, false);
            ids(item.path("matchedDepartmentIds"), path + ".matchedDepartmentIds", 100, allowed, true);
            JsonNode members = array(item.path("members"), path + ".members", 100, false);
            for (int j = 0; j < members.size(); j++) {
                String memberPath = path + ".members[" + j + "]";
                JsonNode member = members.get(j);
                object(member, memberPath, Set.of("fullName", "position", "documentRole", "erpRoleSuggestion", "sources", "roleSources"));
                text(member.path("fullName"), memberPath + ".fullName", 2000);
                citations(member.path("sources"), memberPath + ".sources", request, true);
                citations(member.path("roleSources"), memberPath + ".roleSources", request, false);
            }
            if (!item.path("createEligible").isBoolean()) fail(path + ".createEligible", "boolean");
            if (item.path("createEligible").asBoolean() && (members.size() < 2
                || !"COMPLETE".equals(item.path("rosterCompleteness").asText())))
                fail(path + ".createEligible", "complete roster with at least two members");
            citations(item.path("sources"), path + ".sources", request, true);
            citations(item.path("completenessSources"), path + ".completenessSources", request, false);
            warnings(item.path("warnings"), path + ".warnings", 100);
        }
        JsonNode workItems = array(order.path("workItems"), "orderAnalysis.workItems", 1000, false);
        for (int i = 0; i < workItems.size(); i++) {
            String path = "orderAnalysis.workItems[" + i + "]";
            JsonNode item = workItems.get(i);
            object(item, path, Set.of("sourceKey", "annexNumber", "itemNumber", "kind", "objectMentions",
                "taskTypes", "chairAssignmentSourceKeys", "commissionRosterSourceKeys",
                "candidateChairAssignmentSourceKeys", "candidateCommissionRosterSourceKeys",
                "matchedDepartmentIds", "rosterCompleteness", "sources", "warnings"));
            key(item, path, works);
            choice(item.path("kind"), path + ".kind", Set.of("LOCAL", "REGIONAL", "FINANCIAL", "CONSTRUCTION", "OTHER"));
            choice(item.path("rosterCompleteness"), path + ".rosterCompleteness", COMPLETENESS);
            objects(item.path("objectMentions"), path + ".objectMentions", request, false);
            JsonNode tasks = array(item.path("taskTypes"), path + ".taskTypes", 5, false);
            if (tasks.isEmpty()) fail(path + ".taskTypes", "nonempty array");
            tasks.forEach(type -> choice(type, path + ".taskTypes", TASKS));
            ids(item.path("matchedDepartmentIds"), path + ".matchedDepartmentIds", 100, allowed, true);
            links(item.path("chairAssignmentSourceKeys"), path + ".chairAssignmentSourceKeys", chairs);
            links(item.path("commissionRosterSourceKeys"), path + ".commissionRosterSourceKeys", rosters);
            links(item.path("candidateChairAssignmentSourceKeys"), path + ".candidateChairAssignmentSourceKeys", chairs);
            links(item.path("candidateCommissionRosterSourceKeys"), path + ".candidateCommissionRosterSourceKeys", rosters);
            citations(item.path("sources"), path + ".sources", request, true);
            warnings(item.path("warnings"), path + ".warnings", 100);
        }
        String central = order.path("centralCommissionSourceKey").asText(null);
        if (central != null && !rosters.contains(central)) fail("orderAnalysis.centralCommissionSourceKey", "known roster");
        validateReferences(order, request, rosters);
        if ("NOT_FOUND".equals(scope.path("scopeResolution").asText())
            && (assignments.size() != 0 || commissionRosters.size() != 0 || workItems.size() != 0))
            fail("orderAnalysis", "empty result for unmatched scope");
    }

    private void validateReferences(JsonNode order, AnalysisRequest request, Set<String> rosters) {
        JsonNode approvals = array(order.path("approvalDirections"), "orderAnalysis.approvalDirections", 100, true);
        for (int i = 0; i < approvals.size(); i++) {
            JsonNode item = approvals.get(i);
            String path = "orderAnalysis.approvalDirections[" + i + "]";
            object(item, path, Set.of("taskType", "approverName", "approverCommissionSourceKey", "sources"));
            choice(item.path("taskType"), path + ".taskType", TASKS);
            String key = item.path("approverCommissionSourceKey").asText(null);
            if (key != null && !rosters.contains(key)) fail(path + ".approverCommissionSourceKey", "known roster");
            citations(item.path("sources"), path + ".sources", request, true);
        }
        JsonNode consents = array(order.path("consentSignatories"), "orderAnalysis.consentSignatories", 300, true);
        for (int i = 0; i < consents.size(); i++) {
            JsonNode item = consents.get(i);
            String path = "orderAnalysis.consentSignatories[" + i + "]";
            object(item, path, Set.of("name", "position", "sources"));
            text(item.path("name"), path + ".name", 2000);
            citations(item.path("sources"), path + ".sources", request, true);
        }
        JsonNode coverage = array(order.path("coverage"), "orderAnalysis.coverage", 100, true);
        for (int i = 0; i < coverage.size(); i++) {
            JsonNode item = coverage.get(i);
            String path = "orderAnalysis.coverage[" + i + "]";
            object(item, path, Set.of("annexNumber", "expectedItemCount", "extractedItemCount",
                "missingItemNumbers", "status", "sources"));
            choice(item.path("status"), path + ".status", COMPLETENESS);
            if (!item.path("extractedItemCount").isIntegralNumber() || item.path("extractedItemCount").asInt() < 0)
                fail(path + ".extractedItemCount", "nonnegative integer");
            array(item.path("missingItemNumbers"), path + ".missingItemNumbers", 1000, true);
            citations(item.path("sources"), path + ".sources", request, false);
        }
    }

    private void header(JsonNode header, AnalysisRequest request) {
        object(header, "orderAnalysis.header", HEADER);
        for (String name : HEADER) {
            JsonNode field = header.path(name);
            if (field.isMissingNode() || field.isNull()) continue;
            String path = "orderAnalysis.header." + name;
            object(field, path, Set.of("value", "confidence", "sources"));
            text(field.path("value"), path + ".value", 2000);
            JsonNode confidence = field.path("confidence");
            if (!confidence.isMissingNode() && !confidence.isNull()
                && (!confidence.isNumber() || confidence.asDouble() < 0 || confidence.asDouble() > 1))
                fail(path + ".confidence", "number 0..1");
            citations(field.path("sources"), path + ".sources", request, true);
            if (name.endsWith("Date") || "accountingSubmissionDeadline".equals(name)) {
                try { LocalDate.parse(field.path("value").asText()); }
                catch (DateTimeParseException ex) { fail(path + ".value", "ISO date"); }
            }
        }
    }

    private void objectMention(JsonNode mention, String path, AnalysisRequest request) {
        object(mention, path, Set.of("kind", "name", "sources"));
        text(mention.path("name"), path + ".name", 2000);
        citations(mention.path("sources"), path + ".sources", request, true);
    }
    private void objects(JsonNode value, String path, AnalysisRequest request, boolean required) {
        JsonNode list = array(value, path, 100, !required);
        if (required && list.isEmpty()) fail(path, "nonempty array");
        for (int i = 0; i < list.size(); i++) objectMention(list.get(i), path + "[" + i + "]", request);
    }
    private void departmentIds(JsonNode item, String path, AnalysisRequest request, Set<String> allowed) {
        Set<String> ids = ids(item.path("matchedDepartmentIds"), path + ".matchedDepartmentIds", 100, allowed, true);
        String singular = item.path("matchedDepartmentId").asText(null);
        if (singular != null && (!allowed.contains(singular) || !ids.isEmpty() && !ids.contains(singular)))
            fail(path + ".matchedDepartmentId", "authorized matching department");
        if ("DEPARTMENTS".equals(request.scope().mode()) && singular == null && ids.isEmpty())
            fail(path + ".matchedDepartmentId", "authorized department");
    }
    private Set<String> ids(JsonNode value, String path, int max, Set<String> allowed, boolean optional) {
        JsonNode list = array(value, path, max, optional);
        Set<String> ids = new HashSet<>();
        for (JsonNode id : list) {
            try { UUID.fromString(id.asText()); }
            catch (IllegalArgumentException ex) { fail(path, "UUID"); }
            if (!ids.add(id.asText()) || !allowed.isEmpty() && !allowed.contains(id.asText()))
                fail(path, "unique authorized department IDs");
        }
        return ids;
    }
    private void links(JsonNode value, String path, Set<String> known) {
        JsonNode list = array(value, path, 200, true);
        Set<String> seen = new HashSet<>();
        for (JsonNode item : list) if (!item.isTextual() || !known.contains(item.asText()) || !seen.add(item.asText()))
            fail(path, "unique known source keys");
    }
    private void citations(JsonNode value, String path, AnalysisRequest request, boolean required) {
        JsonNode list = array(value, path, 20, !required);
        if (required && list.isEmpty()) fail(path, "nonempty sources");
        for (int i = 0; i < list.size(); i++) {
            String sourcePath = path + "[" + i + "]";
            JsonNode source = list.get(i);
            object(source, sourcePath, Set.of("fileId", "page", "quote"));
            String quote = source.path("quote").asText();
            if (quote.isBlank() || quote.length() > InventoryAiResponseLimits.expanded(400))
                throw new IllegalArgumentException("MM AI inventory response invalid at " + sourcePath
                    + ".quote: expected length 1..1200, actual=" + quote.length());
            if (!source.path("page").isIntegralNumber() || source.path("page").asInt() < 1)
                fail(sourcePath + ".page", "positive page");
            if (request.documents().stream().noneMatch(file -> file.fileId().toString().equals(source.path("fileId").asText())))
                fail(sourcePath + ".fileId", "uploaded file ID");
        }
    }
    private boolean targetNameMatches(JsonNode item, AnalysisRequest request) {
        String name = normalized(item.path("objectMention").path("name").asText());
        return request.scope().targets().stream().filter(target ->
            target.departmentId().toString().equals(item.path("matchedDepartmentId").asText())
                || item.path("matchedDepartmentIds").toString().contains(target.departmentId().toString()))
            .anyMatch(target -> java.util.stream.Stream.concat(java.util.stream.Stream.of(target.name()),
                target.aliases().stream()).map(this::normalized).anyMatch(name::equals));
    }
    private String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private void warnings(JsonNode value, String path, int max) {
        JsonNode list = array(value, path, max, true);
        for (JsonNode item : list) text(item, path, 2000);
    }
    private void key(JsonNode item, String path, Set<String> keys) {
        String key = item.path("sourceKey").asText();
        if (key.isBlank() || !keys.add(key)) fail(path + ".sourceKey", "unique nonempty source key");
    }
    private JsonNode array(JsonNode value, String path, int max, boolean optional) {
        if (optional && (value.isMissingNode() || value.isNull())) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (!value.isArray() || value.size() > InventoryAiResponseLimits.expanded(max)) fail(path, "array max=" + InventoryAiResponseLimits.expanded(max));
        return value;
    }
    private void object(JsonNode value, String path, Set<String> allowed) {
        if (value == null || !value.isObject()) fail(path, "object");
        value.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) fail(path + "." + name, "documented v2 field"); });
    }
    private void text(JsonNode value, String path, int max) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > InventoryAiResponseLimits.expanded(max))
            fail(path, "nonempty text");
    }
    private void choice(JsonNode value, String path, Set<String> values) {
        if (!value.isTextual() || !values.contains(value.asText())) fail(path, "known value");
    }
    private void fail(String path, String expected) {
        throw new IllegalArgumentException("MM AI inventory response invalid at " + path + ": expected " + expected);
    }
}
