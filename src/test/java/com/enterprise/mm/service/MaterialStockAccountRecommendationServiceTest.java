package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.entity.Material;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MaterialStockAccountRecommendationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MaterialStockAccountRecommendationService service = new MaterialStockAccountRecommendationService(
        mock(MaterialStockAccountResolutionService.class), mock(StockAccountResolver.class),
        mock(MaterialRepository.class), mapper, mock(AiAnalysisJobRepository.class),
        mock(NamedParameterJdbcTemplate.class), mock(RoleDataScopeService.class),
        mock(ManualReceiptAccountDraftService.class));

    @Test
    void convertsBackendMovementToAiEvidenceContract() {
        UUID materialId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        var source = Map.<String, Object>of("id", "movement:123", "sourceType", "OPERATIONAL_MOVEMENT",
            "date", LocalDate.of(2026, 11, 12), "accountCode", "1010", "quantity", "10");
        var allowed = List.of(new MaterialStockAccountResolutionService.Account(accountId, "1010", "Materials"));

        Map<String, Object> evidence = service.aiEvidence(source, materialId, allowed);

        assertThat(evidence).containsEntry("sourceType", "MM_MOVEMENT")
            .containsEntry("sourceId", "123")
            .containsEntry("sourceDate", "2026-11-12")
            .containsEntry("complete", true)
            .containsEntry("materialId", materialId)
            .containsEntry("accountId", accountId);
        assertThat(evidence.get("facts")).isEqualTo(Map.of("accountCode", "1010", "quantity", "10"));
    }

    @Test
    void preservesPostedReceiptPurposeAsOperationContext() {
        UUID materialId = UUID.randomUUID(), accountId = UUID.randomUUID(), warehouseId = UUID.randomUUID();
        var operation = Map.<String, Object>of("operationType", "RECEIPT", "purpose", "Repair",
            "warehouseId", warehouseId);
        var source = Map.<String, Object>of("id", "movement:123", "sourceType", "OPERATIONAL_MOVEMENT",
            "date", LocalDate.of(2026, 11, 12), "accountCode", "1010", "operationContext", operation);
        var allowed = List.of(new MaterialStockAccountResolutionService.Account(accountId, "1010", "Materials"));

        var evidence = service.aiEvidence(source, materialId, allowed);

        assertThat(evidence).containsEntry("operationContext", operation);
        assertThat(((Map<?, ?>) evidence.get("facts")).containsKey("operationContext")).isFalse();
    }

    @Test
    void rejectsAccountOutsideBackendCandidates() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID allowedId = UUID.randomUUID();
        UUID inventedId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var request = new MaterialStockAccountRecommendationService.Request(materialId, null, "user", 2L,
            "resolution", "evidence", List.of(allowedId), List.of("source-1"), mapper.createObjectNode());
        var result = mapper.readTree("""
            {"schemaVersion":1,"jobId":"%s","materialId":"%s","materialVersion":2,
             "evidenceFingerprint":"evidence","status":"RECOMMENDED",
             "recommendedAccount":{"id":"%s","code":"1010"},
             "confidence":"MEDIUM",
             "reason":"Material history supports this account","evidenceRefs":["source-1"]}
            """.formatted(jobId, materialId, inventedId));

        assertThatThrownBy(() -> service.validate(jobId, request, result))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsNoReliableSuggestion() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var request = new MaterialStockAccountRecommendationService.Request(materialId, null, "user", 2L,
            "resolution", "evidence", List.of(UUID.randomUUID()), List.of(), mapper.createObjectNode());
        var result = mapper.readTree("""
            {"schemaVersion":1,"jobId":"%s","materialId":"%s","materialVersion":2,
             "evidenceFingerprint":"evidence","status":"NO_RELIABLE_SUGGESTION",
             "recommendedAccount":null,"confidence":null,"reason":"Evidence is insufficient","evidenceRefs":[]}
            """.formatted(jobId, materialId));

        assertThat(service.validate(jobId, request, result)).isEqualTo(result);
        assertThatThrownBy(() -> service.validate(UUID.randomUUID(), request, result))
            .isInstanceOf(BadRequestException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("confidence", "LOW");
        assertThatThrownBy(() -> service.validate(jobId, request, result))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requiresAReceiptDraftForAmbiguousOneCAccounts() {
        UUID materialId = UUID.randomUUID();
        var resolution = new MaterialStockAccountResolutionService.Resolution(materialId, 1L, true,
            "CHOICE_REQUIRED", null, null, null, List.of(), null, "fingerprint", List.of());
        var resolutionService = mock(MaterialStockAccountResolutionService.class);
        var materials = mock(MaterialRepository.class);
        var material = mock(Material.class);
        when(resolutionService.get(materialId)).thenReturn(resolution);
        when(materials.findByTenantIdAndIdAndIsDeletedFalse(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(materialId))).thenReturn(java.util.Optional.of(material));
        when(material.getCompanyId()).thenReturn(UUID.randomUUID());
        var scoped = new MaterialStockAccountRecommendationService(resolutionService,
            mock(StockAccountResolver.class), materials, mapper, mock(AiAnalysisJobRepository.class),
            mock(NamedParameterJdbcTemplate.class), mock(RoleDataScopeService.class),
            mock(ManualReceiptAccountDraftService.class));
        com.enterprise.mm.common.TenantContext.setTenant("test");
        com.enterprise.mm.common.CompanyContext.setCompany(material.getCompanyId());
        try {
            assertThatThrownBy(() -> scoped.snapshot(materialId, "user"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("saved receipt draft");
        } finally {
            com.enterprise.mm.common.TenantContext.clear();
            com.enterprise.mm.common.CompanyContext.clear();
        }
    }
}
