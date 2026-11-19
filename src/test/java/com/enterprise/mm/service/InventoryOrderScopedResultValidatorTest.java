package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.enterprise.mm.service.InventoryOrderAnalysisScopeService.Scope;
import com.enterprise.mm.service.InventoryOrderAnalysisScopeService.Target;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryOrderScopedResultValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final InventoryOrderScopedResultValidator validator = new InventoryOrderScopedResultValidator();
    private final UUID job = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID department = UUID.randomUUID();
    private final UUID file = UUID.randomUUID();
    private final AnalysisRequest request = new AnalysisRequest(UUID.randomUUID(),
        List.of(new FileSnapshot(file, "order.pdf", "application/pdf", "a".repeat(64))), 2, 2,
        new Scope("DEPARTMENTS", company, List.of(department),
            List.of(new Target(department, "Unit A", List.of("A"), List.of()))));

    @Test
    void acceptsScopedChairAssignment() {
        ObjectNode result = base();
        ObjectNode chair = mapper.createObjectNode();
        chair.put("sourceKey", "chair-1");
        chair.put("annexNumber", "2");
        chair.put("itemNumber", "1");
        chair.put("matchedDepartmentId", department.toString());
        chair.put("chairName", "Person A");
        chair.put("otherMembersRequireLocalOrder", true);
        ObjectNode mention = chair.putObject("objectMention");
        mention.put("name", "Unit A");
        mention.set("sources", sources());
        chair.putArray("objectMentions").add(mention.deepCopy());
        chair.set("sources", sources());
        ((ArrayNode) result.path("orderAnalysis").path("chairAssignments")).add(chair);
        assertThatCode(() -> validator.validate(result, job, request)).doesNotThrowAnyException();
    }

    @Test
    void acceptsPublishedVersionTwoWorkLinksWithoutInventingACommission() {
        ObjectNode result = base();
        ObjectNode scope = (ObjectNode) result.path("scope");
        scope.putArray("targetResolutions").addObject().put("departmentId", department.toString()).put("resolution", "MATCHED");
        ObjectNode chair = ((ArrayNode) result.path("orderAnalysis").path("chairAssignments")).addObject();
        chair.put("sourceKey", "chair-1");
        chair.put("chairName", "Person A");
        chair.put("matchedDepartmentId", department.toString());
        chair.putArray("matchedDepartmentIds").add(department.toString());
        chair.put("otherMembersRequireLocalOrder", true);
        ObjectNode mention = chair.putObject("objectMention");
        mention.put("kind", "ORG_UNIT"); mention.put("name", "Unit A"); mention.set("sources", sources());
        chair.putArray("objectMentions").add(mention.deepCopy());
        chair.set("sources", sources());
        ObjectNode work = ((ArrayNode) result.path("orderAnalysis").path("workItems")).addObject();
        work.put("sourceKey", "work-1"); work.put("kind", "LOCAL");
        work.put("rosterCompleteness", "PARTIAL");
        work.putArray("taskTypes").add("STOCK_COUNT");
        work.putArray("objectMentions").add(mention.deepCopy());
        work.putArray("matchedDepartmentIds").add(department.toString());
        work.putArray("chairAssignmentSourceKeys").add("chair-1");
        work.putArray("commissionRosterSourceKeys");
        work.set("sources", sources());
        assertThatCode(() -> validator.validate(result, job, request)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnotherDepartmentBeforePersistence() {
        ObjectNode result = base();
        ObjectNode chair = mapper.createObjectNode();
        chair.put("sourceKey", "chair-1");
        chair.put("annexNumber", "2");
        chair.put("itemNumber", "1");
        chair.put("matchedDepartmentId", UUID.randomUUID().toString());
        chair.put("chairName", "Person B");
        chair.put("otherMembersRequireLocalOrder", true);
        ObjectNode mention = chair.putObject("objectMention");
        mention.put("name", "Unit B");
        mention.set("sources", sources());
        chair.putArray("objectMentions").add(mention.deepCopy());
        chair.set("sources", sources());
        ((ArrayNode) result.path("orderAnalysis").path("chairAssignments")).add(chair);
        assertThatThrownBy(() -> validator.validate(result, job, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orderAnalysis.chairAssignments[0].matchedDepartmentId");
    }

    @Test
    void rejectsAssignmentNameThatDoesNotMatchAuthorizedDepartment() {
        ObjectNode result = base();
        ObjectNode chair = mapper.createObjectNode();
        chair.put("sourceKey", "chair-1");
        chair.put("annexNumber", "2");
        chair.put("itemNumber", "1");
        chair.put("matchedDepartmentId", department.toString());
        chair.put("chairName", "Person B");
        chair.put("otherMembersRequireLocalOrder", true);
        ObjectNode mention = chair.putObject("objectMention");
        mention.put("name", "Unrelated unit");
        mention.set("sources", sources());
        chair.putArray("objectMentions").add(mention.deepCopy());
        chair.set("sources", sources());
        ((ArrayNode) result.path("orderAnalysis").path("chairAssignments")).add(chair);
        assertThatThrownBy(() -> validator.validate(result, job, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orderAnalysis.chairAssignments[0].objectMention.name");
    }

    @Test
    void rejectsOversizedEvidenceWithPrecisePath() {
        ObjectNode result = base();
        ObjectNode header = (ObjectNode) result.path("orderAnalysis").path("header");
        ObjectNode number = header.putObject("orderNumber");
        number.put("value", "1749");
        number.set("sources", sources());
        ((ObjectNode) number.path("sources").get(0)).put("quote", "x".repeat(1200));
        org.assertj.core.api.Assertions.assertThatCode(() -> validator.validate(result, job, request))
            .doesNotThrowAnyException();
        ((ObjectNode) number.path("sources").get(0)).put("quote", "x".repeat(1201));
        assertThatThrownBy(() -> validator.validate(result, job, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orderAnalysis.header.orderNumber.sources[0].quote")
            .hasMessageContaining("actual=1201");
    }

    @Test
    void rejectsCreateEligibleChairOnlyRoster() {
        ObjectNode result = base();
        ObjectNode roster = mapper.createObjectNode();
        roster.put("sourceKey", "roster-1");
        roster.put("kind", "LOCAL");
        roster.put("name", "Unit A commission");
        roster.put("nameOrigin", "DOCUMENT");
        roster.putArray("matchedDepartmentIds").add(department.toString());
        roster.put("rosterCompleteness", "PARTIAL");
        roster.put("createEligible", true);
        roster.putArray("members").addObject().put("fullName", "Person A").set("sources", sources());
        roster.set("sources", sources());
        ((ArrayNode) result.path("orderAnalysis").path("commissionRosters")).add(roster);
        assertThatThrownBy(() -> validator.validate(result, job, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("orderAnalysis.commissionRosters[0].createEligible");
    }

    private ObjectNode base() {
        ObjectNode result = mapper.createObjectNode();
        result.put("analysisId", job.toString());
        result.put("status", "NEEDS_REVIEW");
        result.put("model", "test-model");
        ObjectNode scope = result.putObject("scope");
        scope.put("mode", "DEPARTMENTS");
        scope.putArray("departmentIds").add(department.toString());
        scope.put("scopeResolution", "MATCHED");
        ObjectNode order = result.putObject("orderAnalysis");
        order.put("schemaVersion", 2);
        order.putObject("header");
        order.putArray("chairAssignments");
        order.putArray("workItems");
        order.putArray("commissionRosters");
        order.putArray("coverage");
        order.putArray("warnings");
        result.putArray("warnings");
        return result;
    }

    private ArrayNode sources() {
        ArrayNode sources = mapper.createArrayNode();
        ObjectNode source = sources.addObject();
        source.put("fileId", file.toString());
        source.put("page", 1);
        source.put("quote", "Unit A");
        return sources;
    }
}
