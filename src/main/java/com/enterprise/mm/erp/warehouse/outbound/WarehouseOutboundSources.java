package com.enterprise.mm.erp.warehouse.outbound;

import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.warehouse.posting.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads immutable source evidence in the caller transaction; never changes source registers. */
final class WarehouseOutboundSources {
    record Evidence(String key,String engine,String operation,UUID resource,LocalDate date,String eligible,
                    ArrayNode facts,ObjectNode raw,List<UUID> candidates,boolean revisioned,boolean registryOnly,String reason){}
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    WarehouseOutboundSources(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private String tenant(){return TenantContext.currentTenant();}private UUID company(){return CompanyContext.currentCompany();}
    private JsonNode parse(String value){try{return mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid persisted warehouse evidence",e);}}
    private JsonNode scoped(String table,UUID id){var rows=jdbc.queryForList("select to_jsonb(s)::text from "+table+" s where id=? and tenant_id=? and company_id=?",String.class,id,tenant(),company());if(rows.size()!=1)throw new NotFoundException("Warehouse source outside current company or missing: "+table);return parse(rows.getFirst());}
    private ArrayNode rows(String sql,Object...args){var result=mapper.createArrayNode();for(String s:jdbc.queryForList(sql,String.class,args))result.add(parse(s));return result;}
    private String string(JsonNode n,String field){return n.hasNonNull(field)?n.path(field).asText():null;}
    private UUID id(JsonNode n,String field){String value=string(n,field);return value==null?null:UUID.fromString(value);}
    private BigDecimal number(JsonNode n,String field){return n.path(field).isNumber()?n.path(field).decimalValue():BigDecimal.ZERO;}
    private List<UUID> candidates(UUID...ids){return Arrays.stream(ids).filter(Objects::nonNull).distinct().toList();}
    private ObjectNode stable(JsonNode original){ObjectNode value=original.deepCopy();value.remove(List.of("updated_at","updated_by","version","status","is_delete"));return value;}
    private boolean excluded(String type){String upper=type.toUpperCase(Locale.ROOT);return List.of("PRODUCTION","PROCESSOR","TRANSFORM","CUTTING","ASSEMBLY","DISASSEMBLY","RETURN","CUSTODY","INSTALLATION","HOLDING","PACKAG").stream().anyMatch(upper::contains);}
    Evidence movement(UUID movementId){
        JsonNode s=scoped("stock_movements",movementId);String reference=s.path("reference_type").asText("");
        if(Set.of("ONE_C_API","OPENING_STOCK_EXACT","OPENING_STOCK").contains(reference))return null;
        if(!s.hasNonNull("posted_at"))throw new ConflictException("Only committed posted movement evidence can be captured");
        var allocations=rows("select to_jsonb(a)::text from stock_movement_allocations a where a.tenant_id=? and a.company_id=? and a.movement_id=? order by allocation_sequence",tenant(),company(),movementId);
        var lineage=rows("select to_jsonb(l)::text from stock_movement_allocation_cost_layers l join stock_movement_allocations a on a.id=l.allocation_id and a.tenant_id=l.tenant_id and a.company_id=l.company_id where a.tenant_id=? and a.company_id=? and a.movement_id=? order by a.allocation_sequence,l.lineage_sequence",tenant(),company(),movementId);
        var facts=mapper.createArrayNode();for(JsonNode a:allocations){BigDecimal q=number(a,"on_hand_delta"),v=number(a,"total_value_delta");if(q.signum()==0&&v.signum()==0)continue;String side=q.signum()<0?"source_":"target_";UUID warehouse=id(a,side+"warehouse_id_snapshot");if(warehouse==null)warehouse=id(a,q.signum()<0?"target_warehouse_id_snapshot":"source_warehouse_id_snapshot");if(warehouse==null)warehouse=id(s,"warehouse_id");ObjectNode f=facts.addObject();f.put("domain","STOCK");OutboundFacts.put(f,"materialId",id(s,"material_id"));OutboundFacts.put(f,"warehouseId",warehouse);f.put("quantity",q.toPlainString());f.put("amount",v.toPlainString());f.put("currency",a.path("currency_code_snapshot").asText("UZS"));OutboundFacts.put(f,"accountCode",string(s,"account_code_snapshot"));OutboundFacts.put(f,"allocationId",id(a,"id"));OutboundFacts.put(f,"storageLocationId",id(a,side+"storage_location_id_snapshot"));OutboundFacts.put(f,"storageBinId",id(a,side+"storage_bin_id_snapshot"));OutboundFacts.put(f,"stockBatchId",id(a,side+"stock_batch_id_snapshot"));OutboundFacts.put(f,"disposition",string(a,side+"disposition_snapshot"));
            if(a.hasNonNull("balance_id")){var balances=jdbc.queryForList("select owner_party_id,stock_category from stock_balances where id=? and tenant_id=? and company_id=?",id(a,"balance_id"),tenant(),company());if(!balances.isEmpty()){OutboundFacts.put(f,"ownerId",balances.getFirst().get("owner_party_id"));OutboundFacts.put(f,"stockCategory",balances.getFirst().get("stock_category"));}}if(!f.has("stockCategory"))f.put("stockCategory","OWNED");if(!f.has("ownerId"))f.putNull("ownerId");}
        ObjectNode raw=mapper.createObjectNode();raw.set("movement",stable(s));raw.set("allocations",allocations);raw.set("costLineages",lineage);raw.put("parentResourceType",reference.isBlank()?"StockMovementDocument":reference);
        UUID document=id(s,"document_id"),ref=id(s,"reference_id"),resource=document!=null?document:ref!=null?ref:movementId;String type=s.path("movement_type").asText();String eligible=null;
        if(!excluded(reference)&&!s.hasNonNull("reversal_of_id")&&!facts.isEmpty()){
            if(Set.of("InventoryDocument","INVENTORY_DOCUMENT").contains(reference)&&Set.of("ADJUSTMENT","WRITE_OFF").contains(type))eligible="INVENTORY_CORRECTION";
            else if(type.equals("WRITE_OFF"))eligible="WRITEOFF";
        }
        if(eligible!=null&&!ownedStock(facts))eligible=null;
        return new Evidence("STOCK_MOVEMENT:"+movementId,"STOCK_MOVEMENT",reference.isBlank()?type:reference+":"+type,resource,LocalDate.parse(s.path("movement_date").asText()),eligible,facts,raw,candidates(movementId,document,ref,resource),false,false,allocations.isEmpty()?"IMMUTABLE_ALLOCATION_EVIDENCE_MISSING":"NATIVE_MAPPING_REQUIRED");
    }
    Evidence posting(UUID batchId){
        JsonNode s=scoped("warehouse_posting_batches",batchId);if(!"COMMITTED".equals(s.path("status").asText()))throw new ConflictException("Posting batch has not committed its complete receipt");
        var effectRows=rows("select to_jsonb(e)::text from warehouse_posting_effects e where batch_id=? order by effect_sequence",batchId);var effects=new ArrayList<PostingEffect>();for(JsonNode e:effectRows)effects.add(new PostingEffect(PostingEffectType.valueOf(e.path("effect_type").asText()),id(e,"material_id"),id(e,"warehouse_id"),string(e,"account_code"),string(e,"base_unit_code"),number(e,"quantity"),number(e,"amount"),string(e,"currency_code"),id(e,"storage_location_id"),id(e,"storage_bin_id"),id(e,"stock_batch_id"),id(e,"owner_id"),string(e,"stock_category"),string(e,"disposition"),string(e,"source_reference")));
        var facts=OutboundFacts.normalize(mapper,effects);ObjectNode raw=mapper.createObjectNode();raw.set("batch",stable(s));raw.set("effects",effectRows);raw.put("commandPayloadAvailable",false);String context=s.path("context_key").asText();raw.put("parentResourceType",context);String eligible=null;
        if(!excluded(context)&&ownedStock(facts)&&!facts.isEmpty()&&facts.findValues("quantity").stream().allMatch(v->new BigDecimal(v.asText()).signum()==0)&&facts.findValues("amount").stream().anyMatch(v->new BigDecimal(v.asText()).signum()!=0))eligible="VALUE_CORRECTION";
        UUID resource=id(s,"resource_id");return new Evidence("POSTING_BATCH:"+batchId,"POSTING_BATCH",context+":"+s.path("operation").asText(),resource,LocalDate.parse(s.path("posting_date").asText()),eligible,facts,raw,candidates(resource),false,false,effects.isEmpty()?"POSTING_EFFECT_EVIDENCE_MISSING":"NATIVE_MAPPING_REQUIRED");
    }
    private boolean ownedStock(ArrayNode facts){for(JsonNode f:facts)if(!"STOCK".equals(f.path("domain").asText())||f.hasNonNull("ownerId")||f.hasNonNull("stockCategory")&&!"OWNED".equals(f.path("stockCategory").asText()))return false;return true;}
    Evidence holding(UUID id){JsonNode s=scoped("warehouse_holdings",id);ObjectNode raw=mapper.createObjectNode();ObjectNode state=s.deepCopy();state.remove(List.of("updated_at","updated_by","version"));raw.set("registrySnapshot",state);raw.put("parentResourceType","WarehouseHolding");raw.put("expensePolicy","PRIOR_EXPENSED_AT_INITIAL_ISSUE");String date=s.hasNonNull("updated_at")?s.path("updated_at").asText().substring(0,10):s.hasNonNull("created_at")?s.path("created_at").asText().substring(0,10):LocalDate.now().toString();return new Evidence("HOLDING:"+id,"HOLDING","HOLDING_STATE",id,LocalDate.parse(date),null,mapper.createArrayNode(),raw,candidates(id),true,true,"REGISTRY_ONLY_NO_ADDITIONAL_STOCK_EXPENSE");}
    Evidence transit(UUID id){JsonNode s=scoped("stock_movements",id);if(!"TRANSFER_TRANSIT_LOSS".equals(s.path("reference_type").asText()))throw new BadRequestException("Not a transfer transit loss");var entries=rows("select to_jsonb(t)::text from transfer_transit_entries t where tenant_id=? and company_id=? and operation_id=? and kind='TRANSIT_LOSS' order by id",tenant(),company(),id);if(entries.isEmpty())throw new ConflictException("Transit loss has no immutable transit evidence");var facts=mapper.createArrayNode();var allocations=mapper.createArrayNode();for(JsonNode t:entries){JsonNode a=scoped("transfer_shipment_allocations",id(t,"allocation_id"));allocations.add(a);ObjectNode f=facts.addObject();f.put("domain","TRANSIT");OutboundFacts.put(f,"materialId",id(s,"material_id"));OutboundFacts.put(f,"warehouseId",id(s,"warehouse_id"));f.put("quantity",number(t,"quantity_delta").toPlainString());f.put("amount",number(t,"value_delta").toPlainString());f.put("currency",a.path("book_currency_code").asText("UZS"));OutboundFacts.put(f,"allocationId",id(t,"allocation_id"));OutboundFacts.put(f,"transferId",id(s,"reference_id"));f.put("stockCategory","IN_TRANSIT");}
        ObjectNode raw=mapper.createObjectNode();raw.set("movement",stable(s));raw.set("transitEntries",entries);raw.set("shipmentAllocations",allocations);raw.set("costMovements",rows("select to_jsonb(c)::text from stock_cost_movements c where tenant_id=? and company_id=? and stock_movement_id=? order by id",tenant(),company(),id));raw.put("parentResourceType","TransferDocument");UUID resource=id(s,"reference_id");return new Evidence("TRANSIT_LOSS:"+id,"TRANSIT_LOSS","TRANSIT_LOSS",resource,LocalDate.parse(s.path("movement_date").asText()),null,facts,raw,candidates(id,resource),false,false,"NATIVE_MAPPING_REQUIRED_TRANSIT_ACCOUNT");}
}
