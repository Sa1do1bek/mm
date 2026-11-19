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
public class WarehouseHistoryQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RoleDataScopeService roles;

    public Map<String,Object> records(String kind,String documentType,String documentKey,UUID materialId,UUID warehouseId,
        String search,String from,String to,int page,int size) {
        if(page<0||page>1000000||size<1||size>200||kind==null||!WarehouseHistoryImportService.KINDS.contains(kind))
            throw new BadRequestException("Invalid historical pagination or kind");
        var p=parameters();var where=new StringBuilder("r.tenant_id=:tenant and r.company_id=:company and r.kind=:kind");p.addValue("kind",kind);
        UUID warehouse=roles.effectiveWarehouseId(warehouseId);
        warehouseFilter(where,p,warehouse);
        if(documentType!=null&&!documentType.isBlank()) {where.append(" and r.document_type=:type");p.addValue("type",documentType);}
        if(documentKey!=null&&!documentKey.isBlank()) {where.append(" and r.document_key=:document");p.addValue("document",documentKey);}
        if(materialId!=null) {where.append(" and r.material_id=:material");p.addValue("material",materialId);}
        if(search!=null&&!search.isBlank()) {if(search.length()>120)throw new BadRequestException("Historical search is too long");
            where.append(" and (lower(r.document_number) like :search or lower(r.document_type) like :search)");p.addValue("search","%"+search.toLowerCase(Locale.ROOT)+"%");}
        if(from!=null&&!from.isBlank()) {where.append(" and r.document_date>=:fromDate");p.addValue("fromDate",date(from));}
        if(to!=null&&!to.isBlank()) {where.append(" and r.document_date<:toDate");p.addValue("toDate",date(to).toLocalDateTime().plusDays(1));}
        long total=jdbc.queryForObject("select count(*) from warehouse_history_records r where "+where,p,Long.class);
        p.addValue("limit",size).addValue("offset",(long)page*size);
        String sql="select r.id,r.content,r.row_sha256,m.name as material_name,w.name as warehouse_name,t.name as target_warehouse_name from warehouse_history_records r "
            +"left join materials m on m.id=r.material_id and m.tenant_id=r.tenant_id and m.company_id=r.company_id "
            +"left join warehouses w on w.id=r.warehouse_id and w.tenant_id=r.tenant_id and w.company_id=r.company_id "
            +"left join warehouses t on t.id=r.target_warehouse_id and t.tenant_id=r.tenant_id and t.company_id=r.company_id "
            +"where "+where+" order by r.document_date desc nulls last,r.id limit :limit offset :offset";
        var result=jdbc.query(sql,p,(rs,n)->{
            var value=read(rs.getString("content"));value.put("id",rs.getObject("id").toString());value.put("sha256",rs.getString("row_sha256"));
            value.put("materialName",rs.getString("material_name"));value.put("warehouseName",rs.getString("warehouse_name"));value.put("targetWarehouseName",rs.getString("target_warehouse_name"));return value;
        });
        referenceNames(result);
        return Map.of("content",result,"totalElements",total,"number",page,"size",size);
    }

    public Map<String,Object> detail(UUID id) {
        var p=parameters().addValue("id",id);var where=new StringBuilder("r.tenant_id=:tenant and r.company_id=:company and r.id=:id");
        warehouseFilter(where,p,roles.effectiveWarehouseId(null));
        var found=jdbc.queryForList("select r.content,r.source_table,r.row_sha256 from warehouse_history_records r where "+where,p);
        if(found.size()!=1)throw new NotFoundException("Historical record not found in the current scope");
        var row=found.getFirst();var result=read((String)row.get("content"));result.put("id",id.toString());result.put("sha256",row.get("row_sha256"));
        var schemas=jdbc.queryForList("select content from warehouse_history_records where tenant_id=:tenant and company_id=:company and source_key=:schema and kind='SCHEMA'",
            parameters().addValue("schema","schema:"+row.get("source_table")));
        result.put("fieldLabels",schemas.isEmpty()?Map.of():((Map<?,?>)read((String)schemas.getFirst().get("content")).get("data")).get("fields"));
        referenceNames(List.of(result));
        return result;
    }

    public Map<String,Object> summary() {
        var p=parameters();var where=new StringBuilder("r.tenant_id=:tenant and r.company_id=:company");warehouseFilter(where,p,roles.effectiveWarehouseId(null));
        var counts=jdbc.queryForList("select r.kind,count(*) as count from warehouse_history_records r where "+where+" group by r.kind",p);
        var types=jdbc.queryForList("select r.document_type as label,count(*) as count from warehouse_history_records r where "+where+" and r.kind='DOCUMENT' group by r.document_type order by r.document_type",p);
        return Map.of("counts",counts,"documentTypes",types,"historyOnly",true,"stockEffects",false);
    }

    private void referenceNames(List<Map<String,Object>> records) {
        var keys=new HashSet<String>();
        var fields=Map.of("materialSourceId","materials","warehouseSourceId","warehouses","targetWarehouseSourceId","warehouses");
        for(var record:records) for(var field:fields.entrySet()) if(record.get(field.getKey()) instanceof String id)
            keys.add("reference:"+field.getValue()+":"+id);
        if(keys.isEmpty())return;
        var names=new HashMap<String,String>();
        jdbc.query("select source_key,content from warehouse_history_records where tenant_id=:tenant and company_id=:company and kind='SCHEMA' and source_key in (:keys)",parameters().addValue("keys",keys),rs->{
            var data=(Map<?,?>)read(rs.getString("content")).get("data");
            if(data.get("name") instanceof String name)names.put(rs.getString("source_key"),name);
        });
        var targets=Map.of("materialSourceId","materialName","warehouseSourceId","warehouseName","targetWarehouseSourceId","targetWarehouseName");
        for(var record:records) for(var field:fields.entrySet()) if(record.get(field.getKey()) instanceof String id && record.get(targets.get(field.getKey()))==null)
            record.put(targets.get(field.getKey()),names.get("reference:"+field.getValue()+":"+id));
    }

    private MapSqlParameterSource parameters() {return new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());}
    private void warehouseFilter(StringBuilder where,MapSqlParameterSource p,UUID warehouse) {
        if(warehouse!=null) {where.append(" and (r.warehouse_id=:warehouse or r.target_warehouse_id=:warehouse)");p.addValue("warehouse",warehouse);}
    }
    private java.sql.Timestamp date(String value) {try{return java.sql.Timestamp.valueOf(java.time.LocalDate.parse(value).atStartOfDay());}catch(Exception e){throw new BadRequestException("Historical date filter must be YYYY-MM-DD");}}
    private Map<String,Object> read(String value) {try{return mapper.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Stored historical JSON is invalid");}}
}
