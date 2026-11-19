package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.AuthenticatedTokenState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database transactions bracket upstream calls; no database lock is held over HTTP. */
@Service
public class CopilotChatService {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW");
    private static final Set<String> REQUEST_FIELDS = Set.of("message", "locale", "conversationId", "context", "history",
        "maxSteps", "includeEvidence", "executionMode", "runId", "knowledgeScope");
    private static final Set<String> DIAGNOSTICS = Set.of("runId", "durationMs", "agentsUsed", "llmUsed", "plan", "evidence");
    private final JdbcTemplate db;
    private final MmAiClient ai;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public CopilotChatService(JdbcTemplate db, MmAiClient ai, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db; this.ai = ai; this.json = json; this.tx = new TransactionTemplate(manager);
    }

    public record Conversation(UUID id, String title, boolean archived, String createdAt, String updatedAt) {}
    public record Message(UUID id, long sequence, String role, String content, String runId, String status,
                          JsonNode response, String createdAt) {}
    public record Page<T>(List<T> content, int page, int size, long totalElements) {}
    public record PendingRun(String runId, String status, JsonNode request) {}
    public record CancelledRun(String runId, String status) {}

    public Conversation create(String title) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO copilot_conversations(id,owner_id,tenant_id,company_id,title) VALUES (?,?,?,?,?)",
            id, owner(), TenantContext.currentTenant(), CompanyContext.currentCompany(), title(title));
        return conversation(id, false);
    }

    public Page<Conversation> list(boolean archived, int page, int size) {
        pagination(page, size);
        Object[] scope = {owner(), TenantContext.currentTenant(), CompanyContext.currentCompany(), archived};
        String where = " FROM copilot_conversations WHERE owner_id=? AND tenant_id=? AND company_id=? AND archived=?";
        Long count = db.queryForObject("SELECT COUNT(*)" + where, Long.class, scope);
        List<Conversation> rows = db.query("SELECT *" + where + " ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",
            this::conversationRow, scope[0], scope[1], scope[2], archived, size, (long) page * size);
        return new Page<>(rows, page, size, count == null ? 0 : count);
    }

    public Conversation get(UUID id) { return conversation(id, false); }

    public Conversation update(UUID id, String title, Boolean archived) {
        return tx.execute(s -> {
            Conversation old = conversation(id, true);
            db.update("UPDATE copilot_conversations SET title=?,archived=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                title == null ? old.title() : title(title), archived == null ? old.archived() : archived, id);
            return conversation(id, false);
        });
    }

    public Page<Message> messages(UUID id, int page, int size) {
        conversation(id, false); pagination(page, size);
        String from = " FROM copilot_messages m JOIN copilot_runs r ON r.run_id=m.run_id"
            + " WHERE m.conversation_id=? AND NOT (r.status='CANCELLED' AND m.role='assistant')";
        Long count = db.queryForObject("SELECT COUNT(*)" + from, Long.class, id);
        List<Message> rows = db.query("SELECT m.*,r.request_json" + from + " ORDER BY m.sequence_no LIMIT ? OFFSET ?",
            (rs, n) -> {
                JsonNode response = parse(rs.getString("response_json"));
                JsonNode visible = response == null ? null : visibleForRequest(response, parse(rs.getString("request_json")));
                String content = "assistant".equals(rs.getString("role")) && visible != null
                    ? visible.path("answer").asText("") : rs.getString("content");
                return new Message(rs.getObject("id", UUID.class), rs.getLong("sequence_no"), rs.getString("role"),
                    content, rs.getString("run_id"), visible == null ? rs.getString("status") : visible.path("status").asText(),
                    visible, rs.getString("created_at"));
            }, id, size, (long) page * size);
        return new Page<>(rows, page, size, count == null ? 0 : count);
    }

    public List<PendingRun> pending(UUID id) {
        conversation(id, false);
        return db.query("SELECT * FROM copilot_runs WHERE conversation_id=? AND status IN ('QUEUED','RUNNING') ORDER BY created_at",
            (rs, n) -> new PendingRun(rs.getString("run_id"), rs.getString("status"), parse(rs.getString("request_json"))), id);
    }

    public JsonNode resume(UUID id, String runId) {
        conversation(id, false);
        List<JsonNode> requests = db.query("SELECT request_json,response_json,status FROM copilot_runs WHERE conversation_id=? AND run_id=?",
            (rs, n) -> "CANCELLED".equals(rs.getString("status")) ? parse(rs.getString("response_json")) : parse(rs.getString("request_json")), id, runId);
        if (requests.isEmpty()) throw new NotFoundException("Copilot run not found");
        if ("CANCELLED".equals(requests.getFirst().path("status").asText())) return visible(requests.getFirst());
        return sendInternal(requests.getFirst(), true);
    }

    public CancelledRun cancel(UUID id, String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,100}")) throw new BadRequestException("A valid runId is required");
        return tx.execute(s -> {
            conversation(id, true);
            List<String> statuses = db.query("SELECT status FROM copilot_runs WHERE conversation_id=? AND run_id=?",
                (rs, n) -> rs.getString(1), id, runId);
            if (!statuses.isEmpty() && !ACTIVE.contains(statuses.getFirst()) && !"CANCELLED".equals(statuses.getFirst()))
                throw new ConflictException("A completed copilot run cannot be cancelled");
            if (statuses.isEmpty()) {
                // A cancellation can arrive before the initial submit. The tombstone
                // makes that same runId terminal even if the submit arrives later.
                db.update("INSERT INTO copilot_runs(run_id,conversation_id,request_json,response_json,status) VALUES (?,?,?,?, 'CANCELLED')",
                    runId, id, "{}", cancelledResponse(runId).toString());
            } else if (ACTIVE.contains(statuses.getFirst())) {
                db.update("UPDATE copilot_runs SET status='CANCELLED',response_json=? WHERE conversation_id=? AND run_id=?",
                    cancelledResponse(runId).toString(), id, runId);
                db.update("UPDATE copilot_messages SET status='CANCELLED',response_json=NULL WHERE conversation_id=? AND run_id=? AND role='user'",
                    id, runId);
            }
            db.update("UPDATE copilot_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            return new CancelledRun(runId, "CANCELLED");
        });
    }

    public JsonNode send(JsonNode input) {
        return sendInternal(input, false);
    }

    private JsonNode sendInternal(JsonNode input, boolean savedRequest) {
        ObjectNode request = validate(input, savedRequest);
        UUID conversationId = UUID.fromString(request.path("conversationId").asText());
        String runId = request.path("runId").asText();
        JsonNode cached;
        try {
            cached = tx.execute(s -> prepare(conversationId, runId, request));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Run identity is already in use; resume the original run");
        }
        if (cached != null) return visibleForRequest(cached, request);
        JsonNode result = execute(request);
        if (result == null || !result.isObject() || !runId.equals(result.path("runId").asText())
            || !(ACTIVE.contains(result.path("status").asText()) || TERMINAL.contains(result.path("status").asText()))) {
            throw new MmAiServiceException(502, "COPILOT_INVALID_RESPONSE", "AI returned an invalid run response", null);
        }
        JsonNode checked = result.has("citations") || result.path("answerMode").asText().startsWith("RESOURCE_")
            ? visibleForRequest(result, request) : result;
        JsonNode saved = tx.execute(s -> {
            conversation(conversationId, true);
            JsonNode existing = storedResponse(runId);
            if (existing != null && TERMINAL.contains(existing.path("status").asText())) return existing;
            String status = checked.path("status").asText();
            JsonNode stored = "CANCELLED".equals(status) ? cancelledResponse(runId) : checked;
            db.update("UPDATE copilot_runs SET status=?,response_json=? WHERE run_id=?", status, stored.toString(), runId);
            db.update("UPDATE copilot_messages SET status=? WHERE run_id=? AND role='user'", status, runId);
            if (TERMINAL.contains(status) && !"CANCELLED".equals(status)) {
                insertMessage(conversationId, runId, "assistant", checked.path("answer").asText(""), status, checked);
            }
            db.update("UPDATE copilot_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", conversationId);
            return stored;
        });
        return visibleForRequest(saved, request);
    }

    private JsonNode execute(ObjectNode request) {
        try {
            return ai.copilotMessage(request);
        } catch (MmAiServiceException ex) {
            // These upstream responses definitively reject the saved request. Keep the
            // rejection in the same terminal/idempotent persistence path as an AI result.
            // Authorization, conflicts and uncertain transport failures remain resumable.
            if (ex.httpStatus() != 400 && ex.httpStatus() != 422) throw ex;
            String language = Locale.forLanguageTag(request.path("locale").asText("uz")).getLanguage();
            String answer = switch (language) {
                case "uz" -> "Yordamchi bu savolni qayta ishlay olmadi. Savolni aniqlashtirib, qayta yuboring.";
                case "ru" -> "Помощник не смог обработать этот вопрос. Уточните вопрос и отправьте его снова.";
                default -> "The assistant could not process this question. Please revise it and try again.";
            };
            ObjectNode failed = json.createObjectNode().put("runId", request.path("runId").asText())
                .put("status", "FAILED").put("answer", answer);
            failed.putArray("cards");
            failed.putArray("actions");
            failed.putArray("warnings").add("COPILOT_REQUEST_REJECTED");
            ObjectNode diagnostic = failed.putArray("evidence").addObject()
                .put("tool", "copilot").put("status", "FAILED").put("upstreamHttpStatus", ex.httpStatus())
                .put("errorCode", ex.errorCode()).put("message", ex.getMessage());
            if (ex.upstreamRequestId() != null) diagnostic.put("upstreamRequestId", ex.upstreamRequestId());
            return failed;
        }
    }

    private JsonNode prepare(UUID id, String runId, ObjectNode request) {
        Conversation c = conversation(id, true);
        List<JsonNode> existing = db.query("SELECT request_json FROM copilot_runs WHERE run_id=? AND conversation_id=?",
            (rs, n) -> parse(rs.getString(1)), runId, id);
        if (!existing.isEmpty()) {
            JsonNode response = storedResponse(runId);
            if (response != null && "CANCELLED".equals(response.path("status").asText())) return response;
            ObjectNode before = (ObjectNode) existing.getFirst().deepCopy();
            ObjectNode after = request.deepCopy();
            before.remove(List.of("includeEvidence", "history", "executionMode"));
            after.remove(List.of("includeEvidence", "history", "executionMode"));
            // The browser never controls revisions. Polls keep the initial server snapshot.
            if (before.path("knowledgeScope").isObject()) ((ObjectNode) before.path("knowledgeScope")).remove("knowledgeRevision");
            if (after.path("knowledgeScope").isObject()) ((ObjectNode) after.path("knowledgeScope")).remove("knowledgeRevision");
            if (!before.equals(after)) throw new ConflictException("Run request cannot change; use a new runId");
            JsonNode evidencePreference = request.get("includeEvidence");
            // The stored canonical history is authoritative; only the evidence
            // display preference may vary between retries and polls.
            request.removeAll(); request.setAll((ObjectNode) existing.getFirst());
            if (evidencePreference != null) request.set("includeEvidence", evidencePreference);
            return response != null && TERMINAL.contains(response.path("status").asText()) ? response : null;
        }
        if (c.archived()) throw new ConflictException("Restore the conversation before sending a new message");
        Long active = db.queryForObject("SELECT COUNT(*) FROM copilot_runs WHERE conversation_id=? AND status IN ('QUEUED','RUNNING')", Long.class, id);
        if (active != null && active > 0) throw new ConflictException("Resume the pending run before sending another message");
        request.set("history", canonicalHistory(id));
        db.update("INSERT INTO copilot_runs(run_id,conversation_id,request_json,status) VALUES (?,?,?,'QUEUED')", runId, id, request.toString());
        insertMessage(id, runId, "user", request.path("message").asText(), "QUEUED", null);
        db.update("UPDATE copilot_conversations SET updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return null;
    }

    private void insertMessage(UUID id, String run, String role, String content, String status, JsonNode response) {
        Long sequence = db.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM copilot_messages WHERE conversation_id=?", Long.class, id);
        db.update("INSERT INTO copilot_messages(id,conversation_id,sequence_no,role,content,run_id,status,response_json) VALUES (?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), id, sequence, role, content, run, status, response == null ? null : response.toString());
    }

    private JsonNode storedResponse(String run) {
        return db.query("SELECT response_json FROM copilot_runs WHERE run_id=?", (rs, n) -> parse(rs.getString(1)), run).getFirst();
    }

    private ArrayNode canonicalHistory(UUID id) {
        List<JsonNode> rows = db.query("""
            SELECT m.role,m.content FROM copilot_messages m JOIN copilot_runs r ON r.run_id=m.run_id
            WHERE m.conversation_id=? AND r.status IN ('COMPLETED','FAILED','NEEDS_REVIEW')
            ORDER BY m.sequence_no DESC LIMIT 20
            """, (rs, n) -> json.createObjectNode().put("role", rs.getString(1))
                .put("content", rs.getString(2).substring(0, Math.min(4000, rs.getString(2).length()))), id);
        Collections.reverse(rows);
        ArrayNode history = json.createArrayNode();
        rows.forEach(history::add);
        return history;
    }

    private ObjectNode cancelledResponse(String runId) {
        ObjectNode response = json.createObjectNode().put("runId", runId).put("status", "CANCELLED").put("answer", "");
        response.putArray("cards"); response.putArray("actions");
        return response;
    }

    private Conversation conversation(UUID id, boolean lock) {
        List<Conversation> rows = db.query("SELECT * FROM copilot_conversations WHERE id=? AND owner_id=? AND tenant_id=? AND company_id=?" + (lock ? " FOR UPDATE" : ""),
            this::conversationRow, id, owner(), TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (rows.isEmpty()) throw new NotFoundException("Copilot conversation not found");
        return rows.getFirst();
    }

    private Conversation conversationRow(ResultSet rs, int n) throws SQLException {
        return new Conversation(rs.getObject("id", UUID.class), rs.getString("title"), rs.getBoolean("archived"),
            rs.getString("created_at"), rs.getString("updated_at"));
    }

    private UUID owner() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getCredentials() instanceof AuthenticatedTokenState state))
            throw new AccessDeniedException("Authenticated ERP user required");
        return state.userId();
    }

    private ObjectNode validate(JsonNode input, boolean savedRequest) {
        if (input == null || !input.isObject()) throw new BadRequestException("A copilot request object is required");
        input.fieldNames().forEachRemaining(k -> { if (!REQUEST_FIELDS.contains(k)) throw new BadRequestException("Unsupported copilot request field: " + k); });
        ObjectNode request = (ObjectNode) input.deepCopy();
        String message = request.path("message").asText("");
        if (!request.path("message").isTextual() || message.isBlank() || message.length() > 20000) throw new BadRequestException("message must contain 1 to 20000 characters");
        try { UUID.fromString(request.path("conversationId").asText()); } catch (IllegalArgumentException e) { throw new BadRequestException("A server-created conversationId is required"); }
        if (request.has("executionMode") && !Set.of("poll", "sync").contains(request.path("executionMode").asText())) throw new BadRequestException("Invalid executionMode");
        if (!request.has("runId") && !"poll".equals(request.path("executionMode").asText())) request.put("runId", "agent_" + UUID.randomUUID());
        if (!request.path("runId").asText().matches("[A-Za-z0-9_-]{1,100}")) throw new BadRequestException("A valid runId is required");
        if (request.has("includeEvidence") && !request.get("includeEvidence").isBoolean()) throw new BadRequestException("includeEvidence must be boolean");
        if (request.has("maxSteps") && (!request.get("maxSteps").isIntegralNumber() || !request.get("maxSteps").canConvertToInt() || request.get("maxSteps").asInt() < 1 || request.get("maxSteps").asInt() > 100)) throw new BadRequestException("maxSteps must be between 1 and 100");
        if (request.has("locale") && (!request.get("locale").isTextual() || request.get("locale").asText().length() > 20)) throw new BadRequestException("Invalid locale");
        if (request.has("context") && !request.get("context").isObject()) throw new BadRequestException("context must be an object");
        ObjectNode context = request.has("context") ? (ObjectNode) request.get("context") : json.createObjectNode();
        context.fieldNames().forEachRemaining(k -> { if (!Set.of("screen", "companyId", "path").contains(k)) throw new BadRequestException("Unsupported context field: " + k); });
        for (String key : List.of("screen", "path")) {
            if (context.has(key) && (!context.get(key).isTextual() || context.get(key).asText().length() > 2000))
                throw new BadRequestException("Invalid context " + key);
        }
        if (context.hasNonNull("companyId") && !CompanyContext.currentCompany().toString().equals(context.path("companyId").asText())) throw new AccessDeniedException("Company mismatch");
        context.put("companyId", CompanyContext.currentCompany().toString()); request.set("context", context);
        if (request.has("knowledgeScope")) {
            JsonNode rawScope = request.get("knowledgeScope");
            if (!rawScope.isObject()) throw new BadRequestException("knowledgeScope must be an object");
            rawScope.fieldNames().forEachRemaining(key -> {
                if (!Set.of("mode", "resourceId", "versionId", "knowledgeRevision").contains(key)
                    || (!savedRequest && "knowledgeRevision".equals(key)))
                    throw new BadRequestException("Unsupported knowledgeScope field: " + key);
            });
            String mode = rawScope.path("mode").asText("");
            if (!Set.of("AUTO", "RESOURCES", "RESOURCE").contains(mode)) throw new BadRequestException("Invalid knowledgeScope mode");
            if (!"RESOURCE".equals(mode) && (rawScope.hasNonNull("resourceId") || rawScope.hasNonNull("versionId")))
                throw new BadRequestException("Resource IDs require RESOURCE mode");
            ObjectNode scope = json.createObjectNode().put("mode", mode);
            if ("RESOURCE".equals(mode)) {
                UUID resourceId;
                UUID versionId = null;
                try {
                    resourceId = UUID.fromString(rawScope.path("resourceId").asText());
                    if (rawScope.hasNonNull("versionId")) versionId = UUID.fromString(rawScope.path("versionId").asText());
                } catch (IllegalArgumentException ex) { throw new BadRequestException("Invalid resource scope ID"); }
                Integer ready = db.queryForObject("""
                    SELECT count(*) FROM resources r JOIN resource_versions v ON v.resource_id=r.id
                    WHERE r.id=? AND r.tenant_id=? AND r.company_id=? AND r.status='PUBLISHED'
                      AND v.id=COALESCE(?,r.current_version_id) AND v.tenant_id=? AND v.company_id=?
                      AND v.status IN ('PUBLISHED','HISTORICAL') AND v.index_status='READY'
                    """, Integer.class, resourceId, TenantContext.currentTenant(), CompanyContext.currentCompany(),
                    versionId, TenantContext.currentTenant(), CompanyContext.currentCompany());
                if (ready == null || ready == 0) throw new NotFoundException("Resource is not available for AI questions");
                scope.put("resourceId", resourceId.toString());
                if (versionId != null) scope.put("versionId", versionId.toString());
            }
            Long revision = db.query("""
                SELECT revision FROM resource_knowledge_revisions WHERE tenant_id=? AND company_id=?
                """, rs -> rs.next() ? rs.getLong(1) : 0L,
                TenantContext.currentTenant(), CompanyContext.currentCompany());
            scope.put("knowledgeRevision", savedRequest ? rawScope.path("knowledgeRevision").asLong(revision) : revision);
            request.set("knowledgeScope", scope);
        }
        if (request.has("history")) {
            JsonNode history = request.get("history");
            if (!history.isArray() || history.size() > 20) throw new BadRequestException("history must contain at most 20 messages");
            for (JsonNode item : history) {
                if (!item.isObject() || item.size() != 2 || !Set.of("user", "assistant").contains(item.path("role").asText())
                    || !item.path("content").isTextual() || item.path("content").asText().length() > 4000) throw new BadRequestException("Invalid text history entry");
            }
        }
        if (request.toString().length() > 150000) throw new BadRequestException("Copilot request is too large");
        return request;
    }

    private JsonNode visible(JsonNode response) {
        if (response == null) return null;
        ObjectNode copy = (ObjectNode) response.deepCopy();
        JsonNode citations = copy.get("citations");
        boolean documentAnswer = copy.path("answerMode").asText().startsWith("RESOURCE_")
            || citations != null && citations.isArray() && !citations.isEmpty();
        if (documentAnswer && TERMINAL.contains(copy.path("status").asText()) && "COMPLETED".equals(copy.path("status").asText())) {
            Long revision = db.query("SELECT revision FROM resource_knowledge_revisions WHERE tenant_id=? AND company_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, TenantContext.currentTenant(), CompanyContext.currentCompany());
            if (!copy.path("knowledgeRevision").isIntegralNumber() || copy.path("knowledgeRevision").asLong(-1) != revision)
                return unavailableResource(copy, "RESOURCE_REVISION_CHANGED");
            if ("RESOURCE_DOCUMENT".equals(copy.path("answerMode").asText()) && (citations == null || citations.isEmpty()))
                return unavailableResource(copy, "RESOURCE_CITATION_UNAVAILABLE");
        }
        if (citations != null) {
            boolean invalid = !citations.isArray() || citations.size() > 50;
            ArrayNode checked = json.createArrayNode();
            if (!invalid) for (JsonNode citation : citations) {
                try {
                    if (!citation.isObject()) { invalid = true; break; }
                    UUID resourceId = UUID.fromString(citation.path("resourceId").asText());
                    UUID versionId = UUID.fromString(citation.path("versionId").asText());
                    JsonNode locator = citation.path("locator");
                    if (!validResourceLocator(locator)) {
                        invalid = true; break;
                    }
                    if ("PDF_PAGE".equals(locator.path("kind").asText()) && locator.path("page").asInt(0) < 1) {
                        invalid = true; break;
                    }
                    Integer count = db.queryForObject("""
                        SELECT count(*) FROM resources r JOIN resource_versions v ON v.resource_id=r.id
                        WHERE r.id=? AND v.id=? AND r.tenant_id=? AND r.company_id=?
                          AND v.tenant_id=? AND v.company_id=? AND r.status='PUBLISHED'
                          AND v.status IN ('PUBLISHED','HISTORICAL') AND v.index_status='READY'
                        """, Integer.class, resourceId, versionId, TenantContext.currentTenant(),
                        CompanyContext.currentCompany(), TenantContext.currentTenant(), CompanyContext.currentCompany());
                    if (count == null || count == 0) { invalid = true; break; }
                    ObjectNode safe = json.createObjectNode();
                    safe.put("resourceId", resourceId.toString());
                    safe.put("versionId", versionId.toString());
                    if (citation.path("sectionId").isTextual()) safe.put("sectionId", citation.path("sectionId").asText().substring(0, Math.min(100, citation.path("sectionId").asText().length())));
                    safe.set("locator", locator.deepCopy());
                    if (citation.path("excerpt").isTextual()) safe.put("excerpt", citation.path("excerpt").asText().substring(0, Math.min(4000, citation.path("excerpt").asText().length())));
                    safe.put("historical", citation.path("historical").asBoolean(false));
                    safe.put("previewPath", "/api/resources/" + resourceId + "/versions/" + versionId + "/preview");
                    checked.add(safe);
                } catch (IllegalArgumentException ex) { invalid = true; break; }
            }
            if (invalid) {
                return unavailableResource(copy, "RESOURCE_CITATION_UNAVAILABLE");
            } else copy.set("citations", checked);
        }
        if (!AccessScope.globalAdmin()) {
            // runId remains transport metadata required for polling; all other diagnostics are privileged.
            for (String key : DIAGNOSTICS) if (!key.equals("runId")) copy.remove(key);
        }
        return copy;
    }

    private JsonNode visibleForRequest(JsonNode response, JsonNode request) {
        ObjectNode result = (ObjectNode) visible(response);
        JsonNode scope = request.path("knowledgeScope");
        if ("RESOURCE".equals(scope.path("mode").asText())) {
            for (JsonNode citation : result.path("citations")) {
                if (!scope.path("resourceId").asText().equals(citation.path("resourceId").asText())
                    || scope.hasNonNull("versionId") && !scope.path("versionId").asText().equals(citation.path("versionId").asText()))
                    return unavailableResource(result, "RESOURCE_CITATION_UNAVAILABLE");
            }
        }
        if (request.has("includeEvidence") && !request.path("includeEvidence").asBoolean()) result.remove("evidence");
        return result;
    }

    /** AI verifies source grounding. ERP bounds locator data and builds its own authorized links. */
    private boolean validResourceLocator(JsonNode locator) {
        if (!locator.isObject() || locator.size() > 12 || !locator.path("kind").asText().matches("[A-Z][A-Z0-9_]{0,49}")) return false;
        var fields = locator.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode value = field.getValue();
            if (!field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,39}")) return false;
            if (value.isTextual()) {
                if (value.asText().length() > 200 || value.asText().chars().anyMatch(Character::isISOControl)) return false;
            } else if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0 || value.asInt() > 1_000_000) return false;
        }
        return !"PDF_PAGE".equals(locator.path("kind").asText())
            || locator.path("page").isIntegralNumber() && locator.path("page").asInt() > 0;
    }

    private ObjectNode unavailableResource(ObjectNode response, String code) {
        response.put("status", "FAILED");
        response.put("answer", "Document sources have changed or are unavailable. Please ask again after synchronization completes.");
        response.putArray("warnings").add(code);
        for (String field : List.of("citations", "cards", "actions", "evidence", "plan")) response.putArray(field);
        if (!AccessScope.globalAdmin()) for (String field : DIAGNOSTICS) if (!"runId".equals(field)) response.remove(field);
        return response;
    }

    private JsonNode parse(String text) {
        if (text == null) return null;
        try { return json.readTree(text); } catch (Exception e) { throw new IllegalStateException("Invalid stored copilot JSON", e); }
    }
    private String title(String title) {
        String value = title == null ? "New chat" : title.trim();
        if (value.isEmpty() || value.length() > 200) throw new BadRequestException("Title must contain 1 to 200 characters");
        return value;
    }
    private void pagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be non-negative and size between 1 and 100");
    }
}
