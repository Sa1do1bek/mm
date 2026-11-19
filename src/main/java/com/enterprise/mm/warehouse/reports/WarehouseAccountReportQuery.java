package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.enums.MaterialType;
import java.math.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import com.enterprise.mm.payload.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

/** Warehouse positions across source accounts, including custody, use, products,
 * shortages and capital acquisitions. Reconstruct dates from one exact snapshot. */
@Repository
public class WarehouseAccountReportQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final WarehouseReportQuery operational;
    private static final String UNMAPPED_REFERENCES = """
        from warehouse_account_entries e
        join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED'
          and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports
              where tenant_id=e.tenant_id and company_id=e.company_id and status='COMPLETED')
        left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id
            and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id
        left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id
            and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id
        where e.tenant_id=:tenant and e.company_id=:company
          and ((coalesce(e.material_id,lm.master_id) is null and e.material_source_id<>repeat('0',32))
            or (coalesce(e.warehouse_id,lw.master_id) is null and e.warehouse_source_id<>repeat('0',32)))
        """;

    public WarehouseAccountReportQuery(NamedParameterJdbcTemplate jdbc, WarehouseReportQuery operational) {
        this.jdbc = jdbc;
        this.operational = operational;
    }
    public boolean available() {
        boolean exists=Boolean.TRUE.equals(jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->{
            try(var tables=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"warehouse_account_imports",new String[]{"TABLE"})){return tables.next();}
        }));
        if(!exists)return false;
        return jdbc.queryForObject("select count(*) from warehouse_account_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED'",operational.scope(),Long.class)>0;
    }
    public long unmappedRows() {
        return jdbc.queryForObject("select count(*) " + UNMAPPED_REFERENCES,operational.scope(),Long.class);
    }
    public LocalDate coverageStart() {
        return jdbc.queryForObject("select min(e.source_date) from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED' and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED') where e.tenant_id=:tenant and e.company_id=:company",operational.scope(),LocalDate.class);
    }
    public LocalDate snapshotDate() {
        return jdbc.queryForObject("select max(snapshot_date) from warehouse_account_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED'",operational.scope(),LocalDate.class);
    }
    public List<Map<String,Object>> accounts() {
        return jdbc.queryForList("select e.account_code as code,coalesce(c.name,min(e.account_name)) as name from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED' and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED') left join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id and c.account_code=e.account_code and c.is_delete=false where e.tenant_id=:tenant and e.company_id=:company group by e.account_code,c.name order by e.account_code",operational.scope());
    }
    public PageResponse<Map<String,Object>> references(String kind, Filter f) {
        if (kind.equals("COUNTERPARTY")) {
            String sql = "with refs as (" +
                "select distinct md5('counterparty:'||e.company_id||':'||trim(e.counterparty))::uuid id, trim(e.counterparty) code, trim(e.counterparty) name " +
                "from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED' " +
                "where e.tenant_id=:tenant and e.company_id=:company and e.counterparty is not null and trim(e.counterparty)<>'' and trim(e.counterparty)<>'—' ";
            if (f.warehouseId() != null) {
                sql += "and coalesce(e.warehouse_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid)=:warehouse ";
            }
            if (f.accountCode() != null) {
                sql += "and e.account_code=:accountCode ";
            }
            if (f.search() != null && !f.search().isBlank()) {
                sql += "and lower(e.counterparty) like :search escape '!' ";
            }
            sql += "union " +
                "select distinct coalesce(s.id, md5('supplier:'||s.company_id||':'||trim(s.name))::uuid) id, coalesce(s.name,'—') code, s.name " +
                "from suppliers s where s.tenant_id=:tenant and s.company_id=:company and s.name is not null and trim(s.name)<>'' ";
            if (f.search() != null && !f.search().isBlank()) {
                sql += "and lower(s.name) like :search escape '!' ";
            }
            sql += ") ";
            long total = jdbc.queryForObject(sql + "select count(*) from refs", params(f), Long.class);
            var content = jdbc.queryForList(sql + "select * from refs order by name,id limit :limit offset :offset", params(f));
            return new com.enterprise.mm.payload.PageResponse<>(content, f.page(), f.size(), total, (int) Math.ceil((double) total / f.size()));
        }
        if (kind.equals("ACCOUNT")) {
            String sql = "with refs as (" +
                "select coalesce(c.id::text,e.account_code) id, e.account_code code, coalesce(c.name,min(e.account_name)) name " +
                "from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id " +
                "and r.tenant_id=e.tenant_id and r.company_id=e.company_id and r.status='COMPLETED' " +
                "and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports " +
                "where tenant_id=:tenant and company_id=:company and status='COMPLETED') " +
                "left join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id " +
                "and c.account_code=e.account_code and c.is_delete=false " +
                links() +
                "where e.tenant_id=:tenant and e.company_id=:company ";
            if (f.warehouseId() != null) {
                sql += "and coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid)=:warehouse ";
            }
            if (f.search() != null && !f.search().isBlank()) {
                sql += "and (lower(e.account_code) like :search escape '!' or lower(coalesce(c.name,e.account_name)) like :search escape '!') ";
            }
            sql += "group by e.account_code,c.id,c.name) ";
            long total = jdbc.queryForObject(sql + "select count(*) from refs", params(f), Long.class);
            var content = jdbc.queryForList(sql + "select * from refs order by code limit :limit offset :offset", params(f));
            return new com.enterprise.mm.payload.PageResponse<>(content, f.page(), f.size(), total, (int) Math.ceil((double) total / f.size()));
        }
        boolean material=kind.equals("MATERIAL");
        String id=material?"coalesce(e.material_id,lm.master_id,md5('1c-material:'||e.company_id||':'||e.material_reference_type||':'||e.material_source_id)::uuid)":"coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid)";
        String prefix=material?"material":"warehouse";
        String sql="with refs as (select distinct "+id+" id,e."+prefix+"_code code,e."+prefix+"_name name from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED' and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports where tenant_id=:tenant and company_id=:company and status='COMPLETED')"+links()+" left join materials m on m.id=coalesce(e.material_id,lm.master_id) and m.tenant_id=e.tenant_id and m.company_id=e.company_id where e.tenant_id=:tenant and e.company_id=:company";
        if(f.warehouseId()!=null)sql+=" and coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid)=:warehouse";
        if(!f.warehouseIds().isEmpty())sql+=" and coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid) in (:scopedWarehouses)";
        if(f.accountCode()!=null)sql+=" and e.account_code=:accountCode";
        if(material) {
            if(f.sourceType()!=null)sql+=" and coalesce(lm.source_type,e.source_type)=:sourceType";
            if(f.materialSubtype()!=null)sql+=" and coalesce(lm.source_type,e.source_type)=:materialSubtype";
            if(f.materialType()!=null)sql+=f.materialType()==MaterialType.TMB?" and coalesce(lm.source_type,e.source_type) in ('MATERIAL','EQUIPMENT','ANIMALS','FINISHED_GOODS','SEMI_FINISHED_GOODS','GOODS')":" and coalesce(lm.source_type,e.source_type)=:materialType";
        }
        if(f.search()!=null && !f.search().isBlank()) sql+=" and (lower(e."+prefix+"_code) like :search escape '!' or lower(e."+prefix+"_name) like :search escape '!')";
        sql+=") ";
        long total=jdbc.queryForObject(sql+"select count(*) from refs",params(f),Long.class);
        var content=jdbc.queryForList(sql+"select * from refs order by code,id limit :limit offset :offset",params(f));
        return new com.enterprise.mm.payload.PageResponse<>(content,f.page(),f.size(),total,(int)Math.ceil((double)total/f.size()));
    }
    private static String links(){return " left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id ";}
    private static final String IDENTITY="material_id,warehouse_id,warehouse_code,warehouse_name,material_group_id,group_code,material_group_name,material_code,material_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,account_code,account_name,source_material_type,stock_category,source_type";

    private String resultSql(Filter f) {
        // The source snapshot already contains the 10xx opening and all earlier
        // 1C movements. Only later operational MM changes are added to it.
        String nativeLedger=operational.ledger().replace("and posted_at is not null", "and coalesce(reference_type,'') not in ('OPENING_STOCK_EXACT','OPENING_STOCK') and posted_at is not null");
        if (f.snapshotTimestamp() != null) {
            nativeLedger = nativeLedger.replace("and status in ('POSTED','CANCELLED')", "and status in ('POSTED','CANCELLED') and posted_at <= :snapshotTimestamp");
            nativeLedger = nativeLedger.replace("when issue_review.status='RESOLVED' then", "when issue_review.status='RESOLVED' and issue_review.reviewed_at <= :snapshotTimestamp then");
        }
        String sql="""
            with normalized as (
              select e.id,e.kind,e.source_date,e.quantity,e.amount,e.corresponding_account,e.counterparty,
                coalesce(e.material_id,lm.master_id,md5('1c-material:'||e.company_id||':'||e.material_reference_type||':'||e.material_source_id)::uuid) material_id,
                coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid) warehouse_id,
                e.warehouse_code,e.warehouse_name,m.material_group_id,g.code group_code,g.name material_group_name,
                e.material_code,e.material_name,e.uom,e.uom_name_ru uom_short_name,e.uom_name_ru uom_short_name_ru,e.uom_name_ru uom_short_name_uz,
                e.account_code,coalesce(c.name,e.account_name) account_name,coalesce(lm.source_material_type,e.source_material_type) source_material_type,e.stock_category,coalesce(lm.source_type,e.source_type) source_type
              from warehouse_account_entries e
              join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id and r.company_id=e.company_id and r.status='COMPLETED'
                and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports
                    where tenant_id=:tenant and company_id=:company and status='COMPLETED')
              left join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id
                and c.account_code=e.account_code and c.is_delete=false
 left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id
              left join materials m on m.id=coalesce(e.material_id,lm.master_id) and m.tenant_id=:tenant and m.company_id=:company
              left join material_groups g on g.id=m.material_group_id and g.tenant_id=:tenant and g.company_id=:company
              where e.tenant_id=:tenant and e.company_id=:company and (e.kind='BALANCE' or e.source_date>=:from)
            ), native as (
            """+nativeLedger+"""
              select n.*,case when m.material_type='FIXED_ASSET' then 'FIXED_ASSET' when m.material_type='SERVICES' then 'SERVICES'
                else coalesce(m.material_subtype,'MATERIAL') end source_type
              from enriched n join materials m on m.id=n.material_id and m.tenant_id=:tenant and m.company_id=:company
            ), contributions as (
              select """+" "+IDENTITY+"""
                ,corresponding_account,counterparty,
                case when kind='BALANCE' then quantity else -quantity end opening_quantity,
                case when kind='BALANCE' then amount else -amount end opening_amount,
                case when kind='MOVEMENT' and source_date<=:to and quantity>0 then quantity else 0 end receipt_quantity,
                case when kind='MOVEMENT' and source_date<=:to and (quantity>0 or (quantity=0 and amount>0)) then amount else 0 end receipt_amount,
                case when kind='MOVEMENT' and source_date<=:to and quantity<0 then -quantity else 0 end issue_quantity,
                case when kind='MOVEMENT' and source_date<=:to and (quantity<0 or (quantity=0 and amount<0)) then -amount else 0 end issue_amount,
                case when kind='BALANCE' then quantity when source_date>:to then -quantity else 0 end closing_quantity,
                case when kind='BALANCE' then amount when source_date>:to then -amount else 0 end closing_amount
              from normalized
              union all
              select n.material_id,n.warehouse_id,n.warehouse_code,n.warehouse_name,n.material_group_id,n.group_code,n.material_group_name,
                n.material_code,n.material_name,n.uom,n.uom_short_name,n.uom_short_name_ru,n.uom_short_name_uz,
                coalesce(nullif(trim(n.account_code_snapshot),''),'—'),
                coalesce((select c.name from chart_of_accounts c where c.tenant_id=:tenant and c.company_id=:company
                    and c.account_code=n.account_code_snapshot and c.is_delete=false), nullif(trim(n.account_code_snapshot),''), 'Счёт не указан (MM)'),
                case n.source_type when 'EQUIPMENT' then 'Оборудование' when 'ANIMALS' then 'Животные'
                when 'FIXED_ASSET' then 'Основные средства' when 'SERVICES' then 'Услуги' else 'Материалы' end,'MM_OPERATIONAL',n.source_type,
                coalesce(nullif(trim(n.counter_account_snapshot),''),'—'),n.counterparty,
                case when n.movement_date<:from then n.quantity else 0 end,
                case when n.movement_date<:from then n.amount else 0 end,
                case when n.movement_date between :from and :to and n.quantity>0 then n.quantity else 0 end,
                case when n.movement_date between :from and :to and (n.quantity>0 or (n.quantity=0 and n.amount>0)) then n.amount else 0 end,
                case when n.movement_date between :from and :to and n.quantity<0 then -n.quantity else 0 end,
                case when n.movement_date between :from and :to and (n.quantity<0 or (n.quantity=0 and n.amount<0)) then -n.amount else 0 end,
                n.quantity,n.amount
              from native n
              where n.movement_date<=:to
            ), filtered as (select * from contributions where 1=1
            """;
        if(f.warehouseId()!=null)sql+=" and warehouse_id=:warehouse";
        if(!f.warehouseIds().isEmpty())sql+=" and warehouse_id in (:scopedWarehouses)";
        if(f.materialId()!=null)sql+=" and material_id=:material";
        if(f.materialGroupId()!=null)sql+=" and material_group_id=:groupId";
        if(f.accountCode()!=null)sql+=" and account_code=:accountCode";
        if(f.sourceType()!=null)sql+=" and source_type=:sourceType";
        if(f.materialSubtype()!=null)sql+=" and source_type=:materialSubtype";
        if(f.materialType()!=null)sql+=f.materialType()==MaterialType.TMB?" and source_type in ('MATERIAL','EQUIPMENT','ANIMALS','FINISHED_GOODS','SEMI_FINISHED_GOODS','GOODS')":" and source_type=:materialType";
        if(f.search()!=null&&!f.search().isBlank())sql+=" and (lower(material_code) like :search escape '!' or lower(material_name) like :search escape '!')";
        if(f.ownerPartyId()!=null)sql+=" and counterparty in (select counterparty_name from warehouse_custody_agreements where owner_id=:ownerPartyId and tenant_id=:tenant and company_id=:company)";
        if(f.type()==Type.MATERIALS_IN_USE)sql+=" and (account_code like '014%' or source_type='IN_USE')";
        if(f.type()==Type.CUSTODY_STATEMENT)sql+=" and (account_code like '002%' or stock_category='CUSTODY' or counterparty<>'—')";
        if(f.type()==Type.INSTALLATION_EQUIPMENT)sql+=" and (account_code in ('07','0820') or source_material_type='EQUIPMENT' or source_type='EQUIPMENT')";
        if (f.type() == Type.GROUP_SUMMARY) {
            String groupIdentity = "cast(null as uuid) material_id,warehouse_id,warehouse_code,warehouse_name,material_group_id,coalesce(group_code,'—') group_code,coalesce(material_group_name,'Без группы') material_group_name,coalesce(group_code,'—') material_code,coalesce(material_group_name,'Без группы') material_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,account_code,account_name,source_material_type,stock_category,source_type";
            String groupBy = "warehouse_id,warehouse_code,warehouse_name,material_group_id,group_code,material_group_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,account_code,account_name,source_material_type,stock_category,source_type";
            sql += "), aggregated as (select " + groupIdentity + ",cast('—' as varchar) counterparty,cast('—' as varchar) corresponding_account,";
            sql += "sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount from filtered";
            sql += " group by " + groupBy + "), results as (select * from aggregated where ";
        } else if (f.type() == Type.INVENTORY_COMPARISON) {
            sql += "), aggregated as (select " + IDENTITY + ",cast('СООТВЕТСТВУЕТ' as varchar) counterparty,cast('1090' as varchar) corresponding_account,";
            sql += "sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,0 receipt_quantity,0 receipt_amount,0 issue_quantity,0 issue_amount,sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount from filtered";
            sql += " group by " + IDENTITY + "), results as (select * from aggregated where ";
        } else if (f.type() == Type.RECEIPTS) {
            sql += "), aggregated as (select " + IDENTITY + ",case when count(distinct counterparty)=1 then min(counterparty) else '—' end counterparty,case when count(distinct corresponding_account)=1 then min(corresponding_account) else '—' end corresponding_account,";
            sql += "0 opening_quantity,0 opening_amount,sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,0 issue_quantity,0 issue_amount,sum(receipt_quantity) closing_quantity,sum(receipt_amount) closing_amount from filtered where receipt_quantity<>0 or receipt_amount>0";
            sql += " group by " + IDENTITY + "), results as (select * from aggregated where ";
        } else if (f.type() == Type.ISSUES) {
            sql += "), aggregated as (select " + IDENTITY + ",case when count(distinct counterparty)=1 then min(counterparty) else '—' end counterparty,case when count(distinct corresponding_account)=1 then min(corresponding_account) else '—' end corresponding_account,";
            sql += "0 opening_quantity,0 opening_amount,0 receipt_quantity,0 receipt_amount,sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,-sum(issue_quantity) closing_quantity,-sum(issue_amount) closing_amount from filtered where issue_quantity<>0 or issue_amount>0";
            sql += " group by " + IDENTITY + "), results as (select * from aggregated where ";
        } else {
            sql += "), aggregated as (select " + IDENTITY + ",case when count(distinct counterparty)=1 then min(counterparty) else '—' end counterparty,case when count(distinct corresponding_account)=1 then min(corresponding_account) else '—' end corresponding_account,";
            sql += "sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount from filtered";
            sql += " group by " + IDENTITY + "), results as (select * from aggregated where ";
        }
        String nonzero;
        if (f.type() == Type.STOCK_BALANCES || f.type() == Type.INVENTORY_COMPARISON) {
            nonzero = "closing_quantity<>0 or closing_amount<>0";
        } else if (f.type() == Type.NONMOVING_STOCK) {
            nonzero = "receipt_quantity=0 and issue_quantity=0 and (opening_quantity<>0 or closing_quantity<>0)";
        } else {
            nonzero = "opening_quantity<>0 or opening_amount<>0 or receipt_quantity<>0 or receipt_amount<>0 or issue_quantity<>0 or issue_amount<>0 or closing_quantity<>0 or closing_amount<>0";
        }
        return sql + nonzero + ") ";
    }
    private MapSqlParameterSource params(Filter f) {
        String search=f.search()==null?"":f.search().trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
        var p = operational.scope().addValue("scopedWarehouses",f.warehouseIds()).addValue("from",f.from()).addValue("to",f.to()).addValue("warehouse",f.warehouseId()).addValue("material",f.materialId())
            .addValue("groupId",f.materialGroupId()).addValue("accountCode",f.accountCode()).addValue("sourceType",f.sourceType())
            .addValue("materialSubtype",f.materialSubtype()==null?null:f.materialSubtype().name()).addValue("materialType",f.materialType()==null?null:f.materialType().name())
            .addValue("ownerPartyId", f.ownerPartyId())
            .addValue("search","%"+search+"%").addValue("limit",f.size()).addValue("offset",(long)f.page()*f.size());
        if (f.snapshotTimestamp() != null) {
            p.addValue("snapshotTimestamp", f.snapshotTimestamp());
        }
        return p;
    }
    public List<Row> rows(Filter f,boolean all) {
        return jdbc.query(resultSql(f)+"select * from results order by warehouse_code,account_code,material_code,material_id,warehouse_id"+(all?"":" limit :limit offset :offset"),params(f),(rs,n)->row(rs));
    }
    public long count(Filter f) {return jdbc.queryForObject(resultSql(f)+"select count(*) from results",params(f),Long.class);}
    public List<WarehouseOverview.WarehouseUnitTotal> warehouseTotals(Filter f) {
        return jdbc.query(resultSql(f)+"""
            select warehouse_id,min(warehouse_code) warehouse_code,min(warehouse_name) warehouse_name,
              uom,min(uom_short_name) uom_short_name,min(uom_short_name_ru) uom_short_name_ru,
              min(uom_short_name_uz) uom_short_name_uz,
              sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,
              sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,
              sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,
              sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount
            from results group by warehouse_id,uom order by warehouse_code,warehouse_id,uom
            """,params(f),(r,n)->new WarehouseOverview.WarehouseUnitTotal(
                r.getObject("warehouse_id",UUID.class),r.getString("warehouse_code"),r.getString("warehouse_name"),
                r.getString("uom"),r.getString("uom_short_name"),r.getString("uom_short_name_ru"),
                r.getString("uom_short_name_uz"),decimal(r,"opening_quantity"),decimal(r,"opening_amount"),
                decimal(r,"receipt_quantity"),decimal(r,"receipt_amount"),decimal(r,"issue_quantity"),
                decimal(r,"issue_amount"),decimal(r,"closing_quantity"),decimal(r,"closing_amount")));
    }
    public List<Total> totals(Filter f) {
        return jdbc.query(resultSql(f)+"select account_code,account_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz,sum(opening_quantity) opening_quantity,sum(opening_amount) opening_amount,sum(receipt_quantity) receipt_quantity,sum(receipt_amount) receipt_amount,sum(issue_quantity) issue_quantity,sum(issue_amount) issue_amount,sum(closing_quantity) closing_quantity,sum(closing_amount) closing_amount from results group by account_code,account_name,uom,uom_short_name,uom_short_name_ru,uom_short_name_uz order by account_code,uom",params(f),(r,n)->new Total(r.getString("uom"),r.getString("uom_short_name"),r.getString("uom_short_name_ru"),r.getString("uom_short_name_uz"),decimal(r,"opening_quantity"),decimal(r,"opening_amount"),decimal(r,"receipt_quantity"),decimal(r,"receipt_amount"),decimal(r,"issue_quantity"),decimal(r,"issue_amount"),decimal(r,"closing_quantity"),decimal(r,"closing_amount"),r.getString("account_code"),r.getString("account_name")));
    }
    private Row row(ResultSet r)throws SQLException {
        BigDecimal quantity=r.getBigDecimal("closing_quantity"),amount=r.getBigDecimal("closing_amount");
        if(quantity.signum()==0){quantity=r.getBigDecimal("receipt_quantity");amount=r.getBigDecimal("receipt_amount");}
        if(quantity.signum()==0){quantity=r.getBigDecimal("issue_quantity");amount=r.getBigDecimal("issue_amount");}
        if(quantity.signum()==0){quantity=r.getBigDecimal("opening_quantity");amount=r.getBigDecimal("opening_amount");}
        String price=quantity.signum()==0?"0":amount.divide(quantity,6,RoundingMode.HALF_UP).toPlainString();
        String keyId = r.getString("material_id") != null ? r.getString("material_id") : r.getString("material_group_id");
        return new Row(r.getString("warehouse_id")+":"+r.getString("account_code")+":"+keyId+":"+r.getString("uom"),
            r.getObject("material_id",UUID.class),r.getObject("warehouse_id",UUID.class),r.getString("warehouse_code"),r.getString("warehouse_name"),
            r.getObject("material_group_id",UUID.class),r.getString("group_code"),r.getString("material_group_name"),r.getString("material_code"),r.getString("material_name"),r.getString("uom"),r.getString("uom_short_name"),r.getString("uom_short_name_ru"),r.getString("uom_short_name_uz"),r.getString("counterparty"),r.getString("corresponding_account"),price,
            decimal(r,"opening_quantity"),decimal(r,"opening_amount"),decimal(r,"receipt_quantity"),decimal(r,"receipt_amount"),decimal(r,"issue_quantity"),decimal(r,"issue_amount"),decimal(r,"closing_quantity"),decimal(r,"closing_amount"),r.getString("account_code"),r.getString("account_name"),r.getString("source_material_type"),r.getString("stock_category"));
    }
    private static String decimal(ResultSet r,String field)throws SQLException{return r.getBigDecimal(field).toPlainString();}

    private record CardTotals(BigDecimal openingQty, BigDecimal openingAmt, long count,
        BigDecimal receiptQty, BigDecimal receiptAmt, BigDecimal issueQty, BigDecimal issueAmt) {}

    public MaterialCard materialCard(UUID materialId, UUID warehouseId, LocalDate from, LocalDate to, String accountCode, int page, int size, String currencyCode) {
        return materialCard(materialId,warehouseId,from,to,accountCode,page,size,currencyCode,List.of());
    }

    public MaterialCard materialCard(UUID materialId, UUID warehouseId, LocalDate from, LocalDate to, String accountCode, int page, int size, String currencyCode, List<UUID> warehouseIds) {
        var baseParams = operational.scope().addValue("scopedWarehouses",warehouseIds).addValue("material", materialId);

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
            }).stream().findFirst().orElse(null);

        if (materialInfo == null) {
            materialInfo = jdbc.query("""
                select material_code, material_name, uom, uom_name_ru, uom_name_ru, uom_name_ru
                from warehouse_account_entries where (material_id=:material or md5('1c-material:'||company_id||':'||material_reference_type||':'||material_source_id)::uuid=:material)
                  and tenant_id=:tenant and company_id=:company limit 1
                """, baseParams, (rs, n) -> new String[]{
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)
                }).stream().findFirst().orElse(new String[]{"—", "—", "—", "—", "—", "—"});
        }

        String warehouseCode = "ALL", warehouseName = "Все склады";
        if (warehouseId != null) {
            var whInfo = jdbc.query("select code, name from warehouses where id=:warehouse and tenant_id=:tenant and company_id=:company",
                baseParams.addValue("warehouse", warehouseId), (rs, n) -> new String[]{rs.getString("code"), rs.getString("name")})
                .stream().findFirst().orElse(null);
            if (whInfo != null) {
                warehouseCode = whInfo[0];
                warehouseName = whInfo[1];
            } else {
                var entryWh = jdbc.query("""
                    select warehouse_code, warehouse_name from warehouse_account_entries
                    where (warehouse_id=:warehouse or md5('1c-warehouse:'||company_id||':'||warehouse_source_id)::uuid=:warehouse)
                      and tenant_id=:tenant and company_id=:company limit 1
                    """, baseParams, (rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                    .stream().findFirst().orElse(null);
                if (entryWh != null) {
                    warehouseCode = entryWh[0];
                    warehouseName = entryWh[1];
                }
            }
        }

        String accountName = null;
        if (accountCode != null) {
            accountName = jdbc.query("select coalesce((select name from chart_of_accounts where account_code=:accountCode and tenant_id=:tenant and company_id=:company and is_delete=false), min(account_name)) from warehouse_account_entries where account_code=:accountCode and tenant_id=:tenant and company_id=:company",
                operational.scope().addValue("accountCode", accountCode), (rs, n) -> rs.getString(1)).stream().findFirst().orElse(accountCode);
        }

        String nativeLedger = operational.ledger()
            .replace("select * from stock_movements where tenant_id=:tenant and company_id=:company",
                "select * from stock_movements where tenant_id=:tenant and company_id=:company and material_id=:material")
            .replace("from stock_cost_movements c where c.tenant_id=:tenant and c.company_id=:company",
                "from stock_cost_movements c where c.tenant_id=:tenant and c.company_id=:company and c.material_id=:material")
            .replace("and posted_at is not null", "and coalesce(reference_type,'') not in ('OPENING_STOCK_EXACT','OPENING_STOCK') and posted_at is not null");
        String sql = """
            with normalized as (
              select e.id, e.kind, e.source_date, e.quantity, e.amount, e.corresponding_account, e.counterparty,
                coalesce(e.material_id,lm.master_id,md5('1c-material:'||e.company_id||':'||e.material_reference_type||':'||e.material_source_id)::uuid) material_id,
                coalesce(e.warehouse_id,lw.master_id,md5('1c-warehouse:'||e.company_id||':'||e.warehouse_source_id)::uuid) warehouse_id,
                e.warehouse_code, e.warehouse_name,
                e.material_code, e.material_name, e.uom,
                e.account_code, coalesce(c.name,e.account_name) account_name, e.stock_category,
                e.document_key
              from warehouse_account_entries e
              join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id and r.company_id=e.company_id and r.status='COMPLETED'
                and r.snapshot_date=(select max(snapshot_date) from warehouse_account_imports
                  where tenant_id=:tenant and company_id=:company and status='COMPLETED')
              left join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id
                and c.account_code=e.account_code and c.is_delete=false
              left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id
              left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id
              where e.tenant_id=:tenant and e.company_id=:company
                and coalesce(e.material_id,lm.master_id,md5('1c-material:'||e.company_id||':'||e.material_reference_type||':'||e.material_source_id)::uuid)=:material
            ), native as (
            """ + nativeLedger + """
              select n.*, coalesce(nullif(trim(n.account_code_snapshot),''), '—') account_code, 'MM Операционный' account_name
              from enriched n where n.material_id=:material
            ), all_movements as (
              select e.id, e.material_id, e.warehouse_id, e.account_code, e.source_date movement_date,
                coalesce(e.document_key, cast(e.id as text)) document_number,
                '1C_ACCOUNT_ENTRY' document_type,
                'SOURCE' movement_origin,
                null::uuid reference_id,
                case when e.quantity < 0 or (e.quantity = 0 and e.amount < 0) then 'ISSUE' else 'RECEIPT' end operation_type,
                e.counterparty, e.corresponding_account,
                e.quantity, e.amount
              from normalized e where e.kind='MOVEMENT'
              union all
              select n.id, n.material_id, n.warehouse_id, n.account_code, n.movement_date,
                cast(coalesce(n.reference_id, n.id) as text) document_number,
                coalesce(n.reference_type, n.movement_type, 'STOCK_MOVEMENT') document_type,
                'NATIVE' movement_origin,
                n.reference_id,
                n.movement_type operation_type,
                n.counterparty, coalesce(nullif(trim(n.counter_account_snapshot),''),'—') corresponding_account,
                n.quantity, n.amount
              from native n
            ), opening_balance as (
              select coalesce(sum(e.quantity), 0) opening_qty, coalesce(sum(e.amount), 0) opening_amt
              from (
                select quantity, amount from normalized where kind='BALANCE' and material_id=:material
                """
                + (warehouseId != null ? " and warehouse_id=:warehouse\n" : "")
                + (!warehouseIds.isEmpty() ? " and warehouse_id in (:scopedWarehouses)\n" : "")
                + (accountCode != null ? " and account_code=:accountCode\n" : "")
                + """
                union all
                -- The source balance is already the snapshot closing position. Reverse later
                -- source movements for earlier dates; add native movements before the period.
                select case when movement_origin='SOURCE' then -quantity else quantity end,
                  case when movement_origin='SOURCE' then -amount else amount end
                from all_movements where material_id=:material
                  and ((movement_origin='SOURCE' and movement_date>=:from)
                    or (movement_origin='NATIVE' and movement_date<:from))
                """
                + (warehouseId != null ? " and warehouse_id=:warehouse\n" : "")
                + (!warehouseIds.isEmpty() ? " and warehouse_id in (:scopedWarehouses)\n" : "")
                + (accountCode != null ? " and account_code=:accountCode\n" : "")
                + """
              ) e
            ), period_movements as (
              select m.id, m.movement_date, m.document_number, m.document_type, m.reference_id,
                m.operation_type, m.counterparty, m.corresponding_account,
                m.quantity raw_qty, m.amount raw_amt,
                case when m.quantity > 0 then m.quantity else 0 end receipt_quantity,
                case when m.quantity > 0 or (m.quantity = 0 and m.amount > 0) then m.amount else 0 end receipt_amount,
                case when m.quantity < 0 then -m.quantity else 0 end issue_quantity,
                case when m.quantity < 0 or (m.quantity = 0 and m.amount < 0) then -m.amount else 0 end issue_amount
              from all_movements m
              where m.material_id=:material and m.movement_date between :from and :to
              """
                + (warehouseId != null ? " and m.warehouse_id=:warehouse\n" : "")
                + (!warehouseIds.isEmpty() ? " and m.warehouse_id in (:scopedWarehouses)\n" : "")
                + (accountCode != null ? " and m.account_code=:accountCode\n" : "")
                + """
            ), with_running as (
              select p.*,
                (select opening_qty from opening_balance) + sum(p.raw_qty) over (order by p.movement_date asc, p.id asc rows between unbounded preceding and current row) running_quantity,
                (select opening_amt from opening_balance) + sum(p.raw_amt) over (order by p.movement_date asc, p.id asc rows between unbounded preceding and current row) running_amount
              from period_movements p
            )
            """;

        var queryParams = operational.scope().addValue("scopedWarehouses",warehouseIds).addValue("material", materialId)
            .addValue("from", from).addValue("to", to)
            .addValue("limit", size).addValue("offset", page * size);
        if (warehouseId != null) queryParams.addValue("warehouse", warehouseId);
        if (accountCode != null) queryParams.addValue("accountCode", accountCode);

        CardTotals totals = jdbc.queryForObject(sql + """
            select o.opening_qty, o.opening_amt, p.movement_count, p.rec_qty, p.rec_amt, p.iss_qty, p.iss_amt
            from opening_balance o cross join (
              select count(*) movement_count,
                coalesce(sum(receipt_quantity),0) rec_qty, coalesce(sum(receipt_amount),0) rec_amt,
                coalesce(sum(issue_quantity),0) iss_qty, coalesce(sum(issue_amount),0) iss_amt
              from period_movements
            ) p
            """, queryParams, (rs, n) -> new CardTotals(rs.getBigDecimal("opening_qty"),
                rs.getBigDecimal("opening_amt"), rs.getLong("movement_count"), rs.getBigDecimal("rec_qty"),
                rs.getBigDecimal("rec_amt"), rs.getBigDecimal("iss_qty"), rs.getBigDecimal("iss_amt")));

        BigDecimal openingQty = totals.openingQty();
        BigDecimal openingAmt = totals.openingAmt();
        BigDecimal openingPrice = openingQty.signum() == 0 ? BigDecimal.ZERO : openingAmt.divide(openingQty, 4, RoundingMode.HALF_UP);
        long count = totals.count();
        BigDecimal totalRecQty = totals.receiptQty();
        BigDecimal totalRecAmt = totals.receiptAmt();
        BigDecimal totalIssQty = totals.issueQty();
        BigDecimal totalIssAmt = totals.issueAmt();

        BigDecimal closingQty = openingQty.add(totalRecQty).subtract(totalIssQty);
        BigDecimal closingAmt = openingAmt.add(totalRecAmt).subtract(totalIssAmt);
        BigDecimal closingPrice = closingQty.signum() == 0 ? BigDecimal.ZERO : closingAmt.divide(closingQty, 4, RoundingMode.HALF_UP);

        var entries = jdbc.query(sql + """
            select * from with_running order by movement_date asc, id asc limit :limit offset :offset
            """, queryParams, (rs, n) -> {
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
            accountCode, accountName,
            from, to, currencyCode,
            openingQty.toPlainString(), openingAmt.toPlainString(), openingPrice.toPlainString(),
            totalRecQty.toPlainString(), totalRecAmt.toPlainString(),
            totalIssQty.toPlainString(), totalIssAmt.toPlainString(),
            closingQty.toPlainString(), closingAmt.toPlainString(), closingPrice.toPlainString(),
            pagedEntries
        );
    }
}
