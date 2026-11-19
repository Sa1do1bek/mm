package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class WarehouseSourceVerification {
    private final NamedParameterJdbcTemplate jdbc;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> verify() {
        var p=new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());
        var stock=new TreeMap<String,Object>();
        for(String table:List.of("stock_balances","stock_batches","inventory_cost_layers","stock_cost_movements","stock_movements","stock_movement_allocations","stock_movement_allocation_cost_layers")) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(:table) is not null",new MapSqlParameterSource("table",table),Boolean.class)))continue;
            // Four independent 64-bit lanes bound aggregation memory and retain every row field.
            stock.put(table,jdbc.queryForMap("select count(*)::text rows,coalesce(sum(('x'||substr(hash,1,16))::bit(64)::bigint),0)::text a,coalesce(sum(('x'||substr(hash,17,16))::bit(64)::bigint),0)::text b,coalesce(sum(('x'||substr(hash,33,16))::bit(64)::bigint),0)::text c,coalesce(sum(('x'||substr(hash,49,16))::bit(64)::bigint),0)::text d from(select encode(sha256(convert_to(row_to_json(t)::text,'UTF8')),'hex') hash from "+table+" t where tenant_id=:tenant and company_id=:company) hashes",p));
        }
        var accounts=jdbc.queryForList("select e.kind,e.account_code,e.source_type,count(*) records,sum(e.quantity)::text quantity,sum(e.amount)::text amount from warehouse_account_entries e join warehouse_account_imports r on r.id=e.import_id and r.status='COMPLETED' where e.tenant_id=:tenant and e.company_id=:company group by e.kind,e.account_code,e.source_type order by e.kind,e.account_code,e.source_type",p);
        var profiles=jdbc.queryForList("select p.category,p.source_table,count(*) records,count(*) filter(where p.target_id is not null) mapped from warehouse_source_profiles p join warehouse_history_records h on h.id=p.id join warehouse_history_runs r on r.id=h.import_id and r.status='COMPLETED' where p.tenant_id=:tenant and p.company_id=:company group by p.category,p.source_table order by p.category,p.source_table",p);
        return Map.of("companyId",CompanyContext.currentCompany(),"stockState",stock,"accounts",accounts,"profiles",profiles,"stockEffects",false);
    }
}
