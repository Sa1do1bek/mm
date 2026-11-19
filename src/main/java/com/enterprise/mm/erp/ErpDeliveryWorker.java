package com.enterprise.mm.erp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ErpDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(ErpDeliveryWorker.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @org.springframework.beans.factory.annotation.Autowired
    private OneCApiAccess nativeApi;
    @org.springframework.beans.factory.annotation.Autowired
    private OneCTransport transport;
    public ErpDeliveryWorker(JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.mapper = mapper; this.transactions = transactions;
    }
    @Scheduled(fixedDelayString = "${mm.erp-exchange.delivery-delay-ms:5000}")
    public void deliver() {
        if (transport != null && transport.pullEnabled()) return;
        try { transactions.executeWithoutResult(status -> deliverOne()); }
        catch (RuntimeException ex) { log.warn("ERP delivery worker failed: {}", ex.getClass().getSimpleName()); }
    }
    record Acknowledgment(String status, boolean posted, UUID documentRef) {}
    static Acknowledgment parseAcknowledgment(JsonNode ack, UUID id) {
        if (ack == null || !ack.path("protocolVersion").isIntegralNumber() || ack.path("protocolVersion").asInt() != 1
            || !id.toString().equals(ack.path("eventId").asText()) || !ack.path("posted").isBoolean())
            throw new IllegalStateException("Invalid acknowledgment");
        String state = ack.path("status").asText(); boolean posted = ack.path("posted").asBoolean();
        if (!(state.equals("POSTED") && posted || state.equals("AWAITING_CONFIRMATION") && !posted))
            throw new IllegalStateException("Inconsistent acknowledgment");
        UUID ref;
        try { ref = UUID.fromString(ack.path("documentRef").asText()); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid document reference"); }
        if (ref.equals(new UUID(0, 0)) || !ref.toString().equalsIgnoreCase(ack.path("documentRef").asText())) throw new IllegalStateException("Invalid document reference");
        return new Acknowledgment(state, posted, ref);
    }
    static String httpError(ObjectMapper mapper, int status, String body) {
        String fallback = "HTTP " + status;
        try {
            JsonNode error = mapper.readTree(body).path("error");
            if (!error.isTextual()) return fallback;
            StringBuilder sanitized = new StringBuilder();
            error.asText().codePoints().forEach(code -> {
                if (sanitized.length() < 500) {
                    if (Character.isISOControl(code)) sanitized.append(' ');
                    else if (sanitized.length() + Character.charCount(code) <= 500) sanitized.appendCodePoint(code);
                }
            });
            String detail = sanitized.toString().trim();
            if (detail.isEmpty()) return fallback;
            String result = fallback + ": " + detail;
            return result.length() <= 500 ? result : result.substring(0, 500);
        } catch (Exception ex) { return fallback; }
    }
    void deliverOne() {
        if (transport != null && transport.pullEnabled()) return;
        List<Map<String,Object>> rows = jdbc.queryForList("""
            select e.id,e.tenant_id,e.company_id,e.payload,e.confirm_requested,e.remote_document_ref,c.base_url from erp_exchange_envelope e join erp_exchange_connection c
              on c.tenant_id=e.tenant_id and c.company_id=e.company_id
            where e.operation is not null and (e.delivery_state in ('PENDING','RETRY') or (e.delivery_state='AWAITING_CONFIRMATION' and e.confirm_requested))
              and e.next_attempt_at <= current_timestamp and c.enabled = true
              and not exists (
                select 1 from erp_exchange_envelope earlier
                where earlier.tenant_id=e.tenant_id and earlier.company_id=e.company_id
                  and earlier.operation is not null and earlier.delivery_state not in ('POSTED','LEGACY_LOCAL')
                  and earlier.delivery_order < e.delivery_order
              )
            order by e.delivery_order for update of e skip locked limit 1
            """);
        if (rows.isEmpty()) return;
        Map<String,Object> row=rows.get(0); UUID id=(UUID)row.get("id");
        boolean confirming = Boolean.TRUE.equals(row.get("confirm_requested"));
        Boolean acquired=jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(cast(? as text) || ':' || cast(? as text), 0))",
            Boolean.class, row.get("tenant_id"), row.get("company_id"));
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
            String base=(String)row.get("base_url");
            if (base == null || base.isBlank()) throw new IllegalArgumentException("Endpoint is not configured");
            URI url=URI.create(base.replaceAll("/+$", "") + "/mm/v1/documents" + (confirming ? "/" + id + "/confirm" : ""));
            if (nativeApi == null && (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost()) || url.getUserInfo()!=null))
                throw new IllegalArgumentException("A native 1C API client is required");
            HttpRequest.Builder builder=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Idempotency-Key", id.toString())
                .POST(HttpRequest.BodyPublishers.ofString((String)row.get("payload")));
            if (nativeApi != null) nativeApi.authorize(builder, base, (String)row.get("tenant_id"), row.get("company_id"));
            HttpRequest request=builder.build();
            HttpResponse<String> response=http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException(httpError(mapper, response.statusCode(), response.body()));
            Acknowledgment ack = parseAcknowledgment(mapper.readTree(response.body()), id);
            String state = ack.status(); boolean posted = ack.posted(); UUID ref = ack.documentRef();
            if (confirming && (!posted || !ref.equals(row.get("remote_document_ref"))))
                throw new IllegalStateException("Invalid confirmation acknowledgment");
            jdbc.update("update erp_exchange_envelope set delivery_state=?, remote_document_ref=?, remote_posted=?, confirm_requested=false, rejection_reason=null, attempt_count=attempt_count+1 where id=?", state, ref, posted, id);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            String reason=ex instanceof IllegalStateException || ex instanceof IllegalArgumentException ? ex.getMessage() : ex.getClass().getSimpleName();
            jdbc.update("update erp_exchange_envelope set delivery_state='RETRY', rejection_reason=?, attempt_count=attempt_count+1, next_attempt_at=current_timestamp + interval '30 seconds' where id=?", reason, id);
        }
    }
}
