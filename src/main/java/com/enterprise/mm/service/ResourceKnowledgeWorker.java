package com.enterprise.mm.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short leased transactions bracket HTTP and storage operations. Multiple ERP replicas share the queue. */
@Service
public class ResourceKnowledgeWorker {
    private static final Logger log = LoggerFactory.getLogger(ResourceKnowledgeWorker.class);
    private final JdbcTemplate db;
    private final ResourceKnowledgeClient ai;
    private final AttachmentService attachments;
    private final TransactionTemplate tx;
    private final String defaultLanguage;
    private final AtomicBoolean dispatchStarted = new AtomicBoolean();

    record Event(UUID id, String tenant, UUID company, UUID resource, UUID version, String kind,
                 int generation, long revision, String visibility, boolean submitted, int attempts,
                 LocalDateTime createdAt, UUID lease) {}

    public ResourceKnowledgeWorker(JdbcTemplate db, ResourceKnowledgeClient ai, AttachmentService attachments,
                                   PlatformTransactionManager manager,
                                   @Value("${app.resources.default-language:uz}") String defaultLanguage) {
        this.db = db;
        this.ai = ai;
        this.attachments = attachments;
        this.tx = new TransactionTemplate(manager);
        this.defaultLanguage = defaultLanguage == null || !defaultLanguage.matches("[a-z]{2,10}") ? "uz" : defaultLanguage;
    }

    @Scheduled(scheduler = "resourceKnowledgeScheduler", fixedDelayString = "${app.resources.worker-interval-ms:1000}")
    public void dispatch() {
        if (dispatchStarted.compareAndSet(false, true)) {
            if (ai.isConfigured()) log.info("Resource knowledge dispatcher started");
            else log.warn("Resource knowledge dispatcher is idle because AI is not configured");
        }
        if (!ai.isConfigured()) return;
        for (int count = 0; count < 10; count++) {
            try {
                if (!processNext()) return;
            } catch (Exception ex) {
                // Lease expiry recovers work even if the database fails during completion.
                log.warn("Resource knowledge dispatcher failed ({})", ex.getClass().getSimpleName());
                return;
            }
        }
    }

    boolean processNext() {
        Event event = tx.execute(s -> claim());
        if (event == null) return false;
        try {
            if ("VISIBILITY".equals(event.kind())) deliverVisibility(event);
            else deliverIndex(event);
        } catch (MmAiServiceException ex) {
            boolean transientFailure = ex.httpStatus() == 408 || ex.httpStatus() == 429 || ex.httpStatus() >= 500;
            fail(event, ex.errorCode(), transientFailure);
        } catch (Exception ex) {
            fail(event, "RESOURCE_SOURCE_UNAVAILABLE", false);
            log.warn("Resource knowledge event {} failed ({})", event.id(), ex.getClass().getSimpleName());
        }
        return true;
    }

    private Event claim() {
        List<Event> rows = db.query("""
            SELECT * FROM resource_knowledge_events
            WHERE status IN ('PENDING','WAITING') AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
            ORDER BY CASE WHEN kind='VISIBILITY' THEN 0 ELSE 1 END,next_attempt_at,created_at,id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, this::eventRow);
        if (rows.isEmpty()) return null;
        Event event = rows.getFirst();
        UUID lease = UUID.randomUUID();
        db.update("UPDATE resource_knowledge_events SET lease_token=?,lease_until=? WHERE id=?",
            lease, now().plusSeconds(90), event.id());
        return new Event(event.id(), event.tenant(), event.company(), event.resource(), event.version(),
            event.kind(), event.generation(), event.revision(), event.visibility(), event.submitted(),
            event.attempts(), event.createdAt(), lease);
    }

    private void deliverVisibility(Event event) {
        JsonNode result = ai.visibility(event.tenant(), event.company(), event.version(),
            new ResourceKnowledgeClient.Visibility(event.resource(), event.revision(), event.visibility()));
        JsonNode responseRevision = result == null ? null : result.has("knowledgeRevision")
            ? result.get("knowledgeRevision") : result.path("revision");
        String responseState = result == null ? "" : result.has("state")
            ? result.path("state").asText() : result.path("effectiveState").asText();
        if (result == null || !event.version().toString().equals(result.path("versionId").asText())
            || responseRevision == null || !responseRevision.isIntegralNumber()
            || responseRevision.asLong(-1) < event.revision()
            || !Set.of("CURRENT", "HISTORICAL", "BLOCKED").contains(responseState)
            || responseRevision.asLong() == event.revision() && !event.visibility().equals(responseState)) {
            throw invalidResponse();
        }
        complete(event, "DONE", null, false);
    }

    private void deliverIndex(Event event) {
        List<ResourceKnowledgeClient.IndexMetadata> rows = db.query("""
            SELECT v.* FROM resource_versions v JOIN resources r ON r.id=v.resource_id
            WHERE v.id=? AND v.tenant_id=? AND v.company_id=? AND v.index_generation=?
              AND r.status='PUBLISHED' AND v.status IN ('PUBLISHED','HISTORICAL')
            """, (rs, n) -> new ResourceKnowledgeClient.IndexMetadata(event.id(), event.tenant(), event.company(),
                event.resource(), event.version(), rs.getString("title"), Integer.toString(rs.getInt("version_no")),
                rs.getString("original_file_name"), rs.getString("content_type"), rs.getString("sha256"), defaultLanguage,
                event.revision(), event.generation()), event.version(), event.tenant(), event.company(), event.generation());
        if (rows.isEmpty()) {
            complete(event, "SUPERSEDED", null, false);
            return;
        }
        JsonNode result;
        try {
            result = event.submitted() ? ai.status(event.tenant(), event.company(), event.id())
                : ai.index(rows.getFirst(), attachments.readResourceBytes(event.tenant(), event.company(), event.version()));
        } catch (MmAiServiceException ex) {
            if (event.submitted() && ex.httpStatus() == 404) {
                // Lost AI state is restored using the original immutable job identity.
                complete(event, "PENDING", null, false);
                return;
            }
            throw ex;
        }
        requireIdentity(event, result);
        String status = result.path("status").asText();
        switch (status) {
            case "READY" -> complete(event, "DONE", null, true);
            case "FAILED" -> fail(event, safeCode(result.path("errorCode").asText(), "RESOURCE_INDEX_FAILED"), false);
            case "QUEUED", "RUNNING" -> complete(event, "WAITING", null, true);
            default -> throw invalidResponse();
        }
    }

    private void requireIdentity(Event event, JsonNode result) {
        if (result == null || !event.id().toString().equals(result.path("jobId").asText())
            || !event.tenant().equals(result.path("tenantId").asText())
            || !event.company().toString().equals(result.path("companyId").asText())
            || !event.resource().toString().equals(result.path("resourceId").asText())
            || !event.version().toString().equals(result.path("versionId").asText())
            || !result.path("indexGeneration").isIntegralNumber()
            || result.path("indexGeneration").asInt(-1) != event.generation()) throw invalidResponse();
    }

    private void complete(Event event, String status, String error, boolean submitted) {
        tx.executeWithoutResult(s -> {
            if (!ownsLease(event)) return;
            db.update("""
                UPDATE resource_knowledge_events SET status=?,error_code=?,submitted=?,attempts=0,
                    lease_token=NULL,lease_until=NULL,next_attempt_at=? WHERE id=?
                """, status, error, submitted, now().plusSeconds(3), event.id());
            if ("INDEX".equals(event.kind()) && !"SUPERSEDED".equals(status)) {
                db.update("UPDATE resource_versions SET index_status='RUNNING',index_error_code=NULL WHERE id=? AND index_generation=?",
                    event.version(), event.generation());
            }
            promoteReady(event.version());
        });
    }

    private void promoteReady(UUID version) {
        db.update("""
            UPDATE resource_versions SET index_status='READY',index_error_code=NULL
            WHERE id=? AND EXISTS (
                SELECT 1 FROM resource_knowledge_events e WHERE e.version_id=resource_versions.id
                  AND e.kind='INDEX' AND e.index_generation=resource_versions.index_generation AND e.status='DONE')
              AND NOT EXISTS (SELECT 1 FROM resource_knowledge_events e WHERE e.version_id=resource_versions.id
                  AND e.kind='VISIBILITY' AND e.status NOT IN ('DONE','SUPERSEDED'))
            """, version);
    }

    private void fail(Event event, String code, boolean retryable) {
        tx.executeWithoutResult(s -> {
            if (!ownsLease(event)) return;
            int attempts = event.attempts() + 1;
            boolean retry = retryable && attempts < 6;
            String safe = safeCode(code, "RESOURCE_AI_FAILED");
            db.update("""
                UPDATE resource_knowledge_events SET status=?,error_code=?,attempts=?,
                    lease_token=NULL,lease_until=NULL,next_attempt_at=? WHERE id=?
                """, retry ? "WAITING" : "FAILED", safe, attempts,
                now().plusSeconds(Math.min(300, 5L << Math.min(attempts - 1, 6))), event.id());
            db.update("""
                UPDATE resource_versions SET index_status=?,index_error_code=? WHERE id=? AND index_generation=?
                """, retry ? "PENDING" : "FAILED", safe, event.version(), event.generation());
        });
    }

    private boolean ownsLease(Event event) {
        return !db.query("""
            SELECT id FROM resource_knowledge_events WHERE id=? AND lease_token=?
              AND status IN ('PENDING','WAITING') FOR UPDATE
            """, (rs, n) -> rs.getObject(1, UUID.class), event.id(), event.lease()).isEmpty();
    }

    private LocalDateTime now() {
        return db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toLocalDateTime();
    }

    private static String safeCode(String value, String fallback) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,99}") ? value : fallback;
    }

    private static MmAiServiceException invalidResponse() {
        return new MmAiServiceException(502, "RESOURCE_AI_INVALID_RESPONSE", "Resource AI returned an invalid response", null);
    }

    private Event eventRow(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getObject("company_id", UUID.class),
            rs.getObject("resource_id", UUID.class), rs.getObject("version_id", UUID.class), rs.getString("kind"),
            rs.getInt("index_generation"), rs.getLong("knowledge_revision"), rs.getString("visibility_state"),
            rs.getBoolean("submitted"), rs.getInt("attempts"), rs.getTimestamp("created_at").toLocalDateTime(), null);
    }
}
