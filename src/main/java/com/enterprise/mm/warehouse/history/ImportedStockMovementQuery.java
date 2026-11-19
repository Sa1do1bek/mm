package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.RoleDataScopeService;
import java.math.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One normal movement list; historical entries never enter posting/valuation tables. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class ImportedStockMovementQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService roles;

    private MapSqlParameterSource scope() {
        return new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());
    }
    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
            try(var tables=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"warehouse_imported_movements",new String[]{"TABLE"})){return tables.next();}
        }));
    }
    public boolean available() {
        if(!tableExists())return false;
        return jdbc.queryForObject("select exists(select 1 from warehouse_imported_movements where tenant_id=:tenant and company_id=:company)",scope(),Boolean.class);
    }
    private static final String LIST = """
        with movements as (
          select sm.id,false historical,sm.material_id,sm.warehouse_id,sm.target_warehouse_id,coalesce(sm.movement_date,cast(sm.created_at as date)) movement_date,sm.created_at,sm.quantity,
            sm.code,sm.name,sm.description search_description,sm.status,sm.reference_id,sm.reference_type,sm.movement_type,sm.source_inventory_item_id inventory_id,sm.is_delete,
            m.code material_code,m.name material_name
          from stock_movements sm
          left join materials m on m.id=sm.material_id and m.tenant_id=sm.tenant_id and m.company_id=sm.company_id
          where sm.tenant_id=:tenant and sm.company_id=:company
          union all
          select h.id,true,h.material_id,h.warehouse_id,h.target_warehouse_id,h.movement_date,cast(h.movement_date as timestamp),abs(h.quantity),
            h.document_number,h.document_type,concat_ws(' ',h.material_code,h.material_name),'POSTED',h.source_record_id,'ONE_C_HISTORY',h.movement_type,null,false,
            coalesce(m.code,h.material_code) material_code,coalesce(m.name,h.material_name) material_name
          from warehouse_imported_movements h
          left join materials m on m.id=h.material_id and m.tenant_id=h.tenant_id and m.company_id=h.company_id
          where h.tenant_id=:tenant and h.company_id=:company
        )
        """;
    public Page<StockMovementResponse> search(StockMovementSearchRequest request,Pageable page,Function<UUID,StockMovementResponse> operational) {
        return searchBatch(request, page, ids -> ids.stream().map(operational).toList());
    }
    public Page<StockMovementResponse> searchBatch(StockMovementSearchRequest request,Pageable page,Function<List<UUID>,List<StockMovementResponse>> operational) {
        com.enterprise.mm.warehouse.movements.MovementQuerySupport.validate(request);
        SearchCriteria f=roles.applyWarehouseScope(request);
        var p=scope();var where=new StringBuilder(" where 1=1");
        if(!f.includeDeleted())where.append(" and is_delete=false");
        if(!f.normalizedSearch().isBlank()) {
            where.append("""
                 and (lower(code) like :search escape '!' or lower(name) like :search escape '!'
                   or lower(search_description) like :search escape '!'
                   or lower(material_code) like :search escape '!'
                   or lower(material_name) like :search escape '!')
                """);
            p.addValue("search","%"+f.normalizedSearch().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        // Use effective movement dates rather than the day a historic record was imported.
        if(f.movementDate()!=null){where.append(" and movement_date=:mDate");p.addValue("mDate",f.movementDate());}
        if(f.dateFrom()!=null){where.append(" and movement_date>=:from");p.addValue("from",f.dateFrom());}
        if(f.dateTo()!=null){where.append(" and movement_date<=:to");p.addValue("to",f.dateTo());}
        if(f.hasStatus()){where.append(" and status in (:status)");p.addValue("status",f.status());}
        if(f.hasWarehouseIds()){
            where.append(" and (warehouse_id in (:warehouseIds) or target_warehouse_id in (:warehouseIds))");
            p.addValue("warehouseIds",f.warehouseIds());
        }
        if(f.warehouseId()!=null){
            where.append(" and (warehouse_id=:warehouse or target_warehouse_id=:warehouse)");
            p.addValue("warehouse",f.warehouseId());
        }
        if(f.materialId()!=null){where.append(" and material_id=:materialId");p.addValue("materialId",f.materialId());}
        if(f.hasMaterialIds()){where.append(" and material_id in (:materials)");p.addValue("materials",f.materialIds());}
        if(f.materialCode()!=null&&!f.materialCode().isBlank()){
            where.append(" and lower(material_code) like :matCode escape '!'");
            p.addValue("matCode","%"+f.materialCode().trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        if(f.materialName()!=null&&!f.materialName().isBlank()){
            where.append(" and lower(material_name) like :matName escape '!'");
            p.addValue("matName","%"+f.materialName().trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        if(f.inventoryId()!=null){where.append(" and inventory_id=:inventory");p.addValue("inventory",f.inventoryId());}
        if(f.referenceId()!=null){where.append(" and reference_id=:ref");p.addValue("ref",f.referenceId());}
        if(f.normalizedReferenceType()!=null){where.append(" and upper(reference_type)=:refType");p.addValue("refType",f.normalizedReferenceType());}
        if(f.hasMovementTypes()){
            where.append(" and movement_type in (:types)");
            p.addValue("types",f.movementTypes().stream().map(MovementType::name).toList());
        }
        if(f.movementType()!=null){
            where.append(" and movement_type=:type");
            p.addValue("type",f.movementType().name());
        }
        String order = (page.getSort().isUnsorted() ? com.enterprise.mm.warehouse.movements.MovementQuerySupport.sort(null) : page.getSort())
            .stream().map(o -> {
                String column = com.enterprise.mm.warehouse.movements.MovementQuerySupport.SQL_SORTS.get(o.getProperty());
                if(column == null) throw new BadRequestException("Unsupported movement sort");
                if(column.equals("material_name")) {
                    String language = org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage();
                    if(!Set.of("uz","ru","en").contains(language)) language="ru";
                    column="multilingual_name_value(material_name, '"+language+"')";
                }
                return column + " " + o.getDirection().name() + (o.isAscending() ? " nulls last" : " nulls first");
            }).collect(java.util.stream.Collectors.joining(","));
        long total=jdbc.queryForObject(LIST+"select count(*) from movements"+where,p,Long.class);
        p.addValue("limit",page.getPageSize()).addValue("offset",page.getOffset());
        var ids=jdbc.queryForList(LIST+"select id,historical from movements"+where+" order by "+order+" limit :limit offset :offset",p);
        List<UUID> operationalIds = ids.stream().filter(row -> !Boolean.TRUE.equals(row.get("historical")))
            .map(row -> (UUID) row.get("id")).toList();
        Map<UUID,StockMovementResponse> records = new HashMap<>();
        if(!operationalIds.isEmpty()) for(var row : operational.apply(operationalIds)) records.put(row.getId(), row);
        List<UUID> historicalIds = ids.stream().filter(row -> Boolean.TRUE.equals(row.get("historical")))
            .map(row -> (UUID) row.get("id")).toList();
        for(var row : details(historicalIds)) records.put(row.getId(),row);
        var content=new ArrayList<StockMovementResponse>();
        for(var id:ids) content.add(Objects.requireNonNull(records.get((UUID)id.get("id")), "Movement disappeared during query"));
        return new PageImpl<>(content,page,total);
    }
    public Optional<StockMovementResponse> detail(UUID id) {
        if(!tableExists())return Optional.empty();
        return details(List.of(id)).stream().findFirst();
    }
    private List<StockMovementResponse> details(List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        var p=scope().addValue("ids",ids);String restriction="";
        UUID wh=roles.effectiveWarehouseId(null);
        if(wh!=null){restriction=" and (h.warehouse_id=:warehouse or h.target_warehouse_id=:warehouse)";p.addValue("warehouse",wh);}
        var rows=jdbc.query("""
            select h.*,coalesce(m.code,h.material_code) display_material_code,coalesce(m.name,h.material_name) display_material_name,coalesce(w.name,h.warehouse_name) display_warehouse_name,coalesce(w.code,h.warehouse_code) display_warehouse_code,t.code target_warehouse_code,t.name target_warehouse_name,u.code uom,u.measurement_name_short uom_short_name
            from warehouse_imported_movements h
            left join materials m on m.id=h.material_id and m.tenant_id=h.tenant_id and m.company_id=h.company_id
            left join warehouses w on w.id=h.warehouse_id and w.tenant_id=h.tenant_id and w.company_id=h.company_id
            left join warehouses t on t.id=h.target_warehouse_id and t.tenant_id=h.tenant_id and t.company_id=h.company_id
            left join unit_of_measures u on u.id=m.unit_of_measure_id and u.tenant_id=h.tenant_id
            where h.id in (:ids) and h.tenant_id=:tenant and h.company_id=:company
            """+restriction,p,(rs,n)->response(rs));
        return rows;
    }
    public void assertOperational(UUID id) {
        if(detail(id).isPresent())throw new BusinessException("This 1C movement is already included in opening stock. Create a new MM correction to change current stock.","ONE_C_MOVEMENT_ALREADY_IN_OPENING");
    }
    private StockMovementResponse response(ResultSet r) throws SQLException {
        var result=new StockMovementResponse();result.setId(r.getObject("id",UUID.class));result.setTenantId(r.getString("tenant_id"));result.setCompanyId(r.getObject("company_id",UUID.class));
        result.setCode(r.getString("document_number"));result.setName(r.getString("document_type"));result.setStatus("POSTED");result.setStockMovementStatus(StockMovementStatus.POSTED);
        result.setHistorical(true);result.setStockEffects(false);result.setSourceDirection(r.getBigDecimal("quantity").signum()>0?"IN":r.getBigDecimal("quantity").signum()<0?"OUT":"VALUE");
        result.setSourceQuantity(r.getBigDecimal("quantity").toPlainString());result.setSourceBookValue(r.getBigDecimal("amount").toPlainString());
        result.setSourceRecordId(r.getObject("source_record_id",UUID.class));result.setSourceDocumentKey(r.getString("document_key"));
        result.setMaterialId(r.getObject("material_id",UUID.class));result.setMaterialName(r.getString("display_material_name"));result.setMaterialCode(r.getString("display_material_code"));result.setUnitOfMeasureCode(r.getString("uom"));
        result.setUnitOfMeasureShortName(r.getString("uom_short_name"));
        result.setWarehouseId(r.getObject("warehouse_id",UUID.class));result.setWarehouseName(r.getString("display_warehouse_name"));result.setWarehouseCode(r.getString("display_warehouse_code"));
        result.setTargetWarehouseId(r.getObject("target_warehouse_id",UUID.class));result.setTargetWarehouseName(r.getString("target_warehouse_name"));result.setTargetWarehouseCode(r.getString("target_warehouse_code"));
        result.setQuantity(r.getBigDecimal("quantity").abs());result.setTotalCost(r.getBigDecimal("amount").abs());result.setUnitCost(result.getQuantity().signum()==0?BigDecimal.ZERO:result.getTotalCost().divide(result.getQuantity(),7,RoundingMode.HALF_UP));
        result.setMovementType(MovementType.valueOf(r.getString("movement_type")));result.setMovementDate(r.getDate("movement_date").toLocalDate());
        result.setCreatedAt(result.getMovementDate().atStartOfDay());result.setPostedAt(result.getCreatedAt());result.setRequestDate(result.getCreatedAt());
        result.setReferenceType("ONE_C_HISTORY");result.setReferenceId(result.getSourceRecordId());result.setDescription("1C: "+result.getSourceDirection()+"; "+r.getLong("source_records")+" source posting legs. Included in the opening-stock snapshot; no additional stock posting.");

        if ("IN".equals(result.getSourceDirection())) {
            result.setDestinationWarehouseId(result.getWarehouseId());
            result.setDestinationWarehouseCode(result.getWarehouseCode());
            result.setDestinationWarehouseName(result.getWarehouseName());
            result.setDestinationLocation(result.getWarehouseName());
            result.setDestinationType("WAREHOUSE");
            if (result.getTargetWarehouseId() != null) {
                result.setSourceWarehouseId(result.getTargetWarehouseId());
                result.setSourceWarehouseCode(result.getTargetWarehouseCode());
                result.setSourceWarehouseName(result.getTargetWarehouseName());
                result.setSourceLocation(result.getTargetWarehouseName());
                result.setSourceType("WAREHOUSE");
                result.setCounterparty(result.getTargetWarehouseName());
            } else {
                result.setSourceLocation("1C Historical Receipt");
                result.setSourceType("EXTERNAL");
                result.setCounterparty("1C Historical Intake");
            }
        } else if ("OUT".equals(result.getSourceDirection())) {
            result.setSourceWarehouseId(result.getWarehouseId());
            result.setSourceWarehouseCode(result.getWarehouseCode());
            result.setSourceWarehouseName(result.getWarehouseName());
            result.setSourceLocation(result.getWarehouseName());
            result.setSourceType("WAREHOUSE");
            if (result.getTargetWarehouseId() != null) {
                result.setDestinationWarehouseId(result.getTargetWarehouseId());
                result.setDestinationWarehouseCode(result.getTargetWarehouseCode());
                result.setDestinationWarehouseName(result.getTargetWarehouseName());
                result.setDestinationLocation(result.getTargetWarehouseName());
                result.setDestinationType("WAREHOUSE");
                result.setCounterparty(result.getTargetWarehouseName());
            } else {
                result.setDestinationLocation("Production / Department");
                result.setDestinationType("DEPARTMENT");
                result.setCounterparty("Production / Department");
            }
        } else {
            result.setCounterparty("1C Valuation Adjustment");
        }

        return result;
    }
}
