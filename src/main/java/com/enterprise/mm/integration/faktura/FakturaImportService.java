package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FakturaImportService {
    private static final int PAGE = 100;
    private static final int CONTENT_BATCH = 50;
    private final FakturaClient client;
    private final FakturaSourceStore store;
    private final JdbcTemplate jdbc;
    private final Executor executor;

    public FakturaImportService(FakturaClient client, FakturaSourceStore store, JdbcTemplate jdbc,
                                @Qualifier("fakturaImportExecutor") Executor executor) {
        this.client = client;
        this.store = store;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    public ImportResult importRange(LocalDate from, LocalDate to) {
        return processRun(beginRun(from, to), from, to);
    }

    /** Return immediately; the run remains queryable even if the browser disconnects. */
    public ImportResult startImportRange(LocalDate from, LocalDate to) {
        UUID runId = beginRun(from, to);
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        try {
            executor.execute(() -> {
                TenantContext.setTenant(tenant);
                CompanyContext.setCompany(company);
                try {
                    processRun(runId, from, to);
                } catch (RuntimeException ignored) {
                    // processRun has already persisted the safe failure details on the run.
                } finally {
                    TenantContext.clear();
                    CompanyContext.clear();
                }
            });
        } catch (RuntimeException ex) {
            finish(runId, "FAILED", new Counts(), "Import worker unavailable");
            throw new BusinessException("Faktura import could not be started; try again later");
        }
        return new ImportResult(runId, "RUNNING", from, to, 0, 0, 0, 0, 0, LocalDateTime.now(), null, null);
    }

    private UUID beginRun(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 31
            || to.isAfter(LocalDate.now(ZoneId.of("Asia/Tashkent")))) {
            throw new BusinessException("Choose a Faktura date range of at most 32 days ending today or earlier");
        }
        String tenant = TenantContext.currentTenant();
        UUID company = CompanyContext.currentCompany();
        failStaleRuns(tenant, company);
        UUID runId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO public.faktura_import_runs(id,tenant_id,company_id,requested_from,requested_to,status) "
                + "VALUES (?,?,?,?,?,'RUNNING')", runId, tenant, company, from, to);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("A Faktura import is already running for this company");
        }
        return runId;
    }

    private ImportResult processRun(UUID runId, LocalDate from, LocalDate to) {
        Counts counts = new Counts();
        try {
            // Verify provider company access before fetching or persisting documents.
            client.verifiedCompanyInn();
            for (int type : new int[]{20, 28, 29}) {
                importType(runId, from, to, type, false, counts);
                importType(runId, from, to, type, true, counts);
            }
            String status = counts.failed == 0 ? "SUCCESS" : "PARTIAL";
            finish(runId, status, counts, counts.failed == 0 ? null : "Some document contents could not be imported");
            return counts.result(runId, status, from, to);
        } catch (Exception ex) {
            finish(runId, "FAILED", counts, ex instanceof BusinessException ? ex.getMessage() : "Faktura fetch or content response failed");
            if (ex instanceof BusinessException business) throw business;
            throw new BusinessException("Faktura import failed; inspect the import run");
        }
    }

    private void importType(UUID runId, LocalDate from, LocalDate to, int type, boolean updatedWindow, Counts counts) {
        for (int skip = 0; skip < 10_000; skip += PAGE) {
            JsonNode response = client.documents(from, to, type, skip, PAGE, updatedWindow);
            JsonNode headers = FakturaClient.field(response, "documents", "Documents");
            if (!headers.isArray()) throw new BusinessException("Faktura document list has no documents array");
            List<JsonNode> page = new ArrayList<>();
            headers.forEach(page::add);
            counts.fetched += page.size();
            for (int offset = 0; offset < page.size(); offset += CONTENT_BATCH) {
                List<JsonNode> batch = page.subList(offset, Math.min(page.size(), offset + CONTENT_BATCH));
                List<String> ids = batch.stream().map(row -> FakturaClient.text(row, "uniqueId", "UniqueId"))
                    .filter(id -> id != null && !id.isBlank()).toList();
                for (JsonNode header : batch) {
                    if (FakturaClient.text(header, "uniqueId", "UniqueId") == null
                        || FakturaClient.text(header, "uniqueId", "UniqueId").isBlank()) {
                        recordFailure(runId, type, null, FakturaClient.text(header, "documentNumber", "DocumentNumber"),
                            "LIST", "MISSING_SOURCE_ID");
                        counts.failed++;
                    }
                }
                if (ids.isEmpty()) continue;
                JsonNode contents;
                try {
                    contents = client.contents(ids);
                } catch (Exception ex) {
                    failContentBatch(runId, type, batch, counts, "CONTENT_FETCH_FAILED");
                    throw ex;
                }
                if (FakturaClient.field(contents, "success", "Success").isBoolean()
                    && !FakturaClient.field(contents, "success", "Success").asBoolean()) {
                    failContentBatch(runId, type, batch, counts, "CONTENT_REJECTED");
                    throw new BusinessException("Faktura rejected a document content batch");
                }
                JsonNode rows = FakturaClient.field(contents, "documents", "Documents");
                if (!rows.isArray()) {
                    failContentBatch(runId, type, batch, counts, "CONTENT_INVALID_RESPONSE");
                    throw new BusinessException("Faktura content response has no documents array");
                }
                Map<String, JsonNode> byId = new HashMap<>();
                rows.forEach(row -> byId.put(FakturaClient.text(row, "uniqueId", "UniqueId"),
                    FakturaClient.field(row, "content", "Content")));
                for (JsonNode header : batch) {
                    String id = FakturaClient.text(header, "uniqueId", "UniqueId");
                    if (id == null || id.isBlank()) continue;
                    JsonNode content = byId.get(id);
                    if (content == null || !content.isObject()) {
                        recordFailure(runId, type, id, FakturaClient.text(header, "documentNumber", "DocumentNumber"),
                            "CONTENT", "CONTENT_MISSING");
                        counts.failed++;
                        continue;
                    }
                    try {
                        switch (store.upsert(type, header, content)) {
                            case CREATED -> counts.created++;
                            case UPDATED -> counts.updated++;
                            case UNCHANGED -> counts.unchanged++;
                        }
                    } catch (Exception ex) {
                        recordFailure(runId, type, id, FakturaClient.text(header, "documentNumber", "DocumentNumber"),
                            "SAVE", ex instanceof IllegalArgumentException ? "INVALID_DOCUMENT" : "SAVE_FAILED");
                        counts.failed++;
                    }
                }
            }
            jdbc.update("UPDATE public.faktura_import_runs SET fetched=?, created=?, updated=?, unchanged=?, failed=? WHERE id=?",
                counts.fetched, counts.created, counts.updated, counts.unchanged, counts.failed, runId);
            if (page.size() < PAGE) return;
        }
        throw new BusinessException("Faktura import exceeded 10,000 documents for one type; narrow the date range");
    }

    private void finish(UUID id, String status, Counts counts, String error) {
        jdbc.update("UPDATE public.faktura_import_runs SET status=?, finished_at=now(), fetched=?, created=?, updated=?, "
            + "unchanged=?, failed=?, error_message=? WHERE id=?",
            status, counts.fetched, counts.created, counts.updated, counts.unchanged, counts.failed, error, id);
    }

    public List<ImportResult> recentRuns() {
        failStaleRuns(TenantContext.currentTenant(), CompanyContext.currentCompany());
        return jdbc.query("SELECT id,status,requested_from,requested_to,fetched,created,updated,unchanged,failed,started_at,finished_at,error_message "
                + "FROM public.faktura_import_runs WHERE tenant_id=? AND company_id=? ORDER BY started_at DESC LIMIT 20",
            (rs, rowNum) -> new ImportResult((UUID) rs.getObject("id"), rs.getString("status"),
                rs.getDate("requested_from").toLocalDate(), rs.getDate("requested_to").toLocalDate(), rs.getInt("fetched"),
                rs.getInt("created"), rs.getInt("updated"), rs.getInt("unchanged"), rs.getInt("failed"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                rs.getString("error_message")),
            TenantContext.currentTenant(), CompanyContext.currentCompany());
    }

    private static final Map<String, String> FAILURE_SORT_COLUMNS = Map.of(
        "documentType", "f.document_type", "sourceUniqueId", "lower(f.source_unique_id)",
        "documentNumber", "lower(f.document_number)", "stage", "f.stage",
        "reasonCode", "f.reason_code", "createdAt", "f.created_at");

    public FailurePage failures(UUID runId, Integer type, String search, String stage, String reason,
                                String sortBy, String sortDir, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || type != null && type != 20 && type != 28 && type != 29
            || stage != null && stage.length() > 40 || reason != null && reason.length() > 60)
            throw new BusinessException("Invalid Faktura failure filters");
        String sortColumn = FAILURE_SORT_COLUMNS.get(sortBy);
        if (sortColumn == null || !("asc".equalsIgnoreCase(sortDir) || "desc".equalsIgnoreCase(sortDir)))
            throw new BusinessException("Invalid Faktura failure sort");
        StringBuilder from = new StringBuilder(" FROM public.faktura_import_failures f "
            + "JOIN public.faktura_import_runs r ON r.id=f.run_id "
            + "WHERE f.run_id=? AND f.tenant_id=? AND f.company_id=? "
            + "AND r.tenant_id=f.tenant_id AND r.company_id=f.company_id");
        List<Object> args = new ArrayList<>();
        args.add(runId);
        args.add(TenantContext.currentTenant());
        args.add(CompanyContext.currentCompany());
        if (type != null) { from.append(" AND f.document_type=?"); args.add(type); }
        if (search != null && !search.isBlank()) {
            if (search.length() > 120) throw new BusinessException("Faktura failure search is too long");
            from.append(" AND (lower(f.document_number) LIKE ? OR lower(f.source_unique_id) LIKE ?)");
            String needle = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            args.add(needle);
            args.add(needle);
        }
        if (stage != null && !stage.isBlank()) { from.append(" AND f.stage=?"); args.add(stage); }
        if (reason != null && !reason.isBlank()) { from.append(" AND f.reason_code=?"); args.add(reason); }
        Long total = jdbc.queryForObject("SELECT count(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<ImportFailure> rows = jdbc.query("SELECT f.id,f.document_type,f.source_unique_id,f.document_number,f.stage,f.reason_code "
                + from + " ORDER BY " + sortColumn + " " + sortDir.toUpperCase(Locale.ROOT)
                + " NULLS LAST, f.id LIMIT ? OFFSET ?",
            (rs, rowNum) -> new ImportFailure((UUID) rs.getObject("id"), rs.getInt("document_type"),
                rs.getString("source_unique_id"), rs.getString("document_number"),
                rs.getString("stage"), rs.getString("reason_code")),
            pageArgs.toArray());
        return new FailurePage(rows, total == null ? 0 : total, page, size);
    }

    private void recordFailure(UUID runId, int type, String sourceId, String number, String stage, String reason) {
        jdbc.update("INSERT INTO public.faktura_import_failures "
                + "(id,run_id,tenant_id,company_id,document_type,source_unique_id,document_number,stage,reason_code) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), runId, TenantContext.currentTenant(), CompanyContext.currentCompany(), type,
            truncate(sourceId, 180), truncate(number, 180), stage, reason);
    }

    private void failContentBatch(UUID runId, int type, List<JsonNode> batch, Counts counts, String reason) {
        for (JsonNode header : batch) {
            String id = FakturaClient.text(header, "uniqueId", "UniqueId");
            if (id == null || id.isBlank()) continue;
            recordFailure(runId, type, id, FakturaClient.text(header, "documentNumber", "DocumentNumber"),
                "CONTENT", reason);
            counts.failed++;
        }
    }

    private static String truncate(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maxLength));
    }

    private void failStaleRuns(String tenant, UUID company) {
        jdbc.update("UPDATE public.faktura_import_runs SET status='FAILED', finished_at=now(), error_message='Interrupted stale run' "
                + "WHERE tenant_id=? AND company_id=? AND status='RUNNING' AND started_at < now() - interval '6 hours'",
            tenant, company);
    }

    public record ImportResult(UUID runId, String status, LocalDate requestedFrom, LocalDate requestedTo,
                               int fetched, int created, int updated,
                               int unchanged, int failed, LocalDateTime startedAt, LocalDateTime finishedAt,
                               String errorMessage) {}
    public record ImportFailure(UUID id, int documentType, String sourceUniqueId, String documentNumber,
                                String stage, String reasonCode) {}
    public record FailurePage(List<ImportFailure> content, long totalElements, int page, int size) {}
    private static class Counts {
        int fetched, created, updated, unchanged, failed;
        ImportResult result(UUID id, String status, LocalDate from, LocalDate to) {
            return new ImportResult(id, status, from, to, fetched, created, updated, unchanged, failed, null, LocalDateTime.now(),
                failed == 0 ? null : "Some document contents could not be imported");
        }
    }
}
