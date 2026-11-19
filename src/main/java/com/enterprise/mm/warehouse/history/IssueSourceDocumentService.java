package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.StockUomPolicy;
import com.enterprise.mm.service.transfer.TransferActionPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads evidence, not live 1C balances. An unposted flag alone never proves a remaining quantity. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class IssueSourceDocumentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService roles;
    private final MaterialRepository materials;

    public record Document(UUID id, String documentType, String documentNumber, LocalDateTime documentDate,
        String sourceWarehouseName, Boolean posted, Boolean marked, long lineCount, List<String> requirements,
        UUID linkedDocumentId, String linkedStatus, LocalDateTime importedAt) {}
    public record Line(UUID id, String lineNumber, UUID materialId, String materialCode, String materialName,
        String quantity, String unit, UUID originalUnitId, String coefficient, UUID destinationWarehouseId,
        String destinationWarehouseName, String requirementNumber, String rowHash) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unit); }
    }
    public record Blocker(String code, String lineNumber) {}
    public record Detail(Document document, List<Line> lines, List<Blocker> blockers, String fingerprint,
        boolean canPrepare, String reviewNote, String reviewedBy, LocalDateTime reviewedAt) {}

    private static final String REQUIREMENT = "coalesce(nullif(trim(l.content::jsonb #>> '{data,_fld4527}'),''),"
        + "nullif(trim(l.content::jsonb #>> '{data,_fld5253}'),''),nullif(trim(l.content::jsonb #>> '{data,_fld13862}'),''))";
    private static final String BASE = """
        from warehouse_history_records d
        join warehouse_history_runs r on r.id=d.import_id and r.tenant_id=d.tenant_id and r.company_id=d.company_id and r.status='COMPLETED'
        left join warehouses w on w.id=d.warehouse_id and w.tenant_id=d.tenant_id and w.company_id=d.company_id
        left join warehouse_issue_source_links link on link.source_document_id=d.id and link.tenant_id=d.tenant_id and link.company_id=d.company_id
        left join transfer_documents t on t.id=link.transfer_document_id and t.tenant_id=d.tenant_id and t.company_id=d.company_id
        where d.tenant_id=:tenant and d.company_id=:company and d.kind='DOCUMENT'
            and d.source_table in ('_document4470','_document199') and d.warehouse_id=:warehouse
        """;
    private static final String LINE_SCOPE = """
        l.tenant_id=d.tenant_id and l.company_id=d.company_id and l.document_key=d.document_key and l.kind='LINE'
        and ((d.source_table='_document4470' and l.source_table='_document4470_vt4512')
            or (d.source_table='_document199' and l.source_table='_document199_vt2959'))
        and exists(select 1 from warehouse_history_runs lr where lr.id=l.import_id and lr.tenant_id=l.tenant_id and lr.company_id=l.company_id and lr.status='COMPLETED')
        """;
    private static final String SELECT = """
        select d.id,d.document_type,d.document_number,d.document_date,d.posted,d.marked,w.name as source_name,
            r.completed_at as imported_at,link.transfer_document_id,t.status as linked_status,
            (select count(*) from warehouse_history_records l where %s) as line_count,
            array(select distinct %s from warehouse_history_records l where %s and %s is not null order by 1) as requirements
        """.formatted(LINE_SCOPE, REQUIREMENT, LINE_SCOPE, REQUIREMENT);
    // Linked drafts remain in review until their operational lifecycle reaches a terminal state.
    private static final String REVIEW = "d.source_table='_document4470' and d.posted=false and d.marked=false "
        + "and (link.source_document_id is null or (t.is_delete=false and t.status not in ('RECEIVED','CLOSED','CANCELLED')))";

    public PageResponse<Document> documents(UUID warehouseId, String bucket, String search, int page, int size) {
        if (!List.of("review", "history").contains(bucket)) throw new BadRequestException("Invalid source document bucket");
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw new BadRequestException("Invalid source document pagination");
        if (search != null && search.length() > 120) throw new BadRequestException("Document search is too long");
        var p = parameters(warehouseId);
        String filter = " and (" + (bucket.equals("review") ? REVIEW : "not coalesce((" + REVIEW + "),false)") + ")";
        if (search != null && !search.isBlank()) {
            p.addValue("search", search.trim().toLowerCase(Locale.ROOT));
            filter += " and (strpos(lower(coalesce(d.document_number,'')),:search)>0 or exists(select 1 from warehouse_history_records l where "
                + LINE_SCOPE + " and strpos(lower(" + REQUIREMENT + "),:search)>0))";
        }
        long total = jdbc.queryForObject("select count(*) " + BASE + filter, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        var content = jdbc.query(SELECT + BASE + filter + " order by d.document_date desc nulls last,d.id limit :limit offset :offset", p, this::document);
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    public Detail detail(UUID id, UUID warehouseId) {
        var p = parameters(warehouseId).addValue("id", id);
        var found = jdbc.query(SELECT + BASE + " and d.id=:id", p, this::document);
        if (found.isEmpty()) throw new NotFoundException("1C source document not found in the issuing warehouse");
        Document doc = found.getFirst();
        var metadata = jdbc.queryForMap("select d.source_table,d.document_key,d.row_sha256,w.is_delete as warehouse_deleted,"
            + "link.review_note,link.reviewed_by,link.reviewed_at " + BASE + " and d.id=:id", p);
        var lines = jdbc.query("""
            select l.id,l.material_id,l.quantity,l.row_sha256,l.content::jsonb ->> 'lineNumber' as line_number,
                coalesce(m.code,l.content::jsonb #>> '{data,_fld4524}',l.content::jsonb #>> '{data,_fld4400}') as material_code,
                coalesce(m.name,ref.content::jsonb #>> '{data,name}') as material_name,
                u.id as original_unit_id,coalesce(u.measurement_name_short,u.name) as unit,
                coalesce(l.content::jsonb #>> '{data,_fld4518}',l.content::jsonb #>> '{data,_fld2966}') as coefficient,
                dest.id as destination_id,dest.name as destination_name,
                nullif(trim(l.content::jsonb #>> '{data,_fld4527}'),'') as requirement_number
            from warehouse_history_records d join warehouse_history_records l on %s
            left join materials m on m.id=l.material_id and m.tenant_id=l.tenant_id and m.company_id=l.company_id
            left join warehouses dest on dest.id=l.target_warehouse_id and dest.tenant_id=l.tenant_id and dest.company_id=l.company_id and dest.is_delete=false
            left join unit_of_measures u on u.tenant_id=l.tenant_id and u.company_id=l.company_id and u.is_delete=false
                and u.code='1C-UOM-' || coalesce(l.content::jsonb #>> '{data,_fld4517rref}',l.content::jsonb #>> '{data,_fld2965rref}')
            left join warehouse_history_records ref on ref.tenant_id=l.tenant_id and ref.company_id=l.company_id
                and ref.kind='SCHEMA' and ref.source_key='reference:materials:' || (l.content::jsonb ->> 'materialSourceId')
                and exists(select 1 from warehouse_history_runs rr where rr.id=ref.import_id and rr.tenant_id=ref.tenant_id and rr.company_id=ref.company_id and rr.status='COMPLETED')
            where d.id=:id and d.tenant_id=:tenant and d.company_id=:company
            order by case when l.content::jsonb ->> 'lineNumber' ~ '^[0-9]+$' then (l.content::jsonb ->> 'lineNumber')::numeric end nulls last,l.id
            """.formatted(LINE_SCOPE), p, (rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getString("line_number"),
                rs.getObject("material_id", UUID.class), rs.getString("material_code"), rs.getString("material_name"), rs.getString("quantity"),
                rs.getString("unit"), rs.getObject("original_unit_id", UUID.class), rs.getString("coefficient"),
                rs.getObject("destination_id", UUID.class), rs.getString("destination_name"), rs.getString("requirement_number"), rs.getString("row_sha256")));
        var blockers = new ArrayList<Blocker>();
        if (!Boolean.FALSE.equals(doc.posted())) blockers.add(new Blocker("POSTED_OR_UNKNOWN", null));
        if (!Boolean.FALSE.equals(doc.marked())) blockers.add(new Blocker("DELETED_OR_UNKNOWN", null));
        if (!"_document4470".equals(metadata.get("source_table"))) blockers.add(new Blocker("NOT_A_REQUEST", null));
        if (!Boolean.FALSE.equals(metadata.get("warehouse_deleted"))) blockers.add(new Blocker("WAREHOUSE_UNAVAILABLE", null));
        if (doc.linkedDocumentId() != null) blockers.add(new Blocker("ALREADY_LINKED", null));
        if (lines.isEmpty()) blockers.add(new Blocker("NO_LINES", null));
        if (exists("select exists(select 1 from warehouse_history_runs where tenant_id=:tenant and company_id=:company and status<>'COMPLETED')", p))
            blockers.add(new Blocker("IMPORT_INCOMPLETE", null));
        p.addValue("documentKey", metadata.get("document_key"));
        // Even a movement in an unfinished import is enough to prevent assuming an unfulfilled request.
        if (exists("select exists(select 1 from warehouse_history_records where tenant_id=:tenant and company_id=:company and document_key=:documentKey and kind='MOVEMENT')", p))
            blockers.add(new Blocker("MOVEMENTS_EXIST", null));
        Set<UUID> destinations = new HashSet<>();
        for (var line : lines) {
            if (line.destinationWarehouseId() == null || line.destinationWarehouseId().equals(warehouseId))
                blockers.add(new Blocker("DESTINATION_UNRESOLVED", line.lineNumber()));
            else destinations.add(line.destinationWarehouseId());
            validateMaterial(line, blockers);
            if (line.requirementNumber() != null && relatedPostedDocument(line, p))
                blockers.add(new Blocker("RELATED_POSTED_DOCUMENT", line.lineNumber()));
        }
        if (destinations.size() > 1) blockers.add(new Blocker("MULTIPLE_DESTINATIONS", null));
        boolean canPrepare = blockers.isEmpty() && TransferActionPolicy.hasRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD");
        var reviewed = (java.sql.Timestamp) metadata.get("reviewed_at");
        return new Detail(doc, lines, blockers, fingerprint(metadata.get("row_sha256"), lines), canPrepare,
            (String) metadata.get("review_note"), (String) metadata.get("reviewed_by"), reviewed == null ? null : reviewed.toLocalDateTime());
    }

    private void validateMaterial(Line line, List<Blocker> blockers) {
        var material = line.materialId() == null ? null : materials.findByIdAndIsDeletedFalse(line.materialId())
            .filter(m -> TenantContext.currentTenant().equals(m.getTenantId()) && CompanyContext.currentCompany().equals(m.getCompanyId())).orElse(null);
        if (material == null || material.getMaterialType() != MaterialType.TMB) {
            blockers.add(new Blocker("MATERIAL_UNAVAILABLE", line.lineNumber())); return;
        }
        if (line.originalUnitId() == null || !line.originalUnitId().equals(material.getUnitOfMeasureId()) || !isOne(line.coefficient()))
            blockers.add(new Blocker("UNIT_MISMATCH", line.lineNumber()));
        try { StockUomPolicy.quantity(material, new BigDecimal(Objects.toString(line.quantity(), "")), false); }
        catch (BusinessException | NumberFormatException invalid) { blockers.add(new Blocker("INVALID_QUANTITY", line.lineNumber())); }
    }

    private boolean relatedPostedDocument(Line line, MapSqlParameterSource p) {
        p.addValue("requirement", line.requirementNumber()).addValue("material", line.materialId());
        return exists("""
            select exists(select 1 from warehouse_history_records l
                join warehouse_history_records h on h.document_key=l.document_key and h.tenant_id=l.tenant_id and h.company_id=l.company_id and h.kind='DOCUMENT'
                where l.tenant_id=:tenant and l.company_id=:company and l.kind='LINE'
                    and l.source_table in ('_document4470_vt4512','_document162_vt2001','_document182_vt2509')
                    and %s=:requirement and l.document_key<>:documentKey
                    and (l.material_id=:material or l.material_id is null)
                    and (h.warehouse_id=:warehouse or l.warehouse_id=:warehouse or h.source_table='_document182')
                    and h.posted=true)
            """.formatted(REQUIREMENT), p);
    }

    private boolean exists(String sql, MapSqlParameterSource p) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, p, Boolean.class)); }
    private static boolean isOne(String value) {
        try { return value != null && new BigDecimal(value).compareTo(BigDecimal.ONE) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static String fingerprint(Object headerHash, List<Line> lines) {
        String evidence = headerHash + "\n" + lines.stream().map(l -> l.id() + ":" + l.rowHash()).sorted().reduce("", (a, b) -> a + b + "\n");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private MapSqlParameterSource parameters(UUID requested) {
        UUID warehouse = roles.availabilityWarehouseId(requested);
        if (warehouse == null) throw new BadRequestException("Issuing warehouse is required");
        return new MapSqlParameterSource("tenant", TenantContext.currentTenant()).addValue("company", CompanyContext.currentCompany()).addValue("warehouse", warehouse);
    }
    private Document document(ResultSet rs, int n) throws SQLException {
        var date = rs.getTimestamp("document_date"); var imported = rs.getTimestamp("imported_at");
        return new Document(rs.getObject("id", UUID.class), rs.getString("document_type"), rs.getString("document_number"),
            date == null ? null : date.toLocalDateTime(), rs.getString("source_name"), (Boolean) rs.getObject("posted"), (Boolean) rs.getObject("marked"),
            rs.getLong("line_count"), Arrays.asList((String[]) rs.getArray("requirements").getArray()), rs.getObject("transfer_document_id", UUID.class),
            rs.getString("linked_status"), imported == null ? null : imported.toLocalDateTime());
    }
}
