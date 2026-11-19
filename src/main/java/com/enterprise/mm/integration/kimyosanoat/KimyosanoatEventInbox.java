package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KimyosanoatEventInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String tenantId;
    private final String companyId;

    public KimyosanoatEventInbox(JdbcTemplate jdbc, ObjectMapper json,
            @Value("${app.kimyosanoat.webhook.tenant-id:}") String tenantId,
            @Value("${app.kimyosanoat.webhook.company-id:}") String companyId) {
        this.jdbc = jdbc;
        this.json = json;
        this.tenantId = tenantId;
        this.companyId = companyId;
    }

    public record Event(UUID eventId, Long transferId, UUID warehouse1cId, String eventType,
            OffsetDateTime occurredAt) { }
    public record Receipt(UUID eventId, String state, boolean duplicate) { }
    public record ReviewItem(UUID eventId, long transferId, UUID warehouse1cId, String state,
            String reason, String detail, int attempts, OffsetDateTime receivedAt) { }

    @Transactional(readOnly = true)
    public List<ReviewItem> reviewQueue() {
        return jdbc.query("""
            select event_id, transfer_id, warehouse_1c_id, processing_state, review_reason,
                   review_detail, attempts, received_at
            from kimyosanoat_event_inbox
            where tenant_id=? and company_id=? and processing_state in ('REVIEW_REQUIRED','RETRY')
            order by received_at desc, event_id limit 100
            """, (rs, row) -> new ReviewItem(rs.getObject(1, UUID.class), rs.getLong(2),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), rs.getObject(8, OffsetDateTime.class)),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    @Transactional
    public void retry(UUID eventId) {
        int updated = jdbc.update("""
            update kimyosanoat_event_inbox
            set processing_state='RECEIVED', next_attempt_at=current_timestamp,
                review_reason=null, review_detail=null, processed_at=null,
                lease_token=null, lease_until=null
            where event_id=? and tenant_id=? and company_id=?
              and processing_state in ('REVIEW_REQUIRED','RETRY')
            """, eventId, TenantContext.currentTenant(), CompanyContext.currentCompany());
        if (updated != 1) throw new ConflictException("Event is not available for retry in this company");
    }

    @Transactional
    public Receipt accept(Event event) {
        if (event == null || event.eventId() == null || event.transferId() == null || event.transferId() <= 0
                || event.warehouse1cId() == null || event.occurredAt() == null
                || !"EXIT_ACCEPTED".equals(event.eventType()))
            throw new BadRequestException("eventId, positive transferId, warehouse1cId, EXIT_ACCEPTED eventType, and occurredAt are required");
        String payload;
        try { payload = json.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize Kimyosanoat event", ex); }
        String hash = sha256(payload);
        int inserted = jdbc.update("""
            insert into kimyosanoat_event_inbox
                (event_id, tenant_id, company_id, transfer_id, warehouse_1c_id, event_type, occurred_at, payload, payload_sha256)
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
            on conflict (event_id) do nothing
            """, event.eventId(), tenantId, UUID.fromString(companyId), event.transferId(), event.warehouse1cId(),
            event.eventType(), event.occurredAt(), payload, hash);
        if (inserted == 1) return new Receipt(event.eventId(), "RECEIVED", false);
        String existingHash = jdbc.queryForObject("select payload_sha256 from kimyosanoat_event_inbox where event_id=?",
            String.class, event.eventId());
        if (!hash.equals(existingHash)) throw new ConflictException("eventId was already used for different event data");
        String state = jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?",
            String.class, event.eventId());
        return new Receipt(event.eventId(), state, true);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
