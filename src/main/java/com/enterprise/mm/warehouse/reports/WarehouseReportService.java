package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.InventoryAccountingSettingsService;
import com.enterprise.mm.service.RoleDataScopeService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

@Service
public class WarehouseReportService {
    private final WarehouseReportQuery query;
    private final RoleDataScopeService access;
    private final InventoryAccountingSettingsService settings;
    private final NamedParameterJdbcTemplate jdbc;

    private final WarehouseAccountReportQuery accounts;

    @Autowired
    public WarehouseReportService(WarehouseReportQuery query, RoleDataScopeService access,
        InventoryAccountingSettingsService settings, NamedParameterJdbcTemplate jdbc, WarehouseAccountReportQuery accounts) {
        this.query=query;this.access=access;this.settings=settings;this.jdbc=jdbc;this.accounts=accounts;
    }
    public WarehouseReportService(WarehouseReportQuery query, RoleDataScopeService access,
        InventoryAccountingSettingsService settings, NamedParameterJdbcTemplate jdbc) {
        this(query,access,settings,jdbc,null);
    }
    public record Options(LocalDate today, LocalDate coverageStart, java.util.UUID warehouseId, String warehouseName,
        LocalDate historyCutoff, long unmappedHistoryRows, LocalDate accountSnapshotDate, java.util.List<java.util.Map<String,Object>> accounts) {
        public Options(LocalDate today, LocalDate coverageStart, java.util.UUID warehouseId, String warehouseName, LocalDate historyCutoff, long unmappedHistoryRows) {
            this(today,coverageStart,warehouseId,warehouseName,historyCutoff,unmappedHistoryRows,null,java.util.List.of());
        }
    }
    private boolean accountReporting() {return accounts!=null && accounts.available();}

    @Transactional(readOnly=true)
    public Options options() {
        var warehouseIds=access.warehouseReadScope(null, null);
        var warehouseId=warehouseIds.size()==1 ? warehouseIds.iterator().next() : null;
        boolean accountReporting=accountReporting();
        return new Options(LocalDate.now(),accountReporting?accounts.coverageStart():query.coverageStart(),warehouseId,warehouseName(warehouseId),
            query.historicalCutoff(),accountReporting?accounts.unmappedRows():query.unmappedHistoryRows(),
            accountReporting?accounts.snapshotDate():null,accountReporting?accounts.accounts():java.util.List.of());
    }

    @Transactional(readOnly=true)
    public com.enterprise.mm.payload.PageResponse<java.util.Map<String,Object>> references(String kind,Filter filter) {
        if(!java.util.Set.of("MATERIAL","WAREHOUSE","COUNTERPARTY","ACCOUNT").contains(kind) || filter.page()<0 || filter.size()<1 || filter.size()>200
            || (filter.search()!=null && filter.search().length()>200)) throw new BusinessException("Invalid report reference filter");
        var warehouseIds=access.warehouseReadScope(filter.warehouseId(), null);
        var warehouse=filter.warehouseId()!=null ? filter.warehouseId() : warehouseIds.size()==1 ? warehouseIds.iterator().next() : null;
        Filter f = new Filter(filter.type(),filter.from(),filter.to(),warehouse,null,null,filter.search(),filter.page(),filter.size(),filter.materialSubtype(),filter.materialType(),filter.accountCode(),filter.sourceType(),filter.ownerPartyId()).withWarehouseScope(warehouseIds);
        if(accountReporting()) {
            return accounts.references(kind, f);
        } else {
            return query.references(kind, f);
        }
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public MaterialCard materialCard(java.util.UUID materialId, java.util.UUID warehouseId, LocalDate from, LocalDate to, String accountCode, int page, int size) {
        if (materialId == null) throw new BusinessException("Material ID is required", "WAREHOUSE_REPORT_MATERIAL_REQUIRED");
        if (from == null || to == null || from.isAfter(to) || to.isAfter(LocalDate.now()))
            throw new BusinessException("Select a valid report period ending today or earlier", "WAREHOUSE_REPORT_PERIOD_INVALID");
        if (page < 0 || size < 1 || size > 200)
            throw new BusinessException("Report page size must be between 1 and 200", "WAREHOUSE_REPORT_PAGE_INVALID");
        var warehouseIds = access.warehouseReadScope(warehouseId, null);
        var effectiveWarehouse = warehouseId != null ? warehouseId : warehouseIds.size()==1 ? warehouseIds.iterator().next() : null;
        String currency = settings.findOrDefault().getBaseCurrencyCode();
        if (accountReporting()) {
            return accounts.materialCard(materialId, effectiveWarehouse, from, to, accountCode, page, size, currency, new java.util.ArrayList<>(warehouseIds));
        } else {
            return query.materialCard(materialId, effectiveWarehouse, from, to, page, size, currency, new java.util.ArrayList<>(warehouseIds));
        }
    }

    public record OverviewContext(Filter filter, String currencyCode, java.util.List<String> warnings,
        boolean accountReporting) {}

    private record Prepared(Filter filter, LocalDate coverageStart, java.util.List<String> warnings,
        boolean accountReporting) {}

    // The overview only needs validation, scope and metadata; its aggregate query supplies the figures.
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public OverviewContext overviewContext(Filter requested) {
        Prepared prepared=prepare(requested);
        return new OverviewContext(prepared.filter(),settings.findOrDefault().getBaseCurrencyCode(),
            prepared.warnings(),prepared.accountReporting());
    }

    // Rows, count and totals share a database snapshot even while warehouse posting continues.
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public WarehouseReport generate(Filter requested, boolean all) {
        Prepared prepared=prepare(requested);
        Filter f=prepared.filter();
        boolean accountReporting=prepared.accountReporting();
        String company=jdbc.query("select coalesce(legal_name,name) from companies where id=:company and tenant_id=:tenant",
            query.scope(),(rs,n)->rs.getString(1)).stream().findFirst().orElse(CompanyContext.currentCompany().toString());
        var rows=accountReporting?accounts.rows(f,all):query.rows(f,all);
        long totalElements=all?rows.size():accountReporting?accounts.count(f):query.count(f);
        return new WarehouseReport(f.type(),f.from(),f.to(),prepared.coverageStart(),LocalDateTime.now(),company,
            settings.findOrDefault().getBaseCurrencyCode(),f.warehouseId(),warehouseName(f.warehouseId()),prepared.warnings(),
            rows,f.page(),f.size(),totalElements,
            accountReporting?accounts.totals(f):query.totals(f),f.materialSubtype(),f.materialType(),f.accountCode(),f.sourceType(),
            accountReporting?accounts.snapshotDate():null);
    }

    private Prepared prepare(Filter requested) {
        if (requested.type()==null || requested.to()==null || requested.from()==null
            || requested.from().isAfter(requested.to()) || requested.to().isAfter(LocalDate.now()))
            throw new BusinessException("Select a valid report period ending today or earlier", "WAREHOUSE_REPORT_PERIOD_INVALID");
        if (requested.page()<0 || requested.size()<1 || requested.size()>200)
            throw new BusinessException("Report page size must be between 1 and 200", "WAREHOUSE_REPORT_PAGE_INVALID");
        if (requested.search()!=null && requested.search().length()>200)
            throw new BusinessException("Report search is limited to 200 characters", "WAREHOUSE_REPORT_SEARCH_INVALID");
        if (requested.materialSubtype()!=null && requested.materialType()!=null && requested.materialType()!=MaterialType.TMB)
            throw new BusinessException("Inventory subtype is only available for TMB materials", "material.subtype.tmb_only");
        if(requested.accountCode()!=null && !requested.accountCode().matches("[0-9]{2,6}|—"))
            throw new BusinessException("Invalid warehouse account filter", "WAREHOUSE_REPORT_ACCOUNT_INVALID");
        if(requested.sourceType()!=null && !java.util.Set.of("MATERIAL","EQUIPMENT","ANIMALS","FIXED_ASSET","SERVICES","FINISHED_GOODS","SEMI_FINISHED_GOODS","GOODS","VALUE_ONLY","UNSPECIFIED").contains(requested.sourceType()))
            throw new BusinessException("Invalid source material type", "WAREHOUSE_REPORT_SOURCE_TYPE_INVALID");
        boolean accountReporting=accountReporting();
        if(!accountReporting && (requested.accountCode()!=null || requested.sourceType()!=null))
            throw new BusinessException("Account balances have not been imported for this company", "WAREHOUSE_REPORT_ACCOUNTS_UNAVAILABLE");
        var warehouseIds=access.warehouseReadScope(requested.warehouseId(), null);
        var warehouseId=requested.warehouseId()!=null ? requested.warehouseId() : warehouseIds.size()==1 ? warehouseIds.iterator().next() : null;
        Filter f=new Filter(requested.type(),requested.type()==Type.STOCK_BALANCES ? requested.to() : requested.from(),
            requested.to(),warehouseId,requested.materialId(),requested.materialGroupId(),requested.search(),requested.page(),requested.size(),requested.materialSubtype(),requested.materialType(),requested.accountCode(),requested.sourceType(),requested.ownerPartyId(),requested.snapshotTimestamp()).withWarehouseScope(warehouseIds);
        if (query.importInProgress()) throw new BusinessException("Opening stock import is still being reconciled", "OPENING_IMPORT_IN_PROGRESS");
        LocalDate coverage=accountReporting?accounts.coverageStart():query.coverageStart();
        if (coverage!=null && f.from().isBefore(coverage))
            throw new BusinessException("Warehouse reporting is available from " + coverage + "; no earlier warehouse movement evidence is available", "WAREHOUSE_REPORT_BEFORE_OPENING", coverage.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        var warnings=new ArrayList<String>();
        if(accountReporting) warnings.add("ACCOUNT_WAREHOUSE_BALANCES");
        else if (query.historicalStart()!=null) warnings.add("ONE_C_HISTORY_INTEGRATED");
        else if (coverage!=null) warnings.add("HISTORY_STARTS_AT_OPENING");
        // A full count lets PostgreSQL hash-join mapped references; EXISTS can choose
        // millions of individual link lookups when no unmatched reference exists.
        if((accountReporting?accounts.unmappedRows():query.unmappedHistoryRows())>0) warnings.add("UNMAPPED_1C_REFERENCES");
        if (!accountReporting && (f.type()==Type.RECEIPTS || f.type()==Type.ISSUES)) warnings.add("CORRESPONDING_ACCOUNTS_UNAVAILABLE");
        if (!accountReporting && !reconciles(f)) warnings.add("LEDGER_DIFFERS_FROM_CURRENT_BALANCES");
        return new Prepared(f,coverage,java.util.List.copyOf(warnings),accountReporting);
    }

    private String warehouseName(java.util.UUID warehouseId) {
        if (warehouseId==null) return null;
        return jdbc.query("select name from warehouses where id=:warehouse and tenant_id=:tenant and company_id=:company",
            query.scope().addValue("warehouse",warehouseId),(rs,n)->rs.getString(1)).stream().findFirst().orElse(null);
    }

    private boolean reconciles(Filter f) {
        String restriction="";
        if (f.warehouseId()!=null) restriction+=" and warehouse_id=:warehouse";
        if (!f.warehouseIds().isEmpty()) restriction+=" and warehouse_id in (:scopedWarehouses)";
        if (f.materialId()!=null) restriction+=" and material_id=:material";
        if (f.materialGroupId()!=null) restriction+=" and material_id in (select id from materials where material_group_id=:groupId and tenant_id=:tenant and company_id=:company)";
        restriction+=WarehouseReportQuery.materialRestriction(f);
        var params=query.scope().addValue("scopedWarehouses",f.warehouseIds()).addValue("warehouse",f.warehouseId()).addValue("material",f.materialId()).addValue("groupId",f.materialGroupId())
            .addValue("materialType",f.materialType()==null ? null : f.materialType().name())
            .addValue("materialSubtype",f.materialSubtype()==null ? null : f.materialSubtype().name());
        // Compare per stock key so discrepancies cannot cancel between warehouses or materials.
        Long differences=jdbc.queryForObject(query.ledger() + """
            , compared as (
              select material_id,warehouse_id,quantity,amount from net
              union all select material_id,warehouse_id,-quantity,-total_value from stock_balances
                where tenant_id=:tenant and company_id=:company and is_delete=false
            ), differences as (
              select material_id,warehouse_id from compared where 1=1
            """ + restriction + " group by material_id,warehouse_id having abs(sum(quantity))>0.00000005 or abs(sum(amount))>0.00005) select count(*) from differences",
            params,Long.class);
        return differences==0;
    }
}
