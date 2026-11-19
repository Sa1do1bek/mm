package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','AUDITOR','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class WarehouseSourceProfileService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RoleDataScopeService roles;
    public Map<String,Object> records(String category,UUID target,String search,String parent,int page,int size,boolean all) {
        if(!(WarehouseSourceProfileProjection.CATEGORIES.contains(category)||"BALANCE_DETAILS".equals(category))||page<0||page>1000000||size<1||size>200||search!=null&&search.length()>200)
            throw new BadRequestException("Invalid source data filter");
        if("BALANCE_DETAILS".equals(category))return balances(search,page,size,all);
        var p=scope().addValue("category",category);String where="p.tenant_id=:tenant and p.company_id=:company and p.category=:category";
        if(parent!=null){
            if(!parent.equals("root")&&!parent.matches("[a-f0-9]{32}|[a-f0-9]{64}"))throw new BadRequestException("Invalid source parent");
            where+=parent.equals("root")?" and (p.parent_source_id is null or p.parent_source_id='00000000000000000000000000000000')":" and p.parent_source_id=:parent";p.addValue("parent",parent);
        }
        if(target!=null){where+=" and p.target_id=:target";p.addValue("target",target);}
        if(search!=null&&!search.isBlank()) {
            where+=" and p.search_text like :search escape '!'";
            p.addValue("search","%"+search.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        where+=warehouseScope(p,category);
        where+=" and exists(select 1 from warehouse_history_records valid join warehouse_history_runs ready on ready.id=valid.import_id and ready.status='COMPLETED' where valid.id=p.id)";
        long count=jdbc.queryForObject("select count(*) from warehouse_source_profiles p where "+where,p,Long.class);
        if(all&&count>250000)throw new BadRequestException("Narrow the source export filter to 250000 rows");
        p.addValue("limit",size).addValue("offset",(long)page*size);
        var content=jdbc.query("select p.id,p.target_id,p.source_id,p.source_table,p.fields,h.row_sha256 from warehouse_source_profiles p join warehouse_history_records h on h.id=p.id join warehouse_history_runs run on run.id=h.import_id and run.status='COMPLETED' where "+where+("REFERENCE".equals(category)?" order by p.source_date,p.id":" order by p.code,p.id")+(all?"":" limit :limit offset :offset"),p,(rs,n)->{
            Map<String,Object> row=read(rs.getString("fields"));row.put("id",rs.getObject("id").toString());row.put("targetId",rs.getObject("target_id"));
            row.put("sourceId",rs.getString("source_id"));row.put("sourceTable",rs.getString("source_table"));row.put("sha256",rs.getString("row_sha256"));return row;
        });
        return Map.of("content",content,"totalElements",count,"number",page,"size",size,"sourceData",true);
    }
    public Map<String,Object> summary() {
        var p=scope();UUID warehouse=roles.effectiveWarehouseId(null);
        if(warehouse!=null)return Map.of("counts",List.of(),"sourceSnapshot","2026-10-08");
        var counts=jdbc.queryForList("select p.category,count(*) count from warehouse_source_profiles p join warehouse_history_records h on h.id=p.id join warehouse_history_runs run on run.id=h.import_id and run.status='COMPLETED' where p.tenant_id=:tenant and p.company_id=:company group by p.category",p);
        return Map.of("counts",counts,"sourceSnapshot","2026-10-08");
    }
    public Map<String,Object> detail(UUID id) {
        var p=scope().addValue("id",id);
        var category=jdbc.queryForList("select category from warehouse_source_profiles where tenant_id=:tenant and company_id=:company and id=:id",p);
        if(category.isEmpty())return balanceDetail(id);
        if(category.size()!=1)throw new NotFoundException("Source record not found");
        String c=(String)category.getFirst().get("category");String restriction=warehouseScope(p,c);
        var found=jdbc.queryForList("select h.content,h.row_sha256 from warehouse_source_profiles p join warehouse_history_records h on h.id=p.id join warehouse_history_runs run on run.id=h.import_id and run.status='COMPLETED' where p.tenant_id=:tenant and p.company_id=:company and p.id=:id"+restriction,p);
        if(found.size()!=1)throw new NotFoundException("Source record not found in the current scope");
        var value=read((String)found.getFirst().get("content"));value.put("sha256",found.getFirst().get("row_sha256"));
        var schemas=jdbc.queryForList("select content from warehouse_history_records where tenant_id=:tenant and company_id=:company and source_key=:key",scope().addValue("key","schema:"+value.get("sourceTable")));
        if(!schemas.isEmpty())value.put("fieldLabels",((Map<?,?>)read((String)schemas.getFirst().get("content")).get("data")).get("fields"));
        if("ASSET_CARD".equals(c)&&value.get("data") instanceof Map<?,?> data && data.get("registerSnapshots") instanceof Map<?,?> snapshots) {
            var labels=new LinkedHashMap<String,Object>();
            for(Object table:snapshots.keySet()) {
                var fields=jdbc.queryForList("select content from warehouse_history_records where tenant_id=:tenant and company_id=:company and source_key=:key",scope().addValue("key","schema:"+table));
                if(!fields.isEmpty())labels.put(table.toString(),((Map<?,?>)read((String)fields.getFirst().get("content")).get("data")).get("fields"));
            }
            value.put("registerFieldLabels",labels);
        }
        return value;
    }
    private Map<String,Object> balances(String search,int page,int size,boolean all) {
        var p=scope();String where="e.tenant_id=:tenant and e.company_id=:company and e.kind='BALANCE' and run.status='COMPLETED'";
        UUID warehouse=roles.effectiveWarehouseId(null);
        if(warehouse!=null){where+=" and coalesce(e.warehouse_id,lw.master_id)=:warehouse";p.addValue("warehouse",warehouse);}
        if(search!=null&&!search.isBlank()){where+=" and concat_ws(' ',e.account_code,e.material_name,e.warehouse_name) ilike :search escape '!'";p.addValue("search","%"+search.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        String joins=" from warehouse_account_entries e join warehouse_account_imports run on run.id=e.import_id left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id";
        long count=jdbc.queryForObject("select count(*)"+joins+" where "+where,p,Long.class);
        if(all&&count>250000)throw new BadRequestException("Narrow the source export filter");
        p.addValue("limit",size).addValue("offset",(long)page*size);
        var content=jdbc.query("select e.id,e.content"+joins+" where "+where+" order by e.account_code,e.id"+(all?"":" limit :limit offset :offset"),p,(rs,n)->{
            var r=read(rs.getString("content"));r.put("id",rs.getObject("id").toString());r.put("code",r.get("accountCode"));r.put("name",r.get("materialName"));r.put("sourceId",r.get("sourceKey"));r.put("locationName",r.get("warehouseName"));return r;
        });
        return Map.of("content",content,"totalElements",count,"number",page,"size",size,"sourceData",true);
    }
    private Map<String,Object> balanceDetail(UUID id) {
        var p=scope().addValue("id",id);String restriction="";UUID warehouse=roles.effectiveWarehouseId(null);
        if(warehouse!=null){restriction=" and coalesce(e.warehouse_id,lw.master_id)=:warehouse";p.addValue("warehouse",warehouse);}
        var rows=jdbc.queryForList("select e.content,e.row_sha256 from warehouse_account_entries e join warehouse_account_imports run on run.id=e.import_id and run.status='COMPLETED' left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id where e.tenant_id=:tenant and e.company_id=:company and e.id=:id and e.kind='BALANCE'"+restriction,p);
        if(rows.size()!=1)throw new NotFoundException("Source balance not found in the current scope");
        var r=read((String)rows.getFirst().get("content"));var view=new LinkedHashMap<>(r);view.put("code",r.get("accountCode"));view.put("name",r.get("materialName"));view.put("locationName",r.get("warehouseName"));
        return Map.of("sha256",rows.getFirst().get("row_sha256"),"data",Map.of("raw",r.get("additionalDimensions"),"view",view));
    }
    private String warehouseScope(MapSqlParameterSource p,String category) {
        UUID warehouse=roles.effectiveWarehouseId(null);if(warehouse==null)return "";
        p.addValue("warehouse",warehouse);
        if(category.equals("WAREHOUSE_DETAILS"))return " and p.target_id=:warehouse";
        if(category.equals("MATERIAL_DETAILS"))return " and exists(select 1 from warehouse_account_entries e join warehouse_account_imports run on run.id=e.import_id and run.status='COMPLETED' left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055' and lw.source_id=e.warehouse_source_id where e.tenant_id=p.tenant_id and e.company_id=p.company_id and e.material_source_id=p.source_id and e.material_reference_type=case p.source_table when '_reference54' then '00000036' when '_reference55' then '00000037' when '_reference5876' then '000016f4' else '' end and coalesce(e.warehouse_id,lw.master_id)=:warehouse)";
        // Department asset cards and unallocated source records cannot widen a warehouse-bound user's scope.
        return " and 1=0";
    }
    private MapSqlParameterSource scope(){return new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());}
    private Map<String,Object> read(String text){try{return mapper.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored source profile");}}
}
