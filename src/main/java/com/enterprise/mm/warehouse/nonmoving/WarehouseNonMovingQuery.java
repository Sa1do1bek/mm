package com.enterprise.mm.warehouse.nonmoving;

import com.enterprise.mm.payload.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.enterprise.mm.warehouse.nonmoving.WarehouseNonMovingDtos.*;

/** Read-only candidates; neither detections nor case snapshots are written by this query. */
@Repository
@RequiredArgsConstructor
public class WarehouseNonMovingQuery {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String CANDIDATES = """
        with balances as (
            select sb.material_id, sum(sb.quantity) quantity,
                sum(sb.available_quantity) available_quantity, sum(sb.reserved_quantity) reserved_quantity,
                sum(sb.quarantine_quantity) quarantine_quantity, sum(sb.total_value) book_value
            from stock_balances sb
            join materials m on m.id=sb.material_id and m.tenant_id=sb.tenant_id and m.company_id=sb.company_id
            where sb.tenant_id=:tenant and sb.company_id=:company and sb.warehouse_id=:warehouse
                and sb.is_delete=false and m.is_delete=false and m.material_type='TMB' and m.status='ACTIVE'
            group by sb.material_id having sum(sb.quantity)>0
        ), movement_dates as (
            -- Allocations prove which warehouse actually changed. A dispatched transfer does not
            -- count as a destination receipt until a destination allocation is posted.
            select a.material_id, coalesce(sm.movement_date, cast(sm.posted_at as date)) activity_date,
                coalesce(sm.reference_type,'') in ('OPENING_STOCK','OPENING_STOCK_EXACT') opening
            from stock_movement_allocations a
            join stock_movements sm on sm.id=a.movement_id and sm.tenant_id=a.tenant_id and sm.company_id=a.company_id
            join balances b on b.material_id=a.material_id
            where a.tenant_id=:tenant and a.company_id=:company and sm.status='POSTED' and sm.is_delete=false
                and a.on_hand_delta<>0
                and ((a.on_hand_delta<0 and a.source_warehouse_id_snapshot=:warehouse)
                    or (a.on_hand_delta>0 and a.target_warehouse_id_snapshot=:warehouse))
            union all
            -- Legacy movements without allocation evidence retain their explicit warehouse identity.
            select sm.material_id, coalesce(sm.movement_date, cast(sm.posted_at as date)),
                coalesce(sm.reference_type,'') in ('OPENING_STOCK','OPENING_STOCK_EXACT')
            from stock_movements sm join balances b on b.material_id=sm.material_id
            where sm.tenant_id=:tenant and sm.company_id=:company and sm.is_delete=false
                and sm.status='POSTED' and sm.quantity<>0
                and coalesce(sm.reference_type,'')<>'TRANSFER_TRANSIT_LOSS'
                and (sm.warehouse_id=:warehouse or
                    (sm.movement_type='TRANSFER' and sm.target_warehouse_id=:warehouse
                     and coalesce(sm.reference_type,'') not like 'TRANSFER_%'))
                and not exists (select 1 from stock_movement_allocations a
                    where a.tenant_id=sm.tenant_id and a.company_id=sm.company_id and a.movement_id=sm.id)
            union all
            -- Account-aware stock commands do not necessarily create a StockMovement entity.
            select e.material_id, pb.posting_date, pb.context_key='OPENING'
            from warehouse_posting_effects e join warehouse_posting_batches pb on pb.id=e.batch_id
            join balances b on b.material_id=e.material_id
            where pb.tenant_id=:tenant and pb.company_id=:company and pb.status='COMMITTED'
                and e.warehouse_id=:warehouse and e.effect_type='STOCK' and e.quantity<>0
            union all
            -- Historical imports are evidence only; they never add to current stock quantities.
            select h.material_id, h.movement_date, false
            from warehouse_imported_movements h join balances b on b.material_id=h.material_id
            where h.tenant_id=:tenant and h.company_id=:company and h.warehouse_id=:warehouse and h.quantity<>0
        ), last_activity as (
            -- Loading an opening snapshot is not a new physical receipt: prefer actual history.
            select material_id, max(activity_date) filter (where not opening) last_movement_date,
                max(activity_date) filter (where opening) opening_date
            from movement_dates group by material_id
        ), candidates as (
            select b.*, m.code material_code, m.name material_name, m.material_group_id,
                mg.name material_group_name, u.code unit_code,
                coalesce(nullif(case :language when 'uz' then u.measurement_name_short_uz
                    when 'ru' then u.measurement_name_short_ru else u.measurement_name_short end,''),
                    nullif(u.measurement_name_short,''), nullif(u.measurement_name_short_ru,''),
                    nullif(u.measurement_name_short_uz,''), u.name, u.code) unit_name, la.last_movement_date,
                coalesce(la.last_movement_date,la.opening_date) age_reference_date,
                case when la.last_movement_date is not null then 'MOVEMENT'
                     when la.opening_date is not null then 'OPENING_BASELINE' else 'UNKNOWN' end age_basis
            from balances b join materials m on m.id=b.material_id
            left join material_groups mg on mg.id=m.material_group_id
                and mg.tenant_id=:tenant and mg.company_id=:company and mg.is_delete=false
            left join unit_of_measures u on u.id=m.unit_of_measure_id
                and u.tenant_id=:tenant and u.company_id=:company and u.is_delete=false
            left join last_activity la on la.material_id=b.material_id
            where (cast(:groupId as uuid) is null or m.material_group_id=cast(:groupId as uuid))
                and (:search='' or lower(m.code) like :pattern escape '!'
                    or lower(m.name) like :pattern escape '!')
                and ((coalesce(la.last_movement_date,la.opening_date) + make_interval(months => :months))::date <= :asOf
                    or (:unknown=true and coalesce(la.last_movement_date,la.opening_date) is null))
        )
        """;

    public PageResponse<Candidate> find(String tenant, UUID company, UUID warehouse, int months,
                                        LocalDate asOf, String currency, Filter filter) {
        return find(tenant, company, warehouse, months, asOf, currency, filter, WarehouseNonMovingService.exportLanguage(null));
    }

    public PageResponse<Candidate> find(String tenant, UUID company, UUID warehouse, int months,
        LocalDate asOf, String currency, Filter filter, String language) {
        return find(tenant, company, warehouse, months, asOf, currency, filter, language, null);
    }

    /** Later export pages reuse the snapshot's count instead of repeating the aggregate count query. */
    public PageResponse<Candidate> find(String tenant, UUID company, UUID warehouse, int months,
        LocalDate asOf, String currency, Filter filter, String language, Long knownTotal) {
        String search = filter.search() == null ? "" : filter.search().trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var params = new MapSqlParameterSource("tenant", tenant).addValue("company", company)
            .addValue("language", language).addValue("warehouse", warehouse).addValue("months", months).addValue("asOf", asOf)
            .addValue("groupId", filter.materialGroupId(), java.sql.Types.OTHER)
            .addValue("search", search).addValue("pattern", pattern)
            .addValue("unknown", filter.includeUnknownHistory()).addValue("limit", filter.size())
            .addValue("offset", (long) filter.page() * filter.size());
        Long total = knownTotal != null ? knownTotal : jdbc.queryForObject(CANDIDATES + "select count(*) from candidates", params, Long.class);
        String order = order(filter);
        // Page first, so case lookups are bounded by page size rather than all eligible materials.
        var rows = jdbc.query(CANDIDATES + ", paged as (select * from candidates c order by " + order
            + " limit :limit offset :offset) " + """
            select c.*, active_case.id active_case_id, active_case.case_no, active_case.status case_status
            from paged c
            left join lateral (
                select sc.id, sc.case_no, sc.status from non_moving_stock_case_lines cl
                join non_moving_stock_cases sc on sc.id=cl.case_id
                where cl.tenant_id=:tenant and cl.company_id=:company and cl.warehouse_id=:warehouse
                    and cl.material_id=c.material_id and cl.is_delete=false
                    and sc.tenant_id=:tenant and sc.company_id=:company and sc.is_delete=false
                    and sc.status not in ('CLOSED','CANCELLED')
                order by sc.created_at desc, sc.id limit 1
            ) active_case on true
            """ + " order by " + order, params, (rs, n) -> row(rs, warehouse, months, asOf, currency));
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, filter.page(), filter.size(), count,
            (int) Math.ceil((double) count / filter.size()));
    }

    private String order(Filter filter) {
        String column = switch (filter.sortBy()) {
            case LAST_MOVEMENT_DATE -> "c.age_reference_date";
            case MATERIAL_CODE -> "c.material_code";
            case MATERIAL_NAME -> "multilingual_name_value(c.material_name, :language)";
            case QUANTITY -> "c.quantity";
            case BOOK_VALUE -> "c.book_value";
        };
        return column + " " + filter.direction().name() + " nulls last, c.material_code, c.material_id";
    }

    private Candidate row(ResultSet rs, UUID warehouse, int months, LocalDate asOf, String currency) throws SQLException {
        LocalDate lastDate = rs.getObject("last_movement_date", LocalDate.class);
        LocalDate referenceDate = rs.getObject("age_reference_date", LocalDate.class);
        return new Candidate(rs.getObject("material_id", UUID.class), rs.getString("material_code"),
            rs.getString("material_name"), rs.getObject("material_group_id", UUID.class),
            rs.getString("material_group_name"), warehouse, rs.getString("unit_code"), rs.getString("unit_name"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("available_quantity"),
            rs.getBigDecimal("reserved_quantity"), rs.getBigDecimal("quarantine_quantity"),
            rs.getBigDecimal("book_value"), currency, lastDate, referenceDate, rs.getString("age_basis"),
            referenceDate == null ? null : elapsedMonths(referenceDate, asOf),
            referenceDate == null ? null : referenceDate.plusMonths(months),
            referenceDate == null ? "INSUFFICIENT_HISTORY" : "KNOWN",
            rs.getObject("active_case_id", UUID.class), rs.getString("case_no"), rs.getString("case_status"));
    }

    static int elapsedMonths(LocalDate from, LocalDate to) {
        int months = (int) ChronoUnit.MONTHS.between(from, to);
        // plusMonths uses calendar anniversaries, including Jan 31 -> Feb 28.
        return !from.plusMonths(months + 1L).isAfter(to) ? months + 1 : months;
    }
}
