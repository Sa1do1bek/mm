package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class WarehouseHistoryProjectionVerification {
    private final NamedParameterJdbcTemplate jdbc;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> verify() {
        var p=new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());
        var current=new TreeMap<String,Object>();
        for(String table:List.of("stock_balances","stock_batches","inventory_cost_layers","stock_cost_movements","stock_movements","stock_movement_allocations","stock_movement_allocation_cost_layers")) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(:table) is not null",new MapSqlParameterSource("table",table),Boolean.class)))
                current.put(table,jdbc.queryForMap("select count(*) rows,md5(coalesce(string_agg(md5(row_to_json(t)::text),'' order by id),'')) md5 from "+table+" t where tenant_id=:tenant and company_id=:company",p));
        }
        var audit=jdbc.queryForList("select stage,stock_state::text stock_state from warehouse_history_projection_state where tenant_id=:tenant and company_id=:company order by stage",p);
        var projection=jdbc.queryForMap("""
            select count(*) movements,sum(source_records) source_legs,sum(quantity)::text quantity_checksum,sum(amount)::text value_checksum,
              md5(coalesce(string_agg(md5(concat_ws(':',document_key,movement_date,material_source_id,warehouse_source_id,trim_scale(quantity)::text,trim_scale(amount)::text,source_records)),''
                order by document_key collate "C",movement_date,material_source_id collate "C",warehouse_source_id collate "C"),'')) semantic_md5,
              count(*) filter(where material_id is null or warehouse_id is null) unmapped,min(movement_date) first_date,max(movement_date) last_date
            from warehouse_imported_movements where tenant_id=:tenant and company_id=:company
            """,p);
        return Map.of("currentStockState",current,"migrationStockStates",audit,"projection",projection,"stockEffects",false);
    }
}
