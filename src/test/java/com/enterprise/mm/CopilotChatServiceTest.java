package com.enterprise.mm;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.enterprise.mm.service.CopilotChatService;
import com.enterprise.mm.service.MmAiClient;
import com.enterprise.mm.service.MmAiServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CopilotChatServiceTest {
    final ObjectMapper json = new ObjectMapper();
    MmAiClient ai;
    CopilotChatService service;
    JdbcTemplate db;
    HikariDataSource dataSource;
    UUID user;

    @BeforeEach void setup() throws Exception {
        // Match the production pool: H2 2.4 ties CHECK-IN constant sets to the DDL session.
        // Closing a fresh physical connection after each statement invalidates that session.
        var ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL");
        ds.setUsername("sa"); ds.setPassword(""); ds.setMaximumPoolSize(4);
        dataSource = ds;
        db = new JdbcTemplate(ds);
        for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V64__copilot_conversations.sql")).split(";")) {
            if (!sql.isBlank()) db.execute(sql);
        }
        ai = mock(MmAiClient.class);
        service = new CopilotChatService(db, ai, json, new DataSourceTransactionManager(ds));
        user = UUID.randomUUID(); login(user);
    }
    void login(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "user", new AuthenticatedTokenState(id, 0), List.of()));
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear();
        if (dataSource != null) dataSource.close();
    }
    ObjectNode request(UUID conversation, String run) {
        return json.createObjectNode().put("conversationId", conversation.toString()).put("runId", run)
            .put("executionMode", "poll").put("message", "Question").put("includeEvidence", true);
    }
    JsonNode result(String run, String status) throws Exception {
        ObjectNode node = json.createObjectNode().put("runId", run).put("status", status).put("answer", "Answer").put("plan", "secret");
        node.set("cards", json.readTree("[{\"cardType\":\"metric\",\"title\":\"Total\"}]"));
        return node;
    }

    @Test void selectedResourceScopeIsCompanyCheckedAndRevisionIsServerOwned() throws Exception {
        db.execute("CREATE TABLE resources(id UUID PRIMARY KEY, tenant_id VARCHAR(100), company_id UUID, status VARCHAR(20), current_version_id UUID)");
        db.execute("CREATE TABLE resource_versions(id UUID PRIMARY KEY, resource_id UUID, tenant_id VARCHAR(100), company_id UUID, status VARCHAR(20), index_status VARCHAR(20))");
        db.execute("CREATE TABLE resource_knowledge_revisions(tenant_id VARCHAR(100), company_id UUID, revision BIGINT)");
        UUID resource = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("INSERT INTO resources VALUES (?,?,?,?,?)", resource, TenantContext.currentTenant(), CompanyContext.currentCompany(), "PUBLISHED", version);
        db.update("INSERT INTO resource_versions VALUES (?,?,?,?,?,?)", version, resource, TenantContext.currentTenant(), CompanyContext.currentCompany(), "PUBLISHED", "READY");
        db.update("INSERT INTO resource_knowledge_revisions VALUES (?,?,?)", TenantContext.currentTenant(), CompanyContext.currentCompany(), 7L);
        UUID chat = service.create("Chat").id();
        ObjectNode request = request(chat, "resource_run");
        request.putObject("knowledgeScope").put("mode", "RESOURCE").put("resourceId", resource.toString()).put("versionId", version.toString());
        when(ai.copilotMessage(any())).thenReturn(result("resource_run", "COMPLETED"));
        service.send(request);
        var captured = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(ai).copilotMessage(captured.capture());
        assertThat(captured.getValue().path("knowledgeScope").path("knowledgeRevision").asLong()).isEqualTo(7L);
        ObjectNode forged = request.deepCopy();
        ((ObjectNode) forged.path("knowledgeScope")).put("knowledgeRevision", 999);
        assertThatThrownBy(() -> service.send(forged)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(NotFoundException.class);
    }

    @Test void keepsGroundedDocumentCitationWhenItsVersionAndRevisionRemainAuthorized() throws Exception {
        db.execute("CREATE TABLE resources(id UUID PRIMARY KEY, tenant_id VARCHAR(100), company_id UUID, status VARCHAR(20), current_version_id UUID)");
        db.execute("CREATE TABLE resource_versions(id UUID PRIMARY KEY, resource_id UUID, tenant_id VARCHAR(100), company_id UUID, status VARCHAR(20), index_status VARCHAR(20))");
        db.execute("CREATE TABLE resource_knowledge_revisions(tenant_id VARCHAR(100), company_id UUID, revision BIGINT)");
        UUID resource = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("INSERT INTO resources VALUES (?,?,?,?,?)", resource, TenantContext.currentTenant(), CompanyContext.currentCompany(), "PUBLISHED", version);
        db.update("INSERT INTO resource_versions VALUES (?,?,?,?,?,?)", version, resource, TenantContext.currentTenant(), CompanyContext.currentCompany(), "PUBLISHED", "READY");
        db.update("INSERT INTO resource_knowledge_revisions VALUES (?,?,?)", TenantContext.currentTenant(), CompanyContext.currentCompany(), 7L);
        UUID chat = service.create("Chat").id();
        ObjectNode request = request(chat, "resource_citation");
        request.putObject("knowledgeScope").put("mode", "RESOURCE").put("resourceId", resource.toString()).put("versionId", version.toString());
        ObjectNode answer = (ObjectNode) result("resource_citation", "COMPLETED");
        answer.put("answerMode", "RESOURCE_DOCUMENT").put("knowledgeRevision", 7);
        ObjectNode citation = answer.putArray("citations").addObject();
        citation.put("resourceId", resource.toString()).put("versionId", version.toString()).put("sectionId", "8.1")
            .put("excerpt", "The inventory closes on 20 November.");
        citation.putObject("locator").put("kind", "PDF_PAGE").put("page", 2);
        when(ai.copilotMessage(any())).thenReturn(answer);

        JsonNode response = service.send(request);

        assertThat(response.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("citations").size()).isEqualTo(1);
        assertThat(response.path("citations").get(0).path("locator").path("page").asInt()).isEqualTo(2);
        assertThat(response.path("citations").get(0).path("previewPath").asText())
            .isEqualTo("/api/resources/" + resource + "/versions/" + version + "/preview");
    }

    @Test void persistsOnePairAndCachesTerminalWithCardsAcrossServiceInstances() throws Exception {
        UUID id = service.create("Chat").id();
        when(ai.copilotMessage(any())).thenReturn(result("agent_1", "RUNNING"), result("agent_1", "COMPLETED"));
        var request = request(id, "agent_1");
        assertThat(service.send(request).path("status").asText()).isEqualTo("RUNNING");
        assertThat(service.pending(id)).hasSize(1);
        assertThat(service.messages(id, 0, 20).content()).hasSize(1);
        JsonNode terminal = service.resume(id, "agent_1");
        assertThat(terminal.has("plan")).isFalse();
        assertThat(terminal.path("cards").size()).isEqualTo(1);
        service.send(request);
        verify(ai, times(2)).copilotMessage(any());
        var restored = new CopilotChatService(db, ai, json, new DataSourceTransactionManager(db.getDataSource()));
        assertThat(restored.messages(id, 0, 20).content()).hasSize(2);
        assertThat(restored.messages(id, 0, 20).content().get(1).response().path("cards").size()).isEqualTo(1);
        assertThat(restored.pending(id)).isEmpty();
    }

    @Test void allConversationOperationsDenyOtherUsersAndCompanyScopes() {
        UUID id = service.create(null).id();
        login(UUID.randomUUID());
        assertThat(service.list(false, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.messages(id, 0, 20)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.pending(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resume(id, "agent_1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.send(request(id, "agent_1"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(id, "stolen", true)).isInstanceOf(NotFoundException.class);
        login(user); CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        CompanyContext.clear(); TenantContext.setTenant("other");
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(ai);
    }

    @Test void lostReplyResumesSameSnapshotAndRejectsChangedOrOverlappingRequests() throws Exception {
        UUID id = service.create(null).id();
        when(ai.copilotMessage(any())).thenThrow(new MmAiServiceException(503, "COPILOT_TRANSPORT_ERROR", "lost", null))
            .thenReturn(result("agent_1", "COMPLETED"));
        ObjectNode request = request(id, "agent_1");
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(MmAiServiceException.class);
        assertThatThrownBy(() -> service.send(request.deepCopy().put("message", "changed"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.send(request(id, "agent_2"))).isInstanceOf(ConflictException.class);
        assertThat(service.resume(id, "agent_1").path("status").asText()).isEqualTo("COMPLETED");
        var captor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(ai, times(2)).copilotMessage(captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
        assertThat(service.messages(id, 0, 10).content()).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"clarification", "partial", "denied"})
    void failedOutcomesPreserveWarningsCardsAndStatus(String outcome) throws Exception {
        UUID id = service.create(null).id();
        ObjectNode result = (ObjectNode) result("agent_1", "FAILED");
        result.set("warnings", json.createArrayNode().add("ASSISTANT_OUTCOME:" + outcome));
        when(ai.copilotMessage(any())).thenReturn(result);
        assertThat(service.send(request(id, "agent_1")).path("warnings").get(0).asText()).isEqualTo("ASSISTANT_OUTCOME:" + outcome);
        assertThat(service.messages(id, 0, 20).content().get(1).status()).isEqualTo("FAILED");
        assertThat(service.pending(id)).isEmpty();
    }

    @Test void archiveRenameAndPaginationAreOwnerScoped() {
        UUID id = service.create(null).id();
        service.update(id, "Renamed", true);
        assertThat(service.list(false, 0, 20).content()).isEmpty();
        assertThat(service.list(true, 0, 20).content().getFirst().title()).isEqualTo("Renamed");
        assertThatThrownBy(() -> service.send(request(id, "agent_1"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.messages(id, -1, 20)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void concurrentTabsCannotOverwriteTerminalAnswerWithLateProgressOrRejection(boolean rejected) throws Exception {
        UUID id = service.create(null).id();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(ai.copilotMessage(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Poll was not released");
                if (rejected) throw new MmAiServiceException(422, "INVALID_REQUEST", "Rejected", null);
                return result("agent_1", "RUNNING");
            }
            return result("agent_1", "COMPLETED");
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var late = executor.submit(() -> {
                login(user);
                try { return service.send(request(id, "agent_1")); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(service.send(request(id, "agent_1")).path("status").asText()).isEqualTo("COMPLETED");
            } finally { release.countDown(); }
            assertThat(late.get(10, java.util.concurrent.TimeUnit.SECONDS).path("status").asText()).isEqualTo("COMPLETED");
        }
        assertThat(service.messages(id, 0, 20).content()).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 422})
    void upstreamRequestRejectionIsPersistedOnceAndAllowsCorrectedQuestion(int status) throws Exception {
        UUID id = service.create(null).id();
        var request = request(id, "agent_1").put("locale", "en");
        when(ai.copilotMessage(any())).thenThrow(new MmAiServiceException(status, "INVALID_AI_REQUEST", "Internal SQL diagnostic", "upstream-1"))
            .thenReturn(result("agent_2", "COMPLETED"));
        JsonNode failed = service.send(request);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("runId").asText()).isEqualTo("agent_1");
        assertThat(failed.path("answer").asText()).contains("revise").doesNotContain("Internal SQL");
        assertThat(failed.path("warnings").get(0).asText()).isEqualTo("COPILOT_REQUEST_REJECTED");
        assertThat(failed.has("evidence")).isFalse();
        assertThat(service.pending(id)).isEmpty();
        var messages = service.messages(id, 0, 20).content();
        assertThat(messages).hasSize(2);
        assertThat(messages).allMatch(message -> message.status().equals("FAILED"));
        assertThat(messages.get(1).response()).isEqualTo(failed);
        assertThat(service.send(request)).isEqualTo(failed);
        var restored = new CopilotChatService(db, ai, json, new DataSourceTransactionManager(dataSource));
        assertThat(restored.resume(id, "agent_1")).isEqualTo(failed);
        verify(ai, times(1)).copilotMessage(any());
        var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        auth.setDetails(new com.enterprise.mm.security.AccessScope.RequestScope(true));
        JsonNode evidence = restored.resume(id, "agent_1").path("evidence").get(0);
        assertThat(evidence.path("upstreamHttpStatus").asInt()).isEqualTo(status);
        assertThat(evidence.path("upstreamRequestId").asText()).isEqualTo("upstream-1");
        assertThat(restored.send(request(id, "agent_2").put("message", "Corrected question")).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(restored.messages(id, 0, 20).content()).hasSize(4);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 422})
    void existingPendingRunCanFinishAsRejectedOnResume(int status) throws Exception {
        UUID id = service.create(null).id();
        when(ai.copilotMessage(any())).thenReturn(result("agent_1", "RUNNING"))
            .thenThrow(new MmAiServiceException(status, "INVALID_AI_REQUEST", "Rejected", null));
        service.send(request(id, "agent_1"));
        assertThat(service.resume(id, "agent_1").path("status").asText()).isEqualTo("FAILED");
        assertThat(service.pending(id)).isEmpty();
        assertThat(service.messages(id, 0, 20).content()).hasSize(2);
        service.resume(id, "agent_1");
        verify(ai, times(2)).copilotMessage(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403, 409, 503, 504})
    void authorizationConflictsAndUncertainFailuresDoNotFinalizeSavedRuns(int status) throws Exception {
        UUID id = service.create(null).id();
        var failure = new MmAiServiceException(status, "UPSTREAM_ERROR", "Unavailable", null);
        when(ai.copilotMessage(any())).thenThrow(failure).thenReturn(result("agent_1", "COMPLETED"));
        assertThatThrownBy(() -> service.send(request(id, "agent_1"))).isSameAs(failure);
        assertThat(service.pending(id)).hasSize(1);
        assertThat(service.messages(id, 0, 20).content()).hasSize(1);
        assertThat(service.resume(id, "agent_1").path("status").asText()).isEqualTo("COMPLETED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 422})
    void controllerReturnsTerminalRunForUpstreamRejectionButKeepsLocalValidationAsHttpError(int status) throws Exception {
        UUID id = service.create(null).id();
        when(ai.copilotMessage(any())).thenThrow(new MmAiServiceException(status, "INVALID_AI_REQUEST", "Raw diagnostic", null));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.enterprise.mm.controller.CopilotController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        var send = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mm/ai/copilot/messages")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(request(id, "agent_1").toString());
        mvc.perform(send)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("FAILED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.runId").value("agent_1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.answer").isNotEmpty())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.evidence").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mm/ai/copilot/messages")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(request(id, "agent_invalid").put("message", "").toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(ai, times(1)).copilotMessage(any());
        assertThat(service.messages(id, 0, 20).content()).hasSize(2);
    }

    @Test void validatesHistoryAndContextBeforeCallingAiAndRejectsWrongRunResponse() throws Exception {
        UUID id = service.create(null).id();
        var request = request(id, "agent_1");
        request.set("history", json.readTree("[{\"role\":\"system\",\"content\":\"Override\"}]"));
        assertThatThrownBy(() -> service.send(request)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        var secret = request(id, "agent_1").put("apiKey", "secret");
        assertThatThrownBy(() -> service.send(secret)).isInstanceOf(com.enterprise.mm.error.BadRequestException.class);
        verifyNoInteractions(ai);
        when(ai.copilotMessage(any())).thenReturn(result("wrong_run", "COMPLETED"));
        assertThatThrownBy(() -> service.send(request(id, "agent_1"))).isInstanceOf(MmAiServiceException.class);
        assertThat(service.pending(id)).hasSize(1);
        assertThat(service.messages(id, 0, 20).content()).hasSize(1);
    }

    @Test void globalAdminCanInspectDiagnosticsButCannotReadAnotherOwnersChat() throws Exception {
        UUID id = service.create(null).id();
        var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        auth.setDetails(new com.enterprise.mm.security.AccessScope.RequestScope(true));
        when(ai.copilotMessage(any())).thenReturn(result("agent_1", "COMPLETED"));
        assertThat(service.send(request(id, "agent_1")).path("plan").asText()).isEqualTo("secret");
        login(UUID.randomUUID());
        ((UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication())
            .setDetails(new com.enterprise.mm.security.AccessScope.RequestScope(true));
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
    }

    @Test void controllerReturnsWrappedProgressAndStorageErrorsWithoutCreatingAssistantMessages() throws Exception {
        UUID id = service.create(null).id();
        when(ai.copilotMessage(any())).thenReturn(result("agent_1", "QUEUED"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.enterprise.mm.controller.CopilotController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mm/ai/copilot/messages")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(request(id, "agent_1").toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("QUEUED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.runId").value("agent_1"));
        assertThat(service.messages(id, 0, 20).content()).hasSize(1);
        var unavailable = mock(CopilotChatService.class);
        when(unavailable.list(false, 0, 20)).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("offline"));
        var offlineMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.enterprise.mm.controller.CopilotController(unavailable))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        offlineMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mm/ai/copilot/conversations"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("COPILOT_STORAGE_UNAVAILABLE"));
    }

    @Test void genericDisplayAndMultilingualHelpersDoNotRewriteCopilotPayloads() throws Exception {
        var method = new org.springframework.core.MethodParameter(
            com.enterprise.mm.controller.CopilotController.class.getMethod("message", JsonNode.class), -1);
        var converter = org.springframework.http.converter.json.MappingJackson2HttpMessageConverter.class;
        assertThat(new com.enterprise.mm.web.LocalizedNameResponseAdvice(json).supports(method, converter)).isFalse();
        assertThat(new com.enterprise.mm.web.UserIdentityDisplayResponseAdvice(json,
            mock(com.enterprise.mm.web.UserIdentityDisplayEnricher.class)).supports(method, converter)).isFalse();
        assertThat(new com.enterprise.mm.web.ReferenceDisplayResponseAdvice(
            mock(com.enterprise.mm.web.ReferenceDisplayEnricher.class)).supports(method, converter)).isFalse();
        var parameter = new org.springframework.core.MethodParameter(
            com.enterprise.mm.controller.CopilotController.class.getMethod("message", JsonNode.class), 0);
        assertThat(new com.enterprise.mm.web.MultilingualNameRequestAdvice(json)
            .supports(parameter, JsonNode.class, converter)).isFalse();
    }

    @Test void cancellationReleasesConversationAndFiltersStaleClientHistory() throws Exception {
        UUID id = service.create("Chat").id();
        when(ai.copilotMessage(any())).thenReturn(result("agent_a", "RUNNING"), result("agent_b", "COMPLETED"));
        service.send(request(id, "agent_a"));
        assertThat(service.cancel(id, "agent_a").status()).isEqualTo("CANCELLED");
        assertThat(service.cancel(id, "agent_a").runId()).isEqualTo("agent_a");
        assertThat(service.pending(id)).isEmpty();
        assertThat(service.resume(id, "agent_a").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(service.messages(id, 0, 20).content()).singleElement().satisfies(row ->
            assertThat(row.status()).isEqualTo("CANCELLED"));
        ObjectNode next = request(id, "agent_b").put("message", "Question B");
        next.set("history", json.readTree("[{\"role\":\"user\",\"content\":\"Question\"},{\"role\":\"assistant\",\"content\":\"stale answer\"}]"));
        assertThat(service.send(next).path("status").asText()).isEqualTo("COMPLETED");
        var capture = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(ai, times(2)).copilotMessage(capture.capture());
        assertThat(capture.getAllValues().get(1).path("history").size()).isZero();
        assertThat(service.messages(id, 0, 20).content()).extracting(CopilotChatService.Message::status)
            .containsExactly("CANCELLED", "COMPLETED", "COMPLETED");
    }

    @Test void lateCompletionCannotResurrectCancelledAnswer() throws Exception {
        UUID id = service.create("Chat").id();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(ai.copilotMessage(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("AI was not released");
            return result("agent_a", "COMPLETED");
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var late = executor.submit(() -> {
                login(user);
                try { return service.send(request(id, "agent_a")); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(id, "agent_a").status()).isEqualTo("CANCELLED");
            release.countDown();
            assertThat(late.get(10, java.util.concurrent.TimeUnit.SECONDS).path("status").asText()).isEqualTo("CANCELLED");
        }
        assertThat(service.messages(id, 0, 20).content()).singleElement().satisfies(row ->
            assertThat(row.status()).isEqualTo("CANCELLED"));
    }

    @Test void cancelBeforeSubmitCreatesTerminalTombstoneAndCannotBeUsedAcrossScopes() throws Exception {
        UUID id = service.create("Chat").id();
        assertThat(service.cancel(id, "agent_a").status()).isEqualTo("CANCELLED");
        assertThat(service.send(request(id, "agent_a")).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(service.messages(id, 0, 20).content()).isEmpty();
        verifyNoInteractions(ai);
        login(UUID.randomUUID());
        assertThatThrownBy(() -> service.cancel(id, "agent_b")).isInstanceOf(NotFoundException.class);
        login(user); CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> service.cancel(id, "agent_b")).isInstanceOf(NotFoundException.class);
    }

    @Test void cancelRouteReturnsWrappedRunAndRejectsAlreadyCompletedRun() throws Exception {
        UUID id = service.create("Chat").id();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.enterprise.mm.controller.CopilotController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new com.enterprise.mm.error.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
            "/api/mm/ai/copilot/conversations/{id}/runs/{runId}/cancel", id, "agent_a")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.runId").value("agent_a"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("CANCELLED"));
        when(ai.copilotMessage(any())).thenReturn(result("agent_b", "COMPLETED"));
        service.send(request(id, "agent_b"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
            "/api/mm/ai/copilot/conversations/{id}/runs/{runId}/cancel", id, "agent_b")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
}
