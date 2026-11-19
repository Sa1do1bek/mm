package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class KimyosanoatEventWorker {
    private static final Logger log = LoggerFactory.getLogger(KimyosanoatEventWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KimyosanoatClient client;
    private final KimyosanoatIssueService issues;
    private final boolean enabled;

    public KimyosanoatEventWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
            KimyosanoatClient client, KimyosanoatIssueService issues,
            @Value("${app.kimyosanoat.webhook.auto-post-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.client = client;
        this.issues = issues;
        this.enabled = enabled;
    }

    private record Claimed(UUID eventId, String tenant, UUID company, long transferId,
            UUID warehouse1cId, int attempts, UUID leaseToken) { }

    @Scheduled(fixedDelayString = "${app.kimyosanoat.webhook.poll-delay-ms:10000}")
    public void dispatch() {
        if (!enabled) return;
        for (int i = 0; i < 10; i++) {
            try { if (!processNext()) return; }
            catch (RuntimeException ex) {
                log.warn("Kimyosanoat event dispatcher failed ({})", ex.getClass().getSimpleName());
                return;
            }
        }
    }

    boolean processNext() {
        Claimed event = tx.execute(status -> claim());
        if (event == null) return false;
        try {
            JsonNode source = client.detailBy1cId(event.warehouse1cId(), event.transferId());
            List<Long> warehouseIds = client.resolveWarehousesBy1cId(event.warehouse1cId());
            tx.executeWithoutResult(status -> withScope(event, () ->
                issues.autoApprove(event.eventId(), event.transferId(), event.warehouse1cId(), source, warehouseIds)));
        } catch (KimyosanoatAutoReviewException ex) {
            tx.executeWithoutResult(status -> finish(event, "REVIEW_REQUIRED", ex.reasonCode(), ex.getMessage(), null));
        } catch (KimyosanoatSourceHttpException ex) {
            if (ex.statusCode() == 403 || ex.statusCode() == 400
                    || (ex.statusCode() == 404 || ex.statusCode() == 410) && event.attempts() >= 3)
                tx.executeWithoutResult(status -> finish(event, "REVIEW_REQUIRED", "SOURCE_UNAVAILABLE",
                    ex.getMessage(), null));
            else retry(event, "SOURCE_UNAVAILABLE");
        } catch (BusinessException ex) {
            retry(event, "SOURCE_UNAVAILABLE");
        } catch (RuntimeException ex) {
            retry(event, "PROCESSING_FAILED");
            log.warn("Kimyosanoat event {} failed ({})", event.eventId(), ex.getClass().getSimpleName());
        }
        return true;
    }

    private Claimed claim() {
        List<Claimed> rows = jdbc.query("""
            select event_id, tenant_id, company_id, transfer_id, warehouse_1c_id, attempts
            from kimyosanoat_event_inbox
            where (processing_state in ('RECEIVED','RETRY') and next_attempt_at<=current_timestamp)
               or (processing_state='PROCESSING' and lease_until<current_timestamp)
            order by received_at, event_id limit 1 for update skip locked
            """, this::claimedRow);
        if (rows.isEmpty()) return null;
        Claimed row = rows.getFirst();
        UUID lease = UUID.randomUUID();
        jdbc.update("""
            update kimyosanoat_event_inbox
            set processing_state='PROCESSING', attempts=attempts+1, lease_token=?,
                lease_until=current_timestamp+interval '5 minutes', review_reason=null, review_detail=null
            where event_id=?
            """, lease, row.eventId());
        return new Claimed(row.eventId(), row.tenant(), row.company(), row.transferId(),
            row.warehouse1cId(), row.attempts()+1, lease);
    }

    private Claimed claimedRow(ResultSet rs, int row) throws SQLException {
        return new Claimed(rs.getObject("event_id", UUID.class), rs.getString("tenant_id"),
            rs.getObject("company_id", UUID.class), rs.getLong("transfer_id"),
            rs.getObject("warehouse_1c_id", UUID.class), rs.getInt("attempts"), null);
    }

    private void retry(Claimed event, String reason) {
        long seconds = Math.min(3600, 10L * (1L << Math.min(event.attempts(), 8)));
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                update kimyosanoat_event_inbox
                set processing_state='RETRY', next_attempt_at=?, review_reason=?,
                    review_detail=null, lease_token=null, lease_until=null
                where event_id=? and lease_token=? and processing_state='PROCESSING'
                """, OffsetDateTime.ofInstant(Instant.now().plus(Duration.ofSeconds(seconds)), ZoneOffset.UTC),
                reason, event.eventId(), event.leaseToken());
        });
    }

    private void finish(Claimed event, String state, String reason, String detail, UUID movementId) {
        jdbc.update("""
            update kimyosanoat_event_inbox
            set processing_state=?, review_reason=?, review_detail=?, movement_document_id=?,
                processed_at=current_timestamp, lease_token=null, lease_until=null
            where event_id=? and lease_token=? and processing_state='PROCESSING'
            """, state, reason, detail, movementId, event.eventId(), event.leaseToken());
    }

    private void withScope(Claimed event, Runnable action) {
        var previous = SecurityContextHolder.getContext();
        try {
            TenantContext.setTenant(event.tenant());
            CompanyContext.setCompany(event.company());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken("kimyosanoat-auto", "", List.of()));
            SecurityContextHolder.setContext(context);
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
            TenantContext.clear();
            CompanyContext.clear();
        }
    }
}
