package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.RoleDataScopeService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receiving documents from the immutable 1C snapshot. These records must never post stock. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class InternalIncomeDocumentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService roles;

    public record Document(UUID id, String documentType, String documentNumber, LocalDateTime documentDate,
        String sourceWarehouseName, String destinationWarehouseName, boolean posted, boolean marked,
        long lineCount, List<String> requirements) {}
    public record Line(UUID id, String lineNumber, String materialCode, String materialName,
        String quantity, String unit, String value, String requirementNumber) {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "unitShortName", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public String unitShortName() { return com.enterprise.mm.common.UnitOfMeasureLabels.shortLabel(unit); }
    }
    public record Detail(Document document, List<Line> lines, boolean historyOnly) {}

    /* _fld5251 is 1C's "destination in table" switch. A stale per-line destination must not
       override the header when it is false; an unmapped line destination must not fall back
       to the header when it is true. Internal receipt (182) has no source warehouse field. */
    private static final String CTE = """
        with documents as (
            select d.* from warehouse_history_records d
            join warehouse_history_runs run on run.id=d.import_id and run.tenant_id=d.tenant_id
                and run.company_id=d.company_id and run.status='COMPLETED'
            where d.tenant_id=:tenant and d.company_id=:company and d.kind='DOCUMENT'
                and d.source_table in ('_document162','_document182') %s
        ), scoped_lines as (
            select l.*, d.id as header_id,
                nullif(trim(case when d.source_table='_document162' then l.content::jsonb #>> '{data,_fld5253}'
                    else l.content::jsonb #>> '{data,_fld13862}' end),'') as requirement_number
            from documents d join warehouse_history_records l on l.tenant_id=d.tenant_id
                and l.company_id=d.company_id and l.document_key=d.document_key and l.kind='LINE'
                and ((d.source_table='_document162' and l.source_table='_document162_vt2001')
                    or (d.source_table='_document182' and l.source_table='_document182_vt2509'))
            join warehouse_history_runs lr on lr.id=l.import_id and lr.tenant_id=l.tenant_id
                and lr.company_id=l.company_id and lr.status='COMPLETED'
            where case when d.source_table='_document182' then d.warehouse_id
                when d.content::jsonb #>> '{data,_fld5251}' = 'true' then l.target_warehouse_id
                else d.target_warehouse_id end = :warehouse
        ), summaries as (
            select header_id, count(*) as line_count,
                array_remove(array_agg(distinct requirement_number order by requirement_number),null) as requirements
            from scoped_lines group by header_id
        )
        """;
    private static final String DOCUMENT_SELECT = """
        select d.id,d.document_type,d.document_number,d.document_date,d.posted,d.marked,s.line_count,s.requirements,
            case when d.source_table='_document162' then coalesce(w.name, ref.content::jsonb #>> '{data,name}') end as source_name,
            dest.name as destination_name
        from documents d join summaries s on s.header_id=d.id
        left join warehouses w on w.id=d.warehouse_id and w.tenant_id=d.tenant_id and w.company_id=d.company_id
        left join warehouses dest on dest.id=:warehouse and dest.tenant_id=d.tenant_id and dest.company_id=d.company_id
        left join warehouse_history_records ref on ref.tenant_id=d.tenant_id and ref.company_id=d.company_id
            and ref.kind='SCHEMA' and ref.source_key='reference:warehouses:' || (d.content::jsonb ->> 'warehouseSourceId')
            and exists (select 1 from warehouse_history_runs rr where rr.id=ref.import_id and rr.tenant_id=ref.tenant_id
                and rr.company_id=ref.company_id and rr.status='COMPLETED')
        """;

    public PageResponse<Document> documents(UUID warehouseId, String search, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100)
            throw new BadRequestException("Invalid internal income pagination");
        if (search != null && search.length() > 120) throw new BadRequestException("Document search is too long");
        var p = parameters(warehouseId);
        String filter = "";
        if (search != null && !search.isBlank()) {
            // Literal substring search, including requirement numbers containing SQL wildcard characters.
            p.addValue("search", search.trim().toLowerCase(Locale.ROOT));
            filter = "where strpos(lower(coalesce(d.document_number,'')),:search)>0 or exists "
                + "(select 1 from scoped_lines l where l.header_id=d.id and strpos(lower(l.requirement_number),:search)>0)";
        }
        String cte = CTE.formatted("");
        long total = jdbc.queryForObject(cte + "select count(*) from documents d join summaries s on s.header_id=d.id " + filter, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        var content = jdbc.query(cte + DOCUMENT_SELECT + filter
            + " order by d.document_date desc nulls last,d.id limit :limit offset :offset", p, this::document);
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    public Detail detail(UUID id, UUID warehouseId) {
        var p = parameters(warehouseId).addValue("id", id);
        String cte = CTE.formatted("and d.id=:id");
        var found = jdbc.query(cte + DOCUMENT_SELECT, p, this::document);
        if (found.isEmpty()) throw new NotFoundException("Internal income document not found in the receiving warehouse");
        var lines = jdbc.query(cte + """
            select l.id,l.content::jsonb ->> 'lineNumber' as line_number,
                coalesce(m.code,l.content::jsonb #>> '{data,_fld4398}',l.content::jsonb #>> '{data,_fld5229}') as material_code,
                coalesce(m.name,ref.content::jsonb #>> '{data,name}') as material_name,
                l.quantity,l.book_value,l.requirement_number,coalesce(u.measurement_name_short,u.name) as unit
            from scoped_lines l
            left join materials m on m.id=l.material_id and m.tenant_id=l.tenant_id and m.company_id=l.company_id
            left join warehouse_history_records ref on ref.tenant_id=l.tenant_id and ref.company_id=l.company_id
                and ref.kind='SCHEMA' and ref.source_key='reference:materials:' || (l.content::jsonb ->> 'materialSourceId')
                and exists (select 1 from warehouse_history_runs rr where rr.id=ref.import_id and rr.tenant_id=ref.tenant_id
                    and rr.company_id=ref.company_id and rr.status='COMPLETED')
            left join unit_of_measures u on u.tenant_id=l.tenant_id and u.company_id=l.company_id
                and u.code='1C-UOM-' || coalesce(l.content::jsonb #>> '{data,_fld2007rref}',l.content::jsonb #>> '{data,_fld2515rref}')
            order by (l.content::jsonb ->> 'lineNumber')::numeric nulls last,l.id
            """, p, (rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getString("line_number"),
                rs.getString("material_code"), rs.getString("material_name"), rs.getString("quantity"),
                rs.getString("unit"), rs.getString("book_value"), rs.getString("requirement_number")));
        return new Detail(found.getFirst(), lines, true);
    }

    private MapSqlParameterSource parameters(UUID requested) {
        UUID warehouse = roles.availabilityWarehouseId(requested);
        if (warehouse == null) throw new BadRequestException("Receiving warehouse is required");
        return new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany()).addValue("warehouse", warehouse);
    }

    private Document document(ResultSet rs, int n) throws SQLException {
        var date = rs.getTimestamp("document_date");
        return new Document(rs.getObject("id", UUID.class), rs.getString("document_type"), rs.getString("document_number"),
            date == null ? null : date.toLocalDateTime(), rs.getString("source_name"), rs.getString("destination_name"),
            rs.getBoolean("posted"), rs.getBoolean("marked"), rs.getLong("line_count"),
            Arrays.asList((String[]) rs.getArray("requirements").getArray()));
    }
}
