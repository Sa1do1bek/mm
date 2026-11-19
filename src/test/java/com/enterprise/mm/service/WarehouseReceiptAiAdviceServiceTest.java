package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.ContractManualIssueContextResponse.ContractManualIssueLineResponse;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.*;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class WarehouseReceiptAiAdviceServiceTest {
    WarehouseReceiptContextService contexts = mock(WarehouseReceiptContextService.class);
    MmAiClient client = mock(MmAiClient.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ReceiptAdviceContextMapper transport = new ReceiptAdviceContextMapper(mapper);
    WarehouseReceiptAiAdviceService service = new WarehouseReceiptAiAdviceService(contexts, client, mapper, transport);
    UUID contract = UUID.randomUUID(), warehouse = UUID.randomUUID(), spec = UUID.randomUUID(), material = UUID.randomUUID();
    Context baseline;

    @BeforeEach void setup() {
        baseline = context(List.of(position(spec, List.of(line(spec, material, true)), true)));
        when(contexts.get(eq(warehouse), eq(contract), anyInt(), anyInt())).thenReturn(baseline);
        when(client.isReceiptAdviceConfigured()).thenReturn(true);
    }

    ContractManualIssueLineResponse line(UUID s, UUID m, boolean selectable) {
        return new ContractManualIssueLineResponse(s, m, "001", "Cable", "m", BigDecimal.TEN,
            BigDecimal.ONE, BigDecimal.TEN, "TMB", "NONE", null, List.of(), null, null, null, null,
            BigDecimal.TEN, "1", new Selection(selectable ? Eligibility.ALLOWED : Eligibility.BLOCKED,
                selectable, selectable ? "MATERIAL_GROUP_POLICY" : "CONTRACT_READINESS",
                selectable ? "MATERIAL_GROUP_ALLOWED" : "CONTRACT_UNIT_MISMATCH", "reason", 1L));
    }
    Position position(UUID s, List<ContractManualIssueLineResponse> lines, boolean ready) {
        return new Position(s, 0L, "Cable", "ACTIVE", lines.size() > 1 ? "MATERIAL_GROUP" : "MATERIAL",
            null, null, "m", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN,
            ready ? Readiness.ready() : Readiness.blocked("MATERIAL_CHOICE_REQUIRED"), lines.size(), lines);
    }
    Context context(List<Position> positions) {
        return new Context(contract, "C-1", warehouse, "Cable warehouse", "Cables", 1L,
            new PageResponse<>(positions, 0, 25, positions.size(), positions.isEmpty() ? 0 : 1));
    }
    void use(Context c) {
        baseline = c;
        when(contexts.get(eq(warehouse), eq(contract), anyInt(), anyInt())).thenReturn(c);
    }
    ObjectNode suggestion(UUID s, UUID m) {
        return mapper.createObjectNode().put("specificationId", s.toString()).put("materialId", m.toString())
            .put("recommendedSelected", true).put("confidence", 0.8).put("reasonCode", "DESCRIPTION_MATCH")
            .put("reasonText", "Fits warehouse").put("requiresMaterialChoice", false);
    }
    ObjectNode response(String status, boolean complete, JsonNode... suggestions) {
        ObjectNode r = mapper.createObjectNode().put("status", status).put("message", "Optional advice").put("complete", complete);
        r.set("backendContext", transport.map(baseline, "uz"));
        var array = r.putArray("suggestions");
        for (var s : suggestions) array.add(s);
        r.putArray("warnings");
        return r;
    }
    void given(JsonNode response) { when(client.suggestReceiptAdvice(any(), any())).thenReturn(response); }
    AiRequest request() { return new AiRequest(warehouse, 0, 0, null, null); }

    @Test void explicitAdviceUsesServerContextWithSameDefaultPageSizeAsV2() {
        given(response("READY", true, suggestion(spec, material)));
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.backendContext()).isSameAs(baseline);
        assertThat(result.complete()).isTrue();
        assertThat(result.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.materialId()).isEqualTo(material);
            assertThat(s.recommendedSelected()).isTrue();
            assertThat(s.reasonCode()).isEqualTo("DESCRIPTION_MATCH");
        });
        verify(contexts, times(2)).get(warehouse, contract, 0, 25);
        verify(client).suggestReceiptAdvice(eq(transport.map(baseline, "uz")), eq("uz"));
    }
    @Test void preservesExplicitPageAndSize() {
        given(response("READY", true, suggestion(spec, material)));
        service.advise(contract, new AiRequest(warehouse, 2, 10, "MM_AI", "ru"));
        verify(contexts, times(2)).get(warehouse, contract, 2, 10);
        verify(client).suggestReceiptAdvice(any(), eq("ru"));
    }
    @Test void rejectsInventedPairsDuplicatesAndInvalidConfidenceAndMarksPartial() {
        given(response("READY", true, suggestion(UUID.randomUUID(), material), suggestion(spec, material),
            suggestion(spec, material), suggestion(spec, UUID.randomUUID()).put("confidence", 2)));
        var result = service.advise(contract, request());
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.warnings()).contains("AI_OUTPUT_REJECTED", "PARTIAL_ADVICE");
    }
    @Test void onlyBlockedMaterialsDoNotCallAi() {
        use(context(List.of(position(spec, List.of(line(spec, material, false)), true))));
        assertThat(service.advise(contract, request()).status()).isEqualTo("NO_CANDIDATES");
        verifyNoInteractions(client);
    }
    @Test void ignoresBlockedSuggestionOnMixedPageAndPreservesReadinessSource() {
        UUID blockedSpec = UUID.randomUUID(), blockedMaterial = UUID.randomUUID();
        use(context(List.of(position(spec, List.of(line(spec, material, true)), true),
            position(blockedSpec, List.of(line(blockedSpec, blockedMaterial, false)), true))));
        given(response("READY", true, suggestion(spec, material), suggestion(blockedSpec, blockedMaterial)));
        var result = service.advise(contract, request());
        assertThat(result.suggestions()).singleElement().satisfies(s -> assertThat(s.materialId()).isEqualTo(material));
        assertThat(result.backendContext().positions().content().get(1).materials().getFirst().receiptSelection().decisionSource())
            .isEqualTo("CONTRACT_READINESS");
    }
    @Test void twoPositiveAlternativesRequireUserChoice() {
        UUID alternative = UUID.randomUUID();
        use(context(List.of(position(spec, List.of(line(spec, material, true), line(spec, alternative, true)), false))));
        given(response("READY", true, suggestion(spec, material), suggestion(spec, alternative)));
        var result = service.advise(contract, request());
        assertThat(result.complete()).isTrue();
        assertThat(result.suggestions()).hasSize(2).allSatisfy(s -> {
            assertThat(s.recommendedSelected()).isFalse();
            assertThat(s.requiresMaterialChoice()).isTrue();
            assertThat(s.reasonCode()).isEqualTo("MATERIAL_CHOICE_REQUIRED");
        });
    }
    @Test void distinctSpecificationsCanBothBeRecommended() {
        UUID otherSpec = UUID.randomUUID();
        use(context(List.of(position(spec, List.of(line(spec, material, true)), true),
            position(otherSpec, List.of(line(otherSpec, material, true)), true))));
        given(response("READY", true, suggestion(spec, material), suggestion(otherSpec, material)));
        assertThat(service.advise(contract, request()).suggestions()).hasSize(2)
            .allSatisfy(s -> assertThat(s.recommendedSelected()).isTrue());
    }
    @Test void rejectsChangedUpstreamSnapshot() {
        ObjectNode response = response("READY", true, suggestion(spec, material));
        ((ObjectNode) response.path("backendContext")).put("warehouseId", UUID.randomUUID().toString());
        given(response);
        assertThat(service.advise(contract, request()).status()).isEqualTo("INVALID_RESPONSE");
    }
    @Test void missingCompleteOrEmptyReadyIsInvalid() {
        ObjectNode response = response("READY", true, suggestion(spec, material)); response.remove("complete");
        given(response);
        assertThat(service.advise(contract, request()).status()).isEqualTo("INVALID_RESPONSE");
        given(response("READY", true));
        assertThat(service.advise(contract, request()).status()).isEqualTo("INVALID_RESPONSE");
    }
    @Test void invalidAllSuggestionsCannotBecomeReady() {
        given(response("READY", true, suggestion(spec, material).put("confidence", 2)));
        assertThat(service.advise(contract, request()).status()).isEqualTo("INVALID_RESPONSE");
    }
    @Test void upstreamPartialWarningsAndManualChoiceArePreserved() {
        ObjectNode response = response("READY", false, suggestion(spec, material).put("recommendedSelected", false)
            .put("reasonCode", "INSUFFICIENT_EVIDENCE"));
        response.withArray("warnings").add("ANALYSIS_BUDGET_EXCEEDED");
        given(response);
        var result = service.advise(contract, request());
        assertThat(result.complete()).isFalse();
        assertThat(result.warnings()).contains("ANALYSIS_BUDGET_EXCEEDED");
        assertThat(result.suggestions().getFirst().recommendedSelected()).isFalse();
    }
    @Test void changedProfileOrStockSnapshotDropsAdviceAndReturnsFreshContext() {
        given(response("READY", true, suggestion(spec, material)));
        Context changed = new Context(contract, "C-1", warehouse, "Cable warehouse", "New purpose", 2L, baseline.positions());
        when(contexts.get(warehouse, contract, 0, 25)).thenReturn(baseline, changed);
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.warnings()).contains("STALE_CONTEXT");
        assertThat(result.backendContext()).isSameAs(changed);
        assertThat(result.suggestions()).isEmpty();
    }
    @Test void upstreamFailuresDoNotExposeMessagesOrBreakManualSelection() {
        when(client.suggestReceiptAdvice(any(), any())).thenThrow(new MmAiServiceException(401, "UNAUTHORIZED", "secret key", "req"));
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.message()).doesNotContain("secret");
        assertThat(result.backendContext()).isSameAs(baseline);
    }
    @Test void busyReturnsRetryAfterWithoutAutomaticRetry() {
        when(client.suggestReceiptAdvice(any(), any())).thenThrow(new MmAiServiceException(503, "RECEIPT_ADVICE_BUSY", "busy", "req", 5));
        var result = service.advise(contract, request());
        assertThat(result.retryAfterSeconds()).isEqualTo(5);
        assertThat(result.warnings()).contains("RECEIPT_ADVICE_BUSY");
        verify(client, times(1)).suggestReceiptAdvice(any(), any());
    }
    @Test void unavailableTransportRetainsBaseline() {
        when(client.suggestReceiptAdvice(any(), any())).thenThrow(new IllegalStateException("sensitive URL"));
        assertThat(service.advise(contract, request()).backendContext()).isSameAs(baseline);
    }
    @Test void unconfiguredOrDisabledAiNeverCallsRemote() {
        when(client.isReceiptAdviceConfigured()).thenReturn(false);
        assertThat(service.advise(contract, request()).warnings()).contains("AI_NOT_CONFIGURED");
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.advise(contract, request()).warnings()).contains("AI_DISABLED");
        verify(client, never()).suggestReceiptAdvice(any(), any());
    }
    @Test void oversizedContextDoesNotCallRemote() {
        ReflectionTestUtils.setField(service, "maxRequestBytes", 1);
        assertThat(service.advise(contract, request()).warnings()).contains("CONTEXT_TOO_LARGE");
        verify(client, never()).suggestReceiptAdvice(any(), any());
    }
    @Test void validatesArgumentsBeforeContextOrRemote() {
        assertThatThrownBy(() -> service.advise(contract, new AiRequest(warehouse, 0, 10, "OTHER", "uz"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.advise(contract, new AiRequest(warehouse, 0, 10, null, "xx"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.advise(contract, new AiRequest(warehouse, 0, 26, null, null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.advise(contract, new AiRequest(warehouse, -1, 10, null, null))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(contexts, client);
    }
    @Test void legacyProviderIsNotForwardedAndIsExplicitlyDeprecated() {
        given(response("READY", true, suggestion(spec, material)));
        var result = service.advise(contract, new AiRequest(warehouse, 0, 25, "CLAUDE", "uz"));
        assertThat(result.warnings()).contains("PROVIDER_MANAGED_BY_SERVICE");
        verify(client).suggestReceiptAdvice(any(), eq("uz"));
    }
    @Test void scopeFailureIsNotSwallowedAsAiUnavailability() {
        when(contexts.get(warehouse, contract, 0, 25)).thenThrow(new BadRequestException("scope denied"));
        assertThatThrownBy(() -> service.advise(contract, request())).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(client);
    }
    @Test void packagedGroupAndBlockedContractFixtureIsAcceptedWithoutRewritingBackendContext() throws Exception {
        JsonNode requestFixture, responseFixture;
        try (var in = getClass().getResourceAsStream("/receipt-advice/receipt-suggestions-mm-mixed-request.json")) {
            requestFixture = mapper.readTree(in);
        }
        try (var in = getClass().getResourceAsStream("/receipt-advice/receipt-suggestions-mm-mixed-response.json")) {
            responseFixture = mapper.readTree(in);
        }
        Context actual = mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .treeToValue(requestFixture.get("backendContext"), Context.class);
        // Echo the actual serialized MM DTO, including its nullable/default fields.
        use(actual);
        ((ObjectNode) responseFixture).set("backendContext", transport.map(actual, "uz"));
        given(responseFixture);
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.backendContext()).isSameAs(actual);
        assertThat(result.complete()).isTrue();
        assertThat(result.suggestions()).hasSize(2).allSatisfy(s -> {
            assertThat(s.recommendedSelected()).isFalse();
            assertThat(s.requiresMaterialChoice()).isTrue();
        });
    }
    @Test void upstreamBusinessUnavailableIsNotTreatedAsReadyOrTransportError() {
        ObjectNode response = response("UNAVAILABLE", false);
        response.withArray("warnings").add("AI_DISABLED");
        given(response);
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.complete()).isFalse();
        assertThat(result.warnings()).containsExactly("AI_DISABLED");
        assertThat(result.backendContext()).isSameAs(baseline);
    }
    @Test void nonChoiceReadinessFailureNeverCallsAiEvenIfMaterialWasAllowed() {
        var p = position(spec, List.of(line(spec, material, true)), true);
        use(context(List.of(new Position(p.specificationId(), p.version(), p.name(), p.status(), p.kind(),
            null, null, p.contractUnit(), p.contractQuantity(), p.contractUnitPrice(), p.remainingContractQuantity(),
            Readiness.blocked("CONTRACT_UNIT_MISMATCH"), 1, p.materials()))));
        assertThat(service.advise(contract, request()).status()).isEqualTo("NO_CANDIDATES");
        verifyNoInteractions(client);
    }

    @Test void noCandidatesRefersOnlyToPreviewsAndRetainsPreviewWarning() {
        var p = position(spec, List.of(line(spec, material, false)), false);
        use(context(List.of(new Position(p.specificationId(), p.version(), p.name(), p.status(), "MATERIAL_GROUP",
            null, null, p.contractUnit(), p.contractQuantity(), p.contractUnitPrice(), p.remainingContractQuantity(),
            p.readiness(), 30, p.materials()))));
        var result = service.advise(contract, request());
        assertThat(result.status()).isEqualTo("NO_CANDIDATES");
        assertThat(result.warnings()).contains("PREVIEW_ONLY");
        verifyNoInteractions(client);
    }

}
