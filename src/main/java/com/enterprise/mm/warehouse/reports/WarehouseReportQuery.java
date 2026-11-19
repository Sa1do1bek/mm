package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.enterprise.mm.payload.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

@Repository
public class WarehouseReportQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public WarehouseReportQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* Allocations describe what actually changed, including split issues and exact reversals.
       A cancelled original remains part of history; its reversal is a separate effective-date entry.
       Netting first by movement/warehouse removes relocations within the same warehouse. */
    private static final String OPERATIONAL_LEDGER = """
        with posted as (
          select * from stock_movements where tenant_id=:tenant and company_id=:company
            and posted_at is not null and status in ('POSTED','CANCELLED')
        ), deltas as (
          select m.id, a.material_id,
            coalesce(a.source_warehouse_id_snapshot,a.target_warehouse_id_snapshot) warehouse_id,
            m.movement_date, m.reference_type, m.reference_id, m.movement_type,
            sum(a.on_hand_delta) quantity, sum(a.total_value_delta) amount
          from posted m join stock_movement_allocations a on a.movement_id=m.id
            and a.tenant_id=:tenant and a.company_id=:company and m.tenant_id=a.tenant_id and m.company_id=a.company_id
          group by m.id,a.material_id,coalesce(a.source_warehouse_id_snapshot,a.target_warehouse_id_snapshot),
            m.movement_date,m.reference_type,m.reference_id,m.movement_type
          union all
          select m.id,m.material_id,m.warehouse_id,m.movement_date,m.reference_type,m.reference_id,m.movement_type,
            case when m.movement_type in ('ISSUE','WRITE_OFF','TRANSFER') then -m.quantity else m.quantity end,
            case when m.movement_type in ('ISSUE','WRITE_OFF','TRANSFER') then -coalesce(m.total_cost,0) else coalesce(m.total_cost,0) end
          from posted m where m.tenant_id=:tenant and m.company_id=:company and not exists (select 1 from stock_movement_allocations a where a.movement_id=m.id and a.tenant_id=:tenant and a.company_id=:company)
          union all
          select m.id,m.material_id,m.target_warehouse_id,m.movement_date,m.reference_type,m.reference_id,m.movement_type,
            m.quantity,coalesce(m.total_cost,0)
          from posted m where m.tenant_id=:tenant and m.company_id=:company and m.movement_type='TRANSFER' and m.target_warehouse_id is not null
            and not exists (select 1 from stock_movement_allocations a where a.movement_id=m.id and a.tenant_id=:tenant and a.company_id=:company)
          union all
          select c.id,c.material_id,c.warehouse_id,cast(c.created_at as date),c.source_document_type,c.source_document_id,
            c.movement_type,cast(0 as decimal(22,7)),c.total_cost
          from stock_cost_movements c where c.tenant_id=:tenant and c.company_id=:company and c.is_delete=false
            and c.status='POSTED' and c.stock_movement_id is null and c.movement_type='LANDED_COST_ADJUSTMENT'
        ), net as (
          select id,material_id,warehouse_id,movement_date,reference_type,reference_id,movement_type,
            sum(quantity) quantity,sum(amount) amount from deltas
          group by id,material_id,warehouse_id,movement_date,reference_type,reference_id,movement_type
        ), enriched as (
          select d.*,w.code warehouse_code,w.name warehouse_name,g.id material_group_id,g.code group_code,g.name material_group_name,m.code material_code,
            m.name material_name,coalesce(u.code,'—') uom,
            coalesce(nullif(trim(u.measurement_name_short),''),nullif(trim(u.measurement_name_long),'')) uom_short_name,
            coalesce(nullif(trim(u.measurement_name_short_ru),''),nullif(trim(u.measurement_name_long_ru),'')) uom_short_name_ru,
            coalesce(nullif(trim(u.measurement_name_short_uz),''),nullif(trim(u.measurement_name_long_uz),'')) uom_short_name_uz,
            case when current_posting.reversal_of_id is not null then -d.quantity else d.quantity end direction_quantity,
            case when current_posting.reversal_of_id is not null then -d.amount else d.amount end direction_amount,
            case when issue_review.movement_id is null then current_posting.account_code_snapshot
              when issue_review.status='RESOLVED' then issue_review.stock_account_code else null end account_code_snapshot,
            case when issue_review.status='RESOLVED' then issue_review.counter_account_code else null end counter_account_snapshot,
            coalesce(s.name,po_supplier.name,receiver.full_name,other.name,'—') counterparty
          from net d join materials m on m.id=d.material_id and m.tenant_id=:tenant and m.company_id=:company
          join warehouses w on w.id=d.warehouse_id and w.tenant_id=:tenant and w.company_id=:company
          left join material_groups g on g.id=m.material_group_id and g.tenant_id=:tenant and g.company_id=:company
          left join unit_of_measures u on u.id=m.unit_of_measure_id and u.tenant_id=:tenant
          -- Use primary-key lookups for enrichment rather than rescanning a materialized ledger.
          left join stock_movements current_posting on current_posting.id=d.id
            and current_posting.tenant_id=:tenant and current_posting.company_id=:company
            and current_posting.posted_at is not null and current_posting.status in ('POSTED','CANCELLED')
          left join stock_movements original on original.id=current_posting.reversal_of_id
            and original.tenant_id=:tenant and original.company_id=:company
            and original.posted_at is not null and original.status in ('POSTED','CANCELLED')
          left join stock_issue_account_reviews issue_review
            on issue_review.movement_id=coalesce(original.id,current_posting.id)
            and issue_review.tenant_id=:tenant and issue_review.company_id=:company
          left join goods_receipts gr on gr.id=coalesce(original.reference_id,d.reference_id) and coalesce(original.reference_type,d.reference_type)='GOODS_RECEIPT'
            and gr.tenant_id=:tenant and gr.company_id=:company
          left join suppliers s on s.id=gr.supplier_id and s.tenant_id=:tenant and s.company_id=:company
          left join purchase_orders po on po.id=coalesce(original.reference_id,d.reference_id)
            and coalesce(original.reference_type,d.reference_type) in ('PurchaseOrder','PURCHASE_ORDER') and po.tenant_id=:tenant and po.company_id=:company
          left join suppliers po_supplier on po_supplier.id=po.supplier_id and po_supplier.tenant_id=:tenant and po_supplier.company_id=:company
          left join issue_requests ir on ir.id=coalesce(original.reference_id,d.reference_id) and coalesce(original.reference_type,d.reference_type)='ISSUE_REQUEST'
            and ir.tenant_id=:tenant and ir.company_id=:company
          left join users receiver on receiver.id=ir.receiver_user_id and receiver.tenant_id=:tenant
          left join stock_movements transfer on transfer.id=coalesce(original.id,d.id) and transfer.movement_type='TRANSFER'
            and transfer.tenant_id=:tenant and transfer.company_id=:company
            and transfer.posted_at is not null and transfer.status in ('POSTED','CANCELLED')
          left join warehouses other on other.id=case when d.warehouse_id=transfer.warehouse_id
            then transfer.target_warehouse_id else transfer.warehouse_id end and other.tenant_id=:tenant and other.company_id=:company
          where (d.quantity<>0 or d.amount<>0)
        )
        """;

    static final String LEDGER = OPERATIONAL_LEDGER.replace("), deltas as (", "), operational_deltas as (")
        .replace("), net as (", """
        ), imported as (
          select h.id,h.tenant_id,h.company_id,h.document_key,h.movement_date,h.source_record_id,h.target_warehouse_id,h.movement_type,h.quantity,h.amount,
            coalesce(h.material_id,md5('1c-material:'||h.company_id||':'||h.material_source_id)::uuid) material_id,
            coalesce(h.warehouse_id,md5('1c-warehouse:'||h.company_id||':'||h.warehouse_source_id)::uuid) warehouse_id,
            h.material_name,h.warehouse_name,h.material_code,h.warehouse_code,h.material_source_id,(h.material_id is null or h.warehouse_id is null) legacy
          from warehouse_imported_movements h
          where h.tenant_id=:tenant and h.company_id=:company and h.movement_date>=:from
        ), historical_masters as (
          select distinct on (material_id,warehouse_id) material_id,warehouse_id,material_name,warehouse_name,material_code,warehouse_code,material_source_id
          from imported where legacy order by material_id,warehouse_id,id
        ), history_start as (select cast(:from as date)-1 anchor_date), deltas as (
          select id,material_id,warehouse_id,
            case when reference_type='OPENING_STOCK_EXACT' and (select anchor_date from history_start) is not null
              then (select anchor_date from history_start) else movement_date end movement_date,
            reference_type,reference_id,movement_type,quantity,amount from operational_deltas
          union all
          select id,material_id,warehouse_id,movement_date,'ONE_C_HISTORY',source_record_id,movement_type,quantity,amount from imported where movement_date<=:to
          union all
          -- Back-calculate the requested opening from the verified snapshot by removing
          -- source movements on/after :from. Re-add only movements through :to. Indexed
          -- date filtering avoids scanning all years for a current-month report.
          select md5('1c-anchor:'||:company||':'||material_id||':'||warehouse_id)::uuid,
            material_id,warehouse_id,(select anchor_date from history_start),'OPENING_STOCK_EXACT',null,'ADJUSTMENT',-sum(quantity),-sum(amount)
          from imported group by material_id,warehouse_id
        ), net as (
        """)
        .replace("d.*,w.code warehouse_code,w.name warehouse_name", "d.*,coalesce(w.code,hm.warehouse_code) warehouse_code,coalesce(w.name,hm.warehouse_name) warehouse_name")
        .replace("m.code material_code", "coalesce(m.code,hm.material_code) material_code")
        .replace("m.name material_name,coalesce(u.code,'—') uom", "coalesce(m.name,hm.material_name) material_name,coalesce(u.code,'1C-UNKNOWN-'||hm.material_source_id,'—') uom")
        .replace("from net d join materials m", "from net d left join historical_masters hm on hm.material_id=d.material_id and hm.warehouse_id=d.warehouse_id left join materials m")
        .replace("join warehouses w on w.id=d.warehouse_id", "left join warehouses w on w.id=d.warehouse_id")
        .replace("left join warehouses other on other.id=case when d.warehouse_id=transfer.warehouse_id", "left join warehouse_imported_movements source_transfer on source_transfer.id=d.id and source_transfer.tenant_id=:tenant and source_transfer.company_id=:company left join warehouses other on other.id=coalesce(case when d.warehouse_id=transfer.warehouse_id")
        .replace("then transfer.target_warehouse_id else transfer.warehouse_id end and other.tenant_id", "then transfer.target_warehouse_id else transfer.warehouse_id end,source_transfer.target_warehouse_id) and other.tenant_id");

    public MapSqlParameterSource scope() {
        return new MapSqlParameterSource().addValue("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany());
    }

    public String ledger() {return OPERATIONAL_LEDGER;}

    private String ledger(Filter filter) {
        LocalDate cutoff=historicalCutoff();
        String base = cutoff!=null && filter.from().isBefore(cutoff) ? LEDGER : OPERATIONAL_LEDGER;
        if (filter.snapshotTimestamp() != null) {
            base = base.replace("and status in ('POSTED','CANCELLED')", "and status in ('POSTED','CANCELLED') and posted_at <= :snapshotTimestamp");
            base = base.replace("when issue_review.status='RESOLVED' then", "when issue_review.status='RESOLVED' and issue_review.reviewed_at <= :snapshotTimestamp then");
        }
        return base;
    }

    private boolean hasImportedTable() {
        return Boolean.TRUE.equals(jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"warehouse_imported_movements",new String[]{"TABLE"})){return tables.next();}
        }));
    }
    public LocalDate historicalStart() {
        if(!hasImportedTable())return null;
        return jdbc.queryForObject("select min(movement_date) from warehouse_imported_movements where tenant_id=:tenant and company_id=:company ",scope(),LocalDate.class);
    }

    public LocalDate historicalCutoff() {
        if(historicalStart()==null)return null;
        return jdbc.queryForObject("select min(posting_date) from exact_opening_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED'",scope(),LocalDate.class);
    }

    public long unmappedHistoryRows() {
        if(!hasImportedTable())return 0;
        return jdbc.queryForObject("select count(*) from warehouse_imported_movements where tenant_id=:tenant and company_id=:company and (material_id is null or warehouse_id is null)",scope(),Long.class);
    }


    public LocalDate coverageStart() {
        LocalDate history=historicalStart();
        if(history!=null)return history;
        if (hasOpeningImports()) {
            List<LocalDate> dates = jdbc.query("select posting_date from exact_opening_imports where tenant_id=:tenant and company_id=:company",
                scope(), (rs,n) -> rs.getDate(1).toLocalDate());
            if (!dates.isEmpty()) return dates.getFirst();
        }
        return jdbc.queryForObject(ledger() + "select min(movement_date) from net", scope(), LocalDate.class);
    }

    public boolean importInProgress() {
        if (!hasOpeningImports()) return false;
        return jdbc.queryForObject("select count(*) from exact_opening_imports where tenant_id=:tenant and company_id=:company and status<>'COMPLETED'",
            scope(), Integer.class) > 0;
    }

    private boolean hasOpeningImports() {
        // Opening-import reconciliation was added after the original stock ledger. Older
        // deployments can still report their posted ledger without that optional module.
        return Boolean.TRUE.equals(jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables=connection.getMetaData().getTables(connection.getCatalog(),connection.getSchema(),"exact_opening_imports",new String[]{"TABLE"})) {
                return tables.next();
            }
        }));
    }

    static String materialRestriction(Filter f) {
        if (f.materialType()==null && f.materialSubtype()==null) return "";
        String restriction=" and material_id in (select id from materials where tenant_id=:tenant and company_id=:company";
        if (f.materialType()!=null) restriction+=" and material_type=:materialType";
        if (f.materialSubtype()!=null) restriction+=" and material_type='TMB' and coalesce(material_subtype,'MATERIAL')=:materialSubtype";
        return restriction+")";
    }

    private String resultSql(Filter f) {
        String opening = "(movement_date<:from or reference_type in ('OPENING_STOCK_EXACT','OPENING_STOCK'))";
        String period = "(movement_date between :from and :to and coalesce(reference_type,'') not in ('OPENING_STOCK_EXACT','OPENING_STOCK'))";
        String common = "material_id,warehouse_id,warehouse_code,warehouse_name,material_group_id,group_code,material_group_name,material_code,material_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz";
        String filters = " from enriched where movement_date<=:to";
        if (f.warehouseId()!=null) filters += " and warehouse_id=:warehouse";
        if (!f.warehouseIds().isEmpty()) filters += " and warehouse_id in (:scopedWarehouses)";
        if (f.materialId()!=null) filters += " and material_id=:material";
        if (f.materialGroupId()!=null) filters += " and material_id in (select id from materials where material_group_id=:groupId and tenant_id=:tenant and company_id=:company)";
        filters += materialRestriction(f);
        if (f.search()!=null && !f.search().isBlank()) filters += " and (lower(material_code) like :search escape '!' or lower(material_name) like :search escape '!')";
        if (f.ownerPartyId()!=null) filters += " and counterparty in (select counterparty_name from warehouse_custody_agreements where owner_id=:ownerPartyId and tenant_id=:tenant and company_id=:company)";
        if (f.type()==Type.MATERIALS_IN_USE) filters += " and (coalesce(uom,'')='014' or reference_type in ('IN_USE','ISSUE_REQUEST','HOLDING_HANDOVER'))";
        if (f.type()==Type.CUSTODY_STATEMENT) filters += " and counterparty<>'—'";
        if (f.type()==Type.INSTALLATION_EQUIPMENT) filters += " and material_id in (select id from materials where tenant_id=:tenant and company_id=:company and (material_type='EQUIPMENT' or material_subtype='EQUIPMENT'))";
        boolean register = f.type()==Type.RECEIPTS || f.type()==Type.ISSUES;
        String columns;
        if (register) {
            String direction = f.type()==Type.RECEIPTS ? "(direction_quantity>0 or (direction_quantity=0 and direction_amount>0))" : "(direction_quantity<0 or (direction_quantity=0 and direction_amount<0))";
            filters += " and " + period + " and " + direction;
            columns = "select " + common + ",counterparty,0 opening_quantity,0 opening_amount,"
                + (f.type()==Type.RECEIPTS ? "sum(quantity) receipt_quantity,sum(amount) receipt_amount,0 issue_quantity,0 issue_amount,"
                : "0 receipt_quantity,0 receipt_amount,-sum(quantity) issue_quantity,-sum(amount) issue_amount,")
                + "0 closing_quantity,0 closing_amount" + filters + " group by " + common + ",counterparty";
        } else if (f.type() == Type.GROUP_SUMMARY) {
            columns = "select cast(null as uuid) material_id,warehouse_id,warehouse_code,warehouse_name,"
                + "material_group_id,coalesce(group_code,'—') group_code,coalesce(material_group_name,'Без группы') material_group_name,"
                + "coalesce(group_code,'—') material_code,coalesce(material_group_name,'Без группы') material_name,"
                + "uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,cast('—' as varchar) counterparty,"
                + "sum(case when " + opening + " then quantity else 0 end) opening_quantity,"
                + "sum(case when " + opening + " then amount else 0 end) opening_amount,"
                + "sum(case when " + period + " and direction_quantity>0 then quantity else 0 end) receipt_quantity,"
                + "sum(case when " + period + " and (direction_quantity>0 or (direction_quantity=0 and direction_amount>0)) then amount else 0 end) receipt_amount,"
                + "sum(case when " + period + " and direction_quantity<0 then -quantity else 0 end) issue_quantity,"
                + "sum(case when " + period + " and (direction_quantity<0 or (direction_quantity=0 and direction_amount<0)) then -amount else 0 end) issue_amount,"
                + "sum(quantity) closing_quantity,sum(amount) closing_amount" + filters
                + " group by warehouse_id,warehouse_code,warehouse_name,material_group_id,group_code,material_group_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz";
        } else if (f.type() == Type.INVENTORY_COMPARISON) {
            columns = "select " + common + ",cast('СООТВЕТСТВУЕТ' as varchar) counterparty,"
                + "sum(quantity) opening_quantity,sum(amount) opening_amount,"
                + "0 receipt_quantity,0 receipt_amount,0 issue_quantity,0 issue_amount,"
                + "sum(quantity) closing_quantity,sum(amount) closing_amount" + filters + " group by " + common;
        } else {
            columns = "select " + common + ",cast('—' as varchar) counterparty,"
                + "sum(case when " + opening + " then quantity else 0 end) opening_quantity,"
                + "sum(case when " + opening + " then amount else 0 end) opening_amount,"
                + "sum(case when " + period + " and direction_quantity>0 then quantity else 0 end) receipt_quantity,"
                + "sum(case when " + period + " and (direction_quantity>0 or (direction_quantity=0 and direction_amount>0)) then amount else 0 end) receipt_amount,"
                + "sum(case when " + period + " and direction_quantity<0 then -quantity else 0 end) issue_quantity,"
                + "sum(case when " + period + " and (direction_quantity<0 or (direction_quantity=0 and direction_amount<0)) then -amount else 0 end) issue_amount,"
                + "sum(quantity) closing_quantity,sum(amount) closing_amount" + filters + " group by " + common;
        }
        String nonzero;
        if (f.type() == Type.STOCK_BALANCES || f.type() == Type.INVENTORY_COMPARISON) {
            nonzero = "closing_quantity<>0 or closing_amount<>0";
        } else if (f.type() == Type.NONMOVING_STOCK) {
            nonzero = "receipt_quantity=0 and issue_quantity=0 and (opening_quantity<>0 or closing_quantity<>0)";
        } else {
            nonzero = "opening_quantity<>0 or opening_amount<>0 or receipt_quantity<>0 or receipt_amount<>0 or issue_quantity<>0 or issue_amount<>0 or closing_quantity<>0 or closing_amount<>0";
        }
        return ledger(f) + ", aggregated as (" + columns + "), results as (select * from aggregated where " + nonzero + ") ";
    }

    private MapSqlParameterSource params(Filter f) {
        String search = f.search()==null ? "" : f.search().trim().toLowerCase(java.util.Locale.ROOT)
            .replace("!","!!").replace("%","!%").replace("_","!_");
        var p = scope().addValue("scopedWarehouses",f.warehouseIds()).addValue("from",f.from()).addValue("to",f.to()).addValue("warehouse",f.warehouseId())
            .addValue("material",f.materialId()).addValue("groupId",f.materialGroupId()).addValue("search","%"+search+"%")
            .addValue("materialSubtype",f.materialSubtype()==null ? null : f.materialSubtype().name())
            .addValue("materialType",f.materialType()==null ? null : f.materialType().name())
            .addValue("ownerPartyId", f.ownerPartyId())
            .addValue("limit",f.size()).addValue("offset",(long)f.page()*f.size());
        if (f.snapshotTimestamp() != null) {
            p.addValue("snapshotTimestamp", f.snapshotTimestamp());
        }
        return p;
    }

    public List<Row> rows(Filter f, boolean all) {
        return jdbc.query(resultSql(f) + "select * from results order by warehouse_code,material_code,material_id,warehouse_id,counterparty"
            + (all ? "" : " limit :limit offset :offset"), params(f), (rs,n) -> row(rs));
    }

    public long count(Filter f) {
        return jdbc.queryForObject(resultSql(f)+"select count(*) from results",params(f),Long.class);
    }

    public List<WarehouseOverview.WarehouseUnitTotal> warehouseTotals(Filter f) {
        return jdbc.query(resultSql(f) + """
            select warehouse_id,min(warehouse_code) warehouse_code,min(warehouse_name) warehouse_name,
              uom,min(uom_short_name) uom_short_name,min(uom_short_name_ru) uom_short_name_ru,
              min(uom_short_name_uz) uom_short_name_uz,
              sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,
              sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,
              sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,
              sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount
            from results group by warehouse_id,uom order by warehouse_code,warehouse_id,uom
            """,params(f),(rs,n)->new WarehouseOverview.WarehouseUnitTotal(
                rs.getObject("warehouse_id",UUID.class),rs.getString("warehouse_code"),rs.getString("warehouse_name"),
                rs.getString("uom"),rs.getString("uom_short_name"),rs.getString("uom_short_name_ru"),
                rs.getString("uom_short_name_uz"),decimal(rs,"opening_quantity"),decimal(rs,"opening_amount"),
                decimal(rs,"receipt_quantity"),decimal(rs,"receipt_amount"),decimal(rs,"issue_quantity"),
                decimal(rs,"issue_amount"),decimal(rs,"closing_quantity"),decimal(rs,"closing_amount")));
    }

    public List<Total> totals(Filter f) {
        return jdbc.query(resultSql(f) + """
            select uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,
            sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,
            sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,
            sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount
            from results group by uom,uom_short_name,uom_short_name_ru,uom_short_name_uz order by uom
            """, params(f), (rs,n) -> new Total(rs.getString("uom"),rs.getString("uom_short_name"),rs.getString("uom_short_name_ru"),
                rs.getString("uom_short_name_uz"),decimal(rs,"opening_quantity"),decimal(rs,"opening_amount"),
                decimal(rs,"receipt_quantity"),decimal(rs,"receipt_amount"),decimal(rs,"issue_quantity"),decimal(rs,"issue_amount"),
                decimal(rs,"closing_quantity"),decimal(rs,"closing_amount")));
    }

    private Row row(ResultSet rs) throws SQLException {
        BigDecimal quantity=rs.getBigDecimal("closing_quantity"),amount=rs.getBigDecimal("closing_amount");
        if (quantity.signum()==0) { quantity=rs.getBigDecimal("receipt_quantity"); amount=rs.getBigDecimal("receipt_amount"); }
        if (quantity.signum()==0) { quantity=rs.getBigDecimal("issue_quantity"); amount=rs.getBigDecimal("issue_amount"); }
        if (quantity.signum()==0) { quantity=rs.getBigDecimal("opening_quantity"); amount=rs.getBigDecimal("opening_amount"); }
        String price=quantity.signum()==0 ? "0" : amount.divide(quantity,6,RoundingMode.HALF_UP).toPlainString();
        String party=rs.getString("counterparty");
        String keyId = rs.getString("material_id") != null ? rs.getString("material_id") : rs.getString("material_group_id");
        return new Row(rs.getString("warehouse_id")+":"+keyId+":"+party+":"+rs.getString("uom"),
            rs.getObject("material_id",UUID.class),rs.getObject("warehouse_id",UUID.class),
            rs.getString("warehouse_code"),rs.getString("warehouse_name"),rs.getObject("material_group_id",UUID.class),rs.getString("group_code"),
            rs.getString("material_group_name"),rs.getString("material_code"),
            rs.getString("material_name"),rs.getString("uom"),rs.getString("uom_short_name"),rs.getString("uom_short_name_ru"),
            rs.getString("uom_short_name_uz"),party,"—",price,
            decimal(rs,"opening_quantity"),decimal(rs,"opening_amount"),decimal(rs,"receipt_quantity"),decimal(rs,"receipt_amount"),
            decimal(rs,"issue_quantity"),decimal(rs,"issue_amount"),decimal(rs,"closing_quantity"),decimal(rs,"closing_amount"));
    }

    private static String decimal(ResultSet rs,String field) throws SQLException { return rs.getBigDecimal(field).toPlainString(); }

    public PageResponse<Map<String,Object>> references(String kind, Filter f) {
        String sql;
        var params = scope().addValue("limit", f.size()).addValue("offset", f.page() * f.size());
        if (f.search() != null && !f.search().isBlank()) {
            params.addValue("search", "%" + f.search().trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (kind.equals("MATERIAL")) {
            sql = "with refs as (select distinct m.id, m.code, m.name from materials m where m.tenant_id=:tenant and m.company_id=:company and coalesce(m.is_delete,false)=false";
            if (f.materialType() != null) {
                sql += " and m.material_type=:materialType";
                params.addValue("materialType", f.materialType().name());
            }
            if (f.materialSubtype() != null) {
                sql += " and m.material_subtype=:materialSubtype";
                params.addValue("materialSubtype", f.materialSubtype().name());
            }
            if (f.search() != null && !f.search().isBlank()) {
                sql += " and (lower(m.code) like :search escape '!' or lower(m.name) like :search escape '!')";
            }
            sql += ") ";
        } else if (kind.equals("WAREHOUSE")) {
            sql = "with refs as (select distinct w.id, w.code, w.name from warehouses w where w.tenant_id=:tenant and w.company_id=:company and coalesce(w.is_delete,false)=false";
            if (!f.warehouseIds().isEmpty()) {
                sql += " and w.id in (:scopedWarehouses)";
                params.addValue("scopedWarehouses",f.warehouseIds());
            }
            if (f.warehouseId() != null) {
                sql += " and w.id=:warehouse";
                params.addValue("warehouse", f.warehouseId());
            }
            if (f.search() != null && !f.search().isBlank()) {
                sql += " and (lower(w.code) like :search escape '!' or lower(w.name) like :search escape '!')";
            }
            sql += ") ";
        } else if (kind.equals("COUNTERPARTY")) {
            sql = "with refs as (select distinct s.id, coalesce(s.name,'—') code, s.name name from suppliers s where s.tenant_id=:tenant and s.company_id=:company and s.name is not null and trim(s.name)<>''";
            if (f.search() != null && !f.search().isBlank()) {
                sql += " and lower(s.name) like :search escape '!'";
            }
            sql += ") ";
        } else if (kind.equals("ACCOUNT")) {
            return com.enterprise.mm.payload.PageResponse.from(java.util.List.of());
        } else {
            return com.enterprise.mm.payload.PageResponse.from(java.util.List.of());
        }
        long total = jdbc.queryForObject(sql + "select count(*) from refs", params, Long.class);
        var content = jdbc.queryForList(sql + "select * from refs order by code,id limit :limit offset :offset", params);
        return new com.enterprise.mm.payload.PageResponse<>(content, f.page(), f.size(), total, (int) Math.ceil((double) total / f.size()));
    }

    public MaterialCard materialCard(UUID materialId, UUID warehouseId, LocalDate from, LocalDate to, int page, int size, String currencyCode) {
        return materialCard(materialId,warehouseId,from,to,page,size,currencyCode,List.of());
    }

    public MaterialCard materialCard(UUID materialId, UUID warehouseId, LocalDate from, LocalDate to, int page, int size, String currencyCode, List<UUID> warehouseIds) {
        var baseParams = scope().addValue("material", materialId);

        var materialInfo = jdbc.query("""
            select m.id, m.code material_code, m.name material_name, coalesce(u.code,'—') uom,
              coalesce(nullif(trim(u.measurement_name_short),''),nullif(trim(u.measurement_name_long),'')) uom_short_name,
              coalesce(nullif(trim(u.measurement_name_short_ru),''),nullif(trim(u.measurement_name_long_ru),'')) uom_short_name_ru,
              coalesce(nullif(trim(u.measurement_name_short_uz),''),nullif(trim(u.measurement_name_long_uz),'')) uom_short_name_uz
            from materials m
            left join unit_of_measures u on u.id=m.unit_of_measure_id and u.tenant_id=:tenant
            where m.id=:material and m.tenant_id=:tenant and m.company_id=:company
            """, baseParams, (rs, n) -> new String[]{
                rs.getString("material_code"), rs.getString("material_name"), rs.getString("uom"),
                rs.getString("uom_short_name"), rs.getString("uom_short_name_ru"), rs.getString("uom_short_name_uz")
            }).stream().findFirst().orElse(new String[]{"—", "—", "—", "—", "—", "—"});

        String warehouseCode = "ALL", warehouseName = "Все склады";
        if (warehouseId != null) {
            var whInfo = jdbc.query("select code, name from warehouses where id=:warehouse and tenant_id=:tenant and company_id=:company",
                baseParams.addValue("warehouse", warehouseId), (rs, n) -> new String[]{rs.getString("code"), rs.getString("name")})
                .stream().findFirst().orElse(null);
            if (whInfo != null) {
                warehouseCode = whInfo[0];
                warehouseName = whInfo[1];
            }
        }

        String whClause = warehouseId != null ? " and warehouse_id=:warehouse" : "";
        if (!warehouseIds.isEmpty()) whClause += " and warehouse_id in (:scopedWarehouses)";
        var openingParams = scope().addValue("scopedWarehouses",warehouseIds).addValue("material", materialId).addValue("from", from);
        if (warehouseId != null) openingParams.addValue("warehouse", warehouseId);

        var openingRow = jdbc.query(OPERATIONAL_LEDGER + """
            select coalesce(sum(quantity), 0) opening_qty, coalesce(sum(amount), 0) opening_amt
            from enriched where material_id=:material and movement_date < :from""" + whClause,
            openingParams, (rs, n) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)}
        ).stream().findFirst().orElse(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});

        BigDecimal openingQty = openingRow[0] != null ? openingRow[0] : BigDecimal.ZERO;
        BigDecimal openingAmt = openingRow[1] != null ? openingRow[1] : BigDecimal.ZERO;
        BigDecimal openingPrice = openingQty.signum() == 0 ? BigDecimal.ZERO : openingAmt.divide(openingQty, 4, RoundingMode.HALF_UP);

        var periodParams = scope().addValue("scopedWarehouses",warehouseIds).addValue("material", materialId).addValue("from", from).addValue("to", to)
            .addValue("openingQty", openingQty).addValue("openingAmt", openingAmt)
            .addValue("limit", size).addValue("offset", page * size);
        if (warehouseId != null) periodParams.addValue("warehouse", warehouseId);

        String periodSql = OPERATIONAL_LEDGER + """
            , period_movements as (
              select d.id, d.movement_date,
                coalesce(d.reference_type, d.movement_type, 'STOCK_MOVEMENT') document_type,
                cast(coalesce(d.reference_id, d.id) as text) document_number,
                d.reference_id,
                d.movement_type operation_type,
                d.counterparty,
                '—' corresponding_account,
                case when d.quantity > 0 then d.quantity else 0 end receipt_quantity,
                case when d.quantity > 0 or (d.quantity = 0 and d.amount > 0) then d.amount else 0 end receipt_amount,
                case when d.quantity < 0 then -d.quantity else 0 end issue_quantity,
                case when d.quantity < 0 or (d.quantity = 0 and d.amount < 0) then -d.amount else 0 end issue_amount,
                d.quantity raw_qty,
                d.amount raw_amt
              from enriched d
              where d.material_id=:material and d.movement_date between :from and :to""" + whClause + """
            ), with_running as (
              select p.*,
                :openingQty + sum(p.raw_qty) over (order by p.movement_date asc, p.id asc rows between unbounded preceding and current row) running_quantity,
                :openingAmt + sum(p.raw_amt) over (order by p.movement_date asc, p.id asc rows between unbounded preceding and current row) running_amount
              from period_movements p
            )
            """;

        Long totalCount = jdbc.queryForObject(periodSql + " select count(*) from period_movements", periodParams, Long.class);
        long count = totalCount != null ? totalCount : 0L;

        var totalsRow = jdbc.query(periodSql + """
            select coalesce(sum(receipt_quantity), 0) rec_qty, coalesce(sum(receipt_amount), 0) rec_amt,
                   coalesce(sum(issue_quantity), 0) iss_qty, coalesce(sum(issue_amount), 0) iss_amt
            from period_movements
            """, periodParams, (rs, n) -> new BigDecimal[]{
                rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)
            }).stream().findFirst().orElse(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

        BigDecimal totalRecQty = totalsRow[0] != null ? totalsRow[0] : BigDecimal.ZERO;
        BigDecimal totalRecAmt = totalsRow[1] != null ? totalsRow[1] : BigDecimal.ZERO;
        BigDecimal totalIssQty = totalsRow[2] != null ? totalsRow[2] : BigDecimal.ZERO;
        BigDecimal totalIssAmt = totalsRow[3] != null ? totalsRow[3] : BigDecimal.ZERO;

        BigDecimal closingQty = openingQty.add(totalRecQty).subtract(totalIssQty);
        BigDecimal closingAmt = openingAmt.add(totalRecAmt).subtract(totalIssAmt);
        BigDecimal closingPrice = closingQty.signum() == 0 ? BigDecimal.ZERO : closingAmt.divide(closingQty, 4, RoundingMode.HALF_UP);

        var entries = jdbc.query(periodSql + """
            select * from with_running order by movement_date asc, id asc limit :limit offset :offset
            """, periodParams, (rs, n) -> {
                BigDecimal rq = rs.getBigDecimal("receipt_quantity"), ra = rs.getBigDecimal("receipt_amount");
                BigDecimal iq = rs.getBigDecimal("issue_quantity"), ia = rs.getBigDecimal("issue_amount");
                BigDecimal rawQ = rs.getBigDecimal("raw_qty"), rawA = rs.getBigDecimal("raw_amt");
                BigDecimal p = rawQ.signum() == 0 ? BigDecimal.ZERO : rawA.abs().divide(rawQ.abs(), 4, RoundingMode.HALF_UP);
                return new MaterialCardEntry(
                    rs.getObject("id", UUID.class),
                    rs.getDate("movement_date").toLocalDate(),
                    rs.getString("document_number"),
                    rs.getString("document_type"),
                    rs.getObject("reference_id", UUID.class),
                    rs.getString("operation_type"),
                    rs.getString("counterparty"),
                    rs.getString("corresponding_account"),
                    p.toPlainString(),
                    rq.toPlainString(),
                    ra.toPlainString(),
                    iq.toPlainString(),
                    ia.toPlainString(),
                    rs.getBigDecimal("running_quantity").toPlainString(),
                    rs.getBigDecimal("running_amount").toPlainString()
                );
            });

        var pagedEntries = new com.enterprise.mm.payload.PageResponse<>(
            entries, page, size, count, (int) Math.ceil((double) count / size)
        );

        return new MaterialCard(
            materialId, materialInfo[0], materialInfo[1], materialInfo[2],
            materialInfo[3], materialInfo[4], materialInfo[5],
            warehouseId, warehouseCode, warehouseName,
            null, null,
            from, to, currencyCode,
            openingQty.toPlainString(), openingAmt.toPlainString(), openingPrice.toPlainString(),
            totalRecQty.toPlainString(), totalRecAmt.toPlainString(),
            totalIssQty.toPlainString(), totalIssAmt.toPlainString(),
            closingQty.toPlainString(), closingAmt.toPlainString(), closingPrice.toPlainString(),
            pagedEntries
        );
    }
}
