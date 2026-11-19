package com.enterprise.mm.integration.kimyosanoat;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores the exact Faktura request bytes before scheduling any MM stock work. */
@Service
public class KimyosanoatWaybillInbox {
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KimyosanoatEventInbox events;
    private final String tenantId;
    private final UUID companyId;

    public KimyosanoatWaybillInbox(JdbcTemplate jdbc, ObjectMapper json, KimyosanoatEventInbox events,
            @Value("${app.kimyosanoat.webhook.tenant-id:}") String tenantId,
            @Value("${app.kimyosanoat.webhook.company-id:}") String companyId) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.tenantId = tenantId;
        UUID parsed;
        try { parsed = UUID.fromString(companyId); }
        catch (IllegalArgumentException ex) { parsed = null; }
        this.companyId = parsed;
    }

    public record Receipt(UUID receiptId, String state, boolean duplicate) { }
    public record Review(UUID receiptId, String reason, OffsetDateTime receivedAt) { }
    private record Stored(UUID receiptId, byte[] bytes, Long transferId, UUID warehouse1cId, String state) { }

    @Transactional
    public Receipt accept(byte[] body, Long transferId, UUID warehouse1cId) {
        int waybillCount = validateBody(body);
        if ((transferId == null) != (warehouse1cId == null) || transferId != null && transferId <= 0)
            throw new BadRequestException("Both X-Transfer-Id and X-Warehouse-1C-Id are required for stock posting");
        String hash = sha256(body);
        UUID receiptId = UUID.randomUUID();
        boolean eligibleForQueue = transferId != null && waybillCount == 1;
        String state = eligibleForQueue ? "RECEIVED" : "REVIEW_REQUIRED";
        String reason = transferId == null ? "SOURCE_IDENTITY_MISSING"
            : waybillCount != 1 ? "MULTIPLE_WAYBILLS" : null;
        int inserted = jdbc.update("""
            insert into kimyosanoat_waybill_inbox
              (receipt_id, tenant_id, company_id, payload_sha256, payload_bytes, transfer_id,
               warehouse_1c_id, processing_state, review_reason)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, company_id, payload_sha256) do nothing
            """, receiptId, tenantId, companyId, hash, body, transferId, warehouse1cId,
            state, reason);
        if (inserted == 0) {
            Stored existing = jdbc.query("""
                select receipt_id, payload_bytes, transfer_id, warehouse_1c_id, processing_state
                from kimyosanoat_waybill_inbox
                where tenant_id=? and company_id=? and payload_sha256=?
                """, rs -> rs.next() ? new Stored(rs.getObject(1, UUID.class), rs.getBytes(2),
                    rs.getObject(3, Long.class), rs.getObject(4, UUID.class), rs.getString(5)) : null,
                tenantId, companyId, hash);
            if (existing == null || !Arrays.equals(body, existing.bytes()))
                throw new ConflictException("Waybill payload hash conflicts with an existing receipt");
            if (transferId != null && existing.transferId() == null) {
                jdbc.update("""
                    update kimyosanoat_waybill_inbox
                    set transfer_id=?, warehouse_1c_id=?, processing_state=?, review_reason=?
                    where receipt_id=? and transfer_id is null
                    """, transferId, warehouse1cId, state, reason, existing.receiptId());
                if (eligibleForQueue) queue(existing.receiptId(), transferId, warehouse1cId);
                return new Receipt(existing.receiptId(), state, true);
            }
            if (transferId != null && (!Objects.equals(transferId, existing.transferId())
                    || !Objects.equals(warehouse1cId, existing.warehouse1cId())))
                throw new ConflictException("Waybill was previously linked to a different source transfer");
            return new Receipt(existing.receiptId(), eventState(existing), true);
        }
        if (eligibleForQueue) queue(receiptId, transferId, warehouse1cId);
        return new Receipt(receiptId, state, false);
    }

    @Transactional(readOnly = true)
    public byte[] raw(UUID receiptId, String tenant, UUID company) {
        List<byte[]> rows = jdbc.query("""
            select payload_bytes from kimyosanoat_waybill_inbox
            where receipt_id=? and tenant_id=? and company_id=?
            """, (rs, row) -> rs.getBytes(1), receiptId, tenant, company);
        if (rows.isEmpty()) throw new NotFoundException("Kimyosanoat waybill not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Review> review(String tenant, UUID company) {
        return jdbc.query("""
            select receipt_id, review_reason, received_at from kimyosanoat_waybill_inbox
            where tenant_id=? and company_id=? and processing_state='REVIEW_REQUIRED'
            order by received_at desc limit 100
            """, (rs, row) -> new Review(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class)), tenant, company);
    }

    private void queue(UUID receiptId, long transferId, UUID warehouse1cId) {
        events.accept(new KimyosanoatEventInbox.Event(receiptId, transferId, warehouse1cId,
            "EXIT_ACCEPTED", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private String eventState(Stored stored) {
        if (stored.transferId() == null) return stored.state();
        List<String> states = jdbc.query("""
            select processing_state from kimyosanoat_event_inbox where event_id=?
            """, (rs, row) -> rs.getString(1), stored.receiptId());
        return states.isEmpty() ? stored.state() : states.getFirst();
    }

    private int validateBody(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES)
            throw new BadRequestException("Waybill JSON must be between 1 byte and 5 MiB");
        JsonNode root;
        try { root = json.readTree(body); }
        catch (IOException ex) { throw new BadRequestException("Invalid waybill JSON"); }
        JsonNode waybills = root == null ? null : root.path("local_waybills");
        if (root == null || !root.isObject() || waybills == null || !waybills.isArray() || waybills.isEmpty())
            throw new BadRequestException("local_waybills must be a non-empty array");
        for (JsonNode waybill : waybills) {
            if (!waybill.isObject() || !waybill.path("head").isObject()
                    || !waybill.path("document").isObject())
                throw new BadRequestException("Each local_waybills item needs head and document objects");
        }
        return waybills.size();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
