package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Source views and reference bindings are projections of immutable history rows. */
@RequiredArgsConstructor
public class WarehouseSourceProfileProjection {
    public static final Set<String> CATEGORIES=Set.of("MATERIAL_DETAILS","WAREHOUSE_DETAILS","GROUP_DETAILS","ASSET_CARD","QUALITY_RESULT","ORGANIZATION_UNIT","RESPONSIBILITY","REFERENCE");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public void apply(String tenant,UUID company,List<JsonNode> rows) {
        var profiles=new ArrayList<Object[]>();var links=new ArrayList<Object[]>();
        var materials=new ArrayList<Object[]>();var warehouses=new ArrayList<Object[]>();var groups=new ArrayList<Object[]>();
        for(var row:rows) {
            String category=text(row,"sourceCategory");if(category==null)continue;
            JsonNode data=row.path("data"),view=data.path("view");
            String source=text(data,"sourceId"),table=text(row,"sourceTable");
            if(!"SCHEMA".equals(text(row,"kind"))||!CATEGORIES.contains(category)||!view.isObject()
                ||source==null||!source.matches("[a-f0-9]{32}|[a-f0-9]{64}")||!data.path("raw").isObject()
                ||text(view,"name")==null||text(view,"name").isBlank())throw new BadRequestException("Invalid structured 1C source profile");
            String key=text(row,"sourceKey");UUID id=UUID.nameUUIDFromBytes((tenant+":"+company+":"+key).getBytes(StandardCharsets.UTF_8));
            UUID target=uuid(row,"targetId"),parent=uuid(view,"parentId");
            String targetTable=switch(category){case "MATERIAL_DETAILS"->"materials";case "WAREHOUSE_DETAILS"->"warehouses";case "GROUP_DETAILS"->"material_groups";default->null;};
            if(target!=null) {
                if(targetTable==null||!table.matches("_reference[0-9]+"))throw new BadRequestException("Unsupported native source binding");
                int catalog=Integer.parseInt(table.substring("_reference".length()));
                var masters=jdbc.queryForList("select id,description from "+targetTable+" where id=? and tenant_id=? and company_id=? and is_delete=false",target,tenant,company);
                String marker=":Catalog"+catalog+":"+source+"]";
                if(masters.size()!=1||!(masters.getFirst().get("description") instanceof String description)||!description.contains(marker))
                    throw new BadRequestException("Source profile binding is outside its company or catalog identity");
                if(parent!=null) {
                    if(parent.equals(target)||jdbc.queryForObject("select count(*) from "+targetTable+" where id=? and tenant_id=? and company_id=? and is_delete=false",Long.class,parent,tenant,company)!=1)
                        throw new BadRequestException("Source parent must be a different master in the same company");
                    UUID old=jdbc.queryForObject("select parent_id from "+targetTable+" where id=?",UUID.class,target);
                    if(old!=null&&!old.equals(parent))throw new ConflictException("An existing native parent differs from the source; review without overwriting it");
                }
                String fields=view.toString();
                if(category.equals("MATERIAL_DETAILS")) {
                    String mxik=text(view,"mxikCode");if(mxik!=null&&!mxik.matches("[0-9]{1,40}"))mxik=null;
                    materials.add(new Object[]{fields,mxik,target,tenant,company});
                } else if(category.equals("WAREHOUSE_DETAILS")) {
                    warehouses.add(new Object[]{fields,parent,view.path("isGroup").asBoolean(),view.path("hasBalance").asBoolean(),text(view,"address"),target,tenant,company});
                } else groups.add(new Object[]{parent,target,tenant,company});
                if(!category.equals("GROUP_DETAILS")) {
                    String kind=category.equals("MATERIAL_DETAILS")?"MATERIAL":"WAREHOUSE";
                    String reference=String.format("%08x",catalog);
                    var old=jdbc.queryForList("select master_id from warehouse_source_master_links where tenant_id=? and company_id=? and reference_kind=? and reference_type=? and source_id=?",tenant,company,kind,reference,source);
                    if(!old.isEmpty()&&!target.equals(old.getFirst().get("master_id")))throw new ConflictException("Source identity already binds another native master");
                    links.add(new Object[]{tenant,company,kind,reference,source,target,id,text(view,"sourceType"),text(view,"sourceKind")});
                }
            }
            Timestamp date=null;String value=text(row,"documentDate");if(value==null)value=text(view,"sourceDate");if(value==null)value=text(view,"stateDate");
            if(value!=null)try{date=Timestamp.valueOf(value.length()==10?java.time.LocalDate.parse(value).atStartOfDay():LocalDateTime.parse(value.replace(' ','T')));}catch(RuntimeException ex){throw new BadRequestException("Invalid source profile date");}
            String code=text(view,"code"),name=text(view,"name");
            profiles.add(new Object[]{id,tenant,company,category,table,source,target,text(view,"parentSourceId"),code,name,date,view.toString().toLowerCase(Locale.ROOT),view.toString()});
        }
        if(profiles.isEmpty())return;
        // Apply edges within this transaction, then check the complete graph, including same-chunk links.
        if(!warehouses.isEmpty())jdbc.batchUpdate("update warehouses set parent_id=coalesce(parent_id,?) where id=? and tenant_id=? and company_id=?",warehouses.stream().map(r->new Object[]{r[1],r[5],r[6],r[7]}).toList());
        if(!groups.isEmpty())jdbc.batchUpdate("update material_groups set parent_id=coalesce(parent_id,?) where id=? and tenant_id=? and company_id=?",groups);
        for(String table:List.of("warehouses","material_groups")) {
            var targets=table.equals("warehouses")?warehouses.stream().map(r->r[5]).toList():groups.stream().map(r->r[1]).toList();
            if(targets.isEmpty())continue;
            Long cycles=jdbc.queryForObject("with recursive graph as(select id,parent_id,array[id] path,false cycle from "+table+" where id=any(?) and tenant_id=? and company_id=? union all select p.id,p.parent_id,g.path||p.id,p.id=any(g.path) from graph g join "+table+" p on p.id=g.parent_id and p.tenant_id=? and p.company_id=? where not g.cycle) select count(*) from graph where cycle",Long.class,targets.toArray(UUID[]::new),tenant,company,tenant,company);
            if(cycles!=null&&cycles>0)throw new ConflictException("Source hierarchy contains a cycle");
        }
        jdbc.batchUpdate("insert into warehouse_source_profiles(id,tenant_id,company_id,category,source_table,source_id,target_id,parent_source_id,code,name,source_date,search_text,fields) values(?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))",profiles);
        if(!links.isEmpty())jdbc.batchUpdate("insert into warehouse_source_master_links values(?,?,?,?,?,?,?,?,?) on conflict(tenant_id,company_id,reference_kind,reference_type,source_id) do nothing",links);
        if(!materials.isEmpty())jdbc.batchUpdate("update materials set source_data=cast(? as jsonb),ikpu_code=coalesce(nullif(ikpu_code,''),?) where id=? and tenant_id=? and company_id=?",materials);
        if(!warehouses.isEmpty())jdbc.batchUpdate("update warehouses set source_data=cast(? as jsonb),parent_id=coalesce(parent_id,?),source_group=?,source_has_balance=?,address=coalesce(nullif(address,''),?) where id=? and tenant_id=? and company_id=?",warehouses);
        if(!groups.isEmpty())jdbc.batchUpdate("update material_groups set parent_id=coalesce(parent_id,?) where id=? and tenant_id=? and company_id=?",groups);
    }
    private static String text(JsonNode node,String key){return node.hasNonNull(key)?node.get(key).asText():null;}
    private static UUID uuid(JsonNode node,String key){String value=text(node,key);try{return value==null?null:UUID.fromString(value);}catch(RuntimeException ex){throw new BadRequestException("Invalid source profile target");}}
}
