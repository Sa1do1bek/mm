package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.StorageBinRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.InventoryAiDraftService.AnalysisRequest;
import com.enterprise.mm.service.InventoryAiDraftService.FileSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InventoryAiResultServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final RoleDataScopeService scope = mock(RoleDataScopeService.class);
    private final InventoryAiResultService service = new InventoryAiResultService(mapper, warehouses,
        mock(CommissionRepository.class), mock(StorageLocationRepository.class), mock(StorageBinRepository.class), scope,
        new InventoryCommissionExtractionValidator(), new InventoryOrderExtractionValidator(),
        new InventoryOrderScopedResultValidator());
    private final UUID analysisId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final AnalysisRequest request = new AnalysisRequest(UUID.randomUUID(),
        List.of(new FileSnapshot(fileId, "order.pdf", "application/pdf", "hash")));

    @AfterEach void cleanup() { TenantContext.clear(); CompanyContext.clear(); }

    @Test
    void validatesEvidenceAndKeepsAsOfDateSeparateFromWorkDates() throws Exception {
        JsonNode result = service.validate(sample(fileId, "2026-10-20", "2026-11-20"), analysisId, request);
        assertThat(result.path("fields").path("plannedStartDate").path("value").asText()).isEqualTo("2026-10-20");
        assertThat(result.path("fields").path("plannedEndDate").path("value").asText()).isEqualTo("2026-11-20");
        assertThat(result.path("documentFacts").path("stockAsOfDate").asText()).isEqualTo("2026-10-01");
    }

    @Test
    void rejectsInventedSourceAndReversedWorkPeriod() throws Exception {
        assertThatThrownBy(() -> service.validate(sample(UUID.randomUUID(), "2026-10-20", "2026-11-20"), analysisId, request))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(sample(fileId, "2026-11-20", "2026-10-20"), analysisId, request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesOnlyOneActiveAuthorizedWarehouse() throws Exception {
        TenantContext.setTenant("tenant-a");
        UUID companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        Warehouse warehouse = new Warehouse();
        warehouse.setId(UUID.randomUUID());
        warehouse.setName("Main warehouse");
        warehouse.setStatus("ACTIVE");
        when(warehouses.findByTenantIdAndCompanyIdAndIsDeletedFalse("tenant-a", companyId)).thenReturn(List.of(warehouse));
        when(scope.canAccessWarehouse(warehouse.getId())).thenReturn(true);
        JsonNode source = sample(fileId, "2026-10-20", "2026-11-20");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("fields")).set("warehouseName",
            mapper.readTree("{\"value\":\"Main warehouse\",\"confidence\":0.9,\"sources\":[{\"fileId\":\"" + fileId + "\",\"page\":1,\"quote\":\"Main warehouse\"}]}"));
        JsonNode validated = service.validate(source, analysisId, request);
        assertThat(service.resolve(validated).path("resolvedIds").path("warehouseId").asText()).isEqualTo(warehouse.getId().toString());
        when(scope.canAccessWarehouse(warehouse.getId())).thenReturn(false);
        assertThat(service.resolve(validated).path("resolvedIds").has("warehouseId")).isFalse();
    }

    @Test
    void preservesVersionedCommissionAnalysisForFrontend() throws Exception {
        var source = (ObjectNode) sample(fileId, "2026-10-20", "2026-11-20");
        source.set("commissionAnalysis", commissionAnalysis(fileId));
        var versioned = new AnalysisRequest(request.draftId(), request.documents(), 1);

        JsonNode validated = service.validate(source, analysisId, versioned);

        assertThat(validated.path("commissionAnalysis").path("commissions").get(0).path("members").get(0)
            .path("documentRole").asText()).isEqualTo("CHAIRMAN");
        assertThat(service.resolve(validated).path("commissionAnalysis")).isEqualTo(validated.path("commissionAnalysis"));
    }

    @Test
    void versionOneRequiresCommissionBlockAndRejectsUnknownEvidence() throws Exception {
        var versioned = new AnalysisRequest(request.draftId(), request.documents(), 1);
        assertThatThrownBy(() -> service.validate(sample(fileId, "2026-10-20", "2026-11-20"), analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class);
        var source = (ObjectNode) sample(fileId, "2026-10-20", "2026-11-20");
        source.set("commissionAnalysis", commissionAnalysis(UUID.randomUUID()));
        assertThatThrownBy(() -> service.validate(source, analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsLarge1749LikeCommissionSetWithoutTruncation() throws Exception {
        var source = (ObjectNode) sample(fileId, "2026-10-20", "2026-11-20");
        ObjectNode analysis = commissionAnalysis(fileId);
        ArrayNode commissions = (ArrayNode) analysis.path("commissions");
        ObjectNode template = (ObjectNode) commissions.get(0);
        for (int i = 1; i < 106; i++) {
            ObjectNode copy = template.deepCopy();
            copy.put("sourceKey", "appendix-2-row-" + i);
            commissions.add(copy);
        }
        source.set("commissionAnalysis", analysis);
        assertThat(source.toString().length()).isGreaterThan(65536);
        var versioned = new AnalysisRequest(request.draftId(), request.documents(), 1);

        assertThat(service.validate(source, analysisId, versioned).path("commissionAnalysis")
            .path("commissions").size()).isEqualTo(106);
    }

    @Test
    void acceptsExpandedCommissionEvidenceAndNames() throws Exception {
        var source = (ObjectNode) sample(fileId, "2026-10-20", "2026-11-20");
        ObjectNode analysis = commissionAnalysis(fileId);
        ObjectNode mention = mapper.createObjectNode();
        mention.put("kind", "ORG_UNIT");
        mention.put("name", "Regional warehouse group");
        ArrayNode citations = mention.putArray("sources");
        ObjectNode citation = citations.addObject();
        citation.put("fileId", fileId.toString());
        citation.put("page", 12);
        citation.put("quote", "x".repeat(442));
        ((ArrayNode) analysis.path("scopeMentions")).add(mention);
        ((ObjectNode) analysis.path("commissions").get(0)).put("name", "n".repeat(303));
        source.set("commissionAnalysis", analysis);
        var versioned = new AnalysisRequest(request.draftId(), request.documents(), 1);

        assertThat(service.validate(source, analysisId, versioned).path("commissionAnalysis")
            .path("scopeMentions").get(0).path("sources").get(0).path("quote").asText()).hasSize(442);

        citation.put("quote", "x".repeat(1500));
        assertThat(service.validate(source, analysisId, versioned).path("commissionAnalysis")
            .path("scopeMentions").get(0).path("sources").get(0).path("quote").asText()).hasSize(1500);

        citation.put("quote", "x".repeat(1501));
        assertThatThrownBy(() -> service.validate(source, analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class);
        citation.put("quote", "x".repeat(442));
        ((ObjectNode) analysis.path("commissions").get(0)).put("name", "n".repeat(721));
        assertThatThrownBy(() -> service.validate(source, analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsTopLevelWarningsToSixtyForVersionedAndLegacyExtraction() throws Exception {
        var source = (ObjectNode) sample(fileId, "2026-10-20", "2026-11-20");
        source.set("commissionAnalysis", commissionAnalysis(fileId));
        ArrayNode warnings = source.putArray("warnings");
        for (int i = 0; i < 60; i++) warnings.add("COMMISSION_REVIEW:" + i);
        var versioned = new AnalysisRequest(request.draftId(), request.documents(), 1);

        assertThat(service.validate(source, analysisId, versioned).path("warnings").size()).isEqualTo(60);
        assertThat(service.validate(source, analysisId, request).path("warnings").size()).isEqualTo(60);

        warnings.add("COMMISSION_REVIEW:60");
        assertThatThrownBy(() -> service.validate(source, analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("warnings exceed the 60-item limit");
        assertThatThrownBy(() -> service.validate(source, analysisId, request))
            .isInstanceOf(IllegalArgumentException.class);
        source.put("warnings", "wrong format");
        assertThatThrownBy(() -> service.validate(source, analysisId, versioned))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("warnings must be an array");
    }

    private ObjectNode commissionAnalysis(UUID citedFile) throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":1,"suggestedScope":{"mode":null,"names":[],"reviewRequired":true},
             "scopeMentions":[],"commissions":[{"sourceKey":"appendix-2-row-0","kind":"LOCAL",
             "name":"Example commission","nameOrigin":"DERIVED_LABEL","appointmentNumber":1,
             "sourceScope":{"departmentNames":[],"orgUnitNames":[],"warehouseNames":["063-ombor"]},
             "scopeMentions":[],"rosterCompleteness":"PARTIAL",
             "members":[{"fullName":"Ali Valiyev","position":null,"documentRole":"CHAIRMAN",
                "erpRoleSuggestion":"CHAIRMAN","sources":[{"fileId":"%s","page":5,"quote":"Ali Valiyev"}],
                "roleSources":[{"fileId":"%s","page":5,"quote":"raisi"}]}],
             "sources":[{"fileId":"%s","page":5,"quote":"komissiya"}],
             "completenessSources":[],"validFrom":null,"validTo":null,"warnings":["ROSTER_PARTIAL"]}],
             "recommendedSourceKeys":[],"warnings":["COUNT_SCOPE_REQUIRES_SELECTION"]}
            """.formatted(citedFile, citedFile, citedFile));
    }

    private JsonNode sample(UUID citedFile, String start, String end) throws Exception {
        return mapper.readTree("""
            {"analysisId":"%s","status":"EXTRACTED","fields":{
              "plannedStartDate":{"value":"%s","confidence":0.97,"sources":[{"fileId":"%s","page":2,"quote":"20.10.2026"}]},
              "plannedEndDate":{"value":"%s","confidence":0.97,"sources":[{"fileId":"%s","page":2,"quote":"20.11.2026"}]},
              "warehouseName":null,"commissionName":null,"globalWarehouse":null
            },"documentFacts":{"stockAsOfDate":"2026-10-01"},"warnings":[]}
            """.formatted(analysisId, start, citedFile, end, citedFile));
    }
}
