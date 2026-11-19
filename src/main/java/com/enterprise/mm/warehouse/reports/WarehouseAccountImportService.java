package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.InventoryScopeLockService;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.enterprise.mm.warehouse.reports.WarehouseAccountImportDtos.*;

/** Exact accounting positions. Ownership classifications and signed values never
 * become ordinary available stock; the original operational opening is retained. */
@Service @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class WarehouseAccountImportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final CompanyRepository companies;
    private final InventoryScopeLockService locks;
    private static final Set<String> KINDS=Set.of("BALANCE","MOVEMENT");
    private static final Set<String> TYPES=Set.of("MATERIAL","EQUIPMENT","ANIMALS","FIXED_ASSET","FINISHED_GOODS","SEMI_FINISHED_GOODS","GOODS","SERVICES","VALUE_ONLY","UNSPECIFIED");

    @Transactional
    public Map<String,Object> start(String tenant,UUID company,Start request) {
        scope(tenant,company);
        if(request==null||!validator.validate(request).isEmpty()||request.counts().size()!=2
            ||!request.counts().keySet().equals(KINDS)||request.counts().get("BALANCE")==0
            ||request.snapshotDate().isAfter(LocalDate.now())||new UUID(0,0).equals(request.importId()))
            throw new BadRequestException("Invalid warehouse account manifest");
        long total=request.counts().values().stream().mapToLong(Long::longValue).sum();
        if(total>20000000) throw new BadRequestException("Warehouse account import exceeds bounds");
        var organization=companies.findByTenantIdAndIdAndIsDeletedFalse(tenant,company).orElseThrow();
        if(organization.getDescription()==null||!organization.getDescription().contains(":Catalog61:"+request.organizationSourceId()+"]"))
            throw new BadRequestException("Warehouse accounts do not match the company's 1C organization");
        locks.lock(company,List.of());
        var old=jdbc.queryForList("select * from warehouse_account_imports where id=?",request.importId());
        if(!old.isEmpty()) {
            var r=old.getFirst();
            if(!tenant.equals(r.get("tenant_id"))||!company.equals(r.get("company_id"))
                ||!request.manifestSha256().equals(r.get("manifest_sha256"))
                ||!request.organizationSourceId().equals(r.get("organization_source_id"))
                ||!request.snapshotDate().equals(((java.sql.Date)r.get("snapshot_date")).toLocalDate())
                ||!request.expectedChainSha256().equals(r.get("expected_chain"))
                ||!request.counts().equals(counts(r.get("expected_counts").toString())))
                throw new ConflictException("Warehouse account import identity changed");
            return status(tenant,company,request.importId());
        }
        var predecessors=jdbc.queryForList("select status,snapshot_date,organization_source_id from warehouse_account_imports where tenant_id=? and company_id=?",tenant,company);
        for(var predecessor:predecessors) {
            if(!"COMPLETED".equals(predecessor.get("status"))
                ||!request.organizationSourceId().equals(predecessor.get("organization_source_id"))
                ||request.snapshotDate().isBefore(((java.sql.Date)predecessor.get("snapshot_date")).toLocalDate()))
                throw new ConflictException("Warehouse imports require completed predecessors for the same organization and a nondecreasing snapshot date");
        }
        jdbc.update("insert into warehouse_account_imports(id,tenant_id,company_id,organization_source_id,snapshot_date,manifest_sha256,expected_counts,expected_records,expected_chain,chain_sha256) values(?,?,?,?,?,?,cast(? as jsonb),?,?,?)",
            request.importId(),tenant,company,request.organizationSourceId(),request.snapshotDate(),request.manifestSha256(),json(request.counts()),total,request.expectedChainSha256(),"0".repeat(64));
        return status(tenant,company,request.importId());
    }

    @Transactional
    public Map<String,Object> chunk(String tenant,UUID company,UUID id,Chunk request) {
        scope(tenant,company);
        if(request==null||!validator.validate(request).isEmpty()) throw new BadRequestException("Invalid warehouse account chunk");
        var run=run(tenant,company,id,true);
        long first=request.rows().getFirst().seq(),previous=first-1;
        var parsed=new ArrayList<JsonNode>();var keys=new HashSet<String>();var fingerprint=new StringBuilder();
        for(var row:request.rows()) {
            if(row.seq()!=++previous||!sha(row.content()).equals(row.sha256())) throw new BadRequestException("Warehouse source sequence or hash mismatch");
            var data=parse(row.content());
            validate(data,(String)run.get("organization_source_id"),((java.sql.Date)run.get("snapshot_date")).toLocalDate());
            String key=text(data,"sourceKey");
            if(!keys.add(key)) throw new BadRequestException("Duplicate warehouse source key");
            parsed.add(data);fingerprint.append(row.seq()).append(':').append(key).append(':').append(row.sha256()).append('\n');
        }
        String payloadSha=sha(fingerprint.toString());
        var accepted=jdbc.queryForList("select * from warehouse_account_chunks where import_id=? and first_seq=?",id,first);
        if(!accepted.isEmpty()) {
            var old=accepted.getFirst();
            if(!payloadSha.equals(old.get("payload_sha256"))||request.rows().size()!=((Number)old.get("row_count")).intValue())
                throw new ConflictException("Warehouse account chunk replay changed");
            return Map.of("firstSeq",first,"acceptedRows",request.rows().size(),"chainSha256",old.get("chain_sha256"));
        }
        if(!"IMPORTING".equals(run.get("status"))||first!=((Number)run.get("accepted_records")).longValue()+1
            ||previous>((Number)run.get("expected_records")).longValue()) throw new ConflictException("Warehouse account sequence cannot be accepted");
        validateLinks(parsed,tenant,company);
        String chain=(String)run.get("chain_sha256");var values=new ArrayList<Object[]>();
        for(int i=0;i<parsed.size();i++) {
            var d=parsed.get(i);var row=request.rows().get(i);String key=text(d,"sourceKey");
            chain=sha(chain+"\n"+key+":"+row.sha256()+"\n");
            // Source keys recur in later full snapshots; the import is part of row identity.
            UUID recordId=UUID.nameUUIDFromBytes((tenant+":"+company+":"+id+":"+key).getBytes(StandardCharsets.UTF_8));
            values.add(new Object[]{recordId,tenant,company,id,row.seq(),key,text(d,"kind"),LocalDate.parse(text(d,"sourceDate")),
                text(d,"accountSourceId"),text(d,"accountCode"),text(d,"accountName"),text(d,"materialReferenceType"),text(d,"materialSourceId"),text(d,"warehouseSourceId"),
                uuid(d,"materialId"),uuid(d,"warehouseId"),text(d,"materialCode"),text(d,"materialName"),text(d,"warehouseCode"),text(d,"warehouseName"),
                text(d,"sourceType"),text(d,"sourceMaterialType"),text(d,"stockCategory"),text(d,"uom"),text(d,"uomNameRu"),
                new BigDecimal(text(d,"quantity")),new BigDecimal(text(d,"amount")),text(d,"correspondingAccount"),text(d,"counterparty"),text(d,"documentKey"),
                d.get("additionalDimensions").toString(),row.sha256(),row.content()});
        }
        jdbc.batchUpdate("insert into warehouse_account_entries(id,tenant_id,company_id,import_id,source_seq,source_key,kind,source_date,account_source_id,account_code,account_name,material_reference_type,material_source_id,warehouse_source_id,material_id,warehouse_id,material_code,material_name,warehouse_code,warehouse_name,source_type,source_material_type,stock_category,uom,uom_name_ru,quantity,amount,corresponding_account,counterparty,document_key,additional_dimensions,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)",values);
        jdbc.update("insert into warehouse_account_chunks values(?,?,?,?,?)",id,first,request.rows().size(),payloadSha,chain);
        jdbc.update("update warehouse_account_imports set accepted_records=?,chain_sha256=? where id=?",previous,chain,id);
        return Map.of("firstSeq",first,"acceptedRows",request.rows().size(),"chainSha256",chain);
    }

    @Transactional
    public Map<String,Object> complete(String tenant,UUID company,UUID id) {
        scope(tenant,company);var run=run(tenant,company,id,true);
        if("COMPLETED".equals(run.get("status")))return status(tenant,company,id);
        Map<String,Long> actual=actualCounts(tenant,company,id);
        if(!Objects.equals(run.get("accepted_records"),run.get("expected_records"))
            ||!run.get("chain_sha256").equals(run.get("expected_chain"))||!counts(run.get("expected_counts").toString()).equals(actual))
            throw new ConflictException("Warehouse account counts or source chain do not reconcile");
        // Rehash persisted source content, not merely the accepted client chunks.
        String chain="0".repeat(64);long after=0;
        while(true) {
            var proof=proof(tenant,company,id,after,chain);long next=((Number)proof.get("next")).longValue();
            chain=(String)proof.get("chainSha256");if(next==after)break;after=next;
        }
        if(after!=((Number)run.get("expected_records")).longValue()||!chain.equals(run.get("expected_chain")))
            throw new ConflictException("Stored warehouse account content does not match the manifest");
        jdbc.update("update warehouse_account_imports set status='COMPLETED',completed_at=CURRENT_TIMESTAMP where id=? and status='IMPORTING'",id);
        // Register the imported codes in the company catalog without replacing names
        // or classifications already managed by Finance. A deleted matching code is
        // restored because it now has a balance in a completed snapshot.
        jdbc.update("""
            insert into chart_of_accounts
                (id, tenant_id, company_id, created_at, version, is_delete, code, account_code,
                 name, status, is_group, source_system, external_id, last_synced_at)
            select gen_random_uuid(), e.tenant_id, e.company_id, CURRENT_TIMESTAMP, 0, false,
                   e.account_code, e.account_code, min(e.account_name), 'ACTIVE', false,
                   'ONE_C', min(e.account_source_id), CURRENT_TIMESTAMP
            from warehouse_account_entries e
            where e.import_id=? and e.tenant_id=? and e.company_id=?
            group by e.tenant_id, e.company_id, e.account_code
            on conflict (tenant_id, company_id, account_code) do update
                set is_delete=false, deleted_at=null, updated_at=CURRENT_TIMESTAMP,
                    version=chart_of_accounts.version+1
            where chart_of_accounts.is_delete=true
            """, id, tenant, company);
        jdbc.execute("ANALYZE warehouse_account_entries");
        return status(tenant,company,id);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> status(String tenant,UUID company,UUID id) {
        scope(tenant,company);var r=run(tenant,company,id,false);
        return Map.of("importId",id,"companyId",company,"status",r.get("status"),"manifestSha256",r.get("manifest_sha256"),
            "acceptedRecords",r.get("accepted_records"),"expectedRecords",r.get("expected_records"),"chainSha256",r.get("chain_sha256"),
            "expectedChainSha256",r.get("expected_chain"),"counts",actualCounts(tenant,company,id),"snapshotDate",r.get("snapshot_date"));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> proof(String tenant,UUID company,UUID id,long after,String previousChain) {
        scope(tenant,company);run(tenant,company,id,false);
        if(after<0||previousChain==null||!previousChain.matches("[a-f0-9]{64}")) throw new BadRequestException("Invalid warehouse account verification cursor");
        var stored=jdbc.queryForList("select * from warehouse_account_entries where tenant_id=? and company_id=? and import_id=? and source_seq>? order by source_seq limit 5000",tenant,company,id,after);
        String chain=previousChain;long next=after;
        for(var row:stored) {
            if(((Number)row.get("source_seq")).longValue()!=++next) throw new ConflictException("Stored warehouse sequence has a gap");
            String hash=sha((String)row.get("content"));
            if(!hash.equals(row.get("row_sha256"))) throw new ConflictException("Stored warehouse content hash changed");
            verifyTyped(row,parse((String)row.get("content")));
            chain=sha(chain+"\n"+row.get("source_key")+":"+hash+"\n");
        }
        return Map.of("next",next,"rows",stored.size(),"chainSha256",chain);
    }

    private Map<String,Object> run(String tenant,UUID company,UUID id,boolean lock) {
        var found=jdbc.queryForList("select * from warehouse_account_imports where id=? and tenant_id=? and company_id=?"+(lock?" for update":""),id,tenant,company);
        if(found.size()!=1) throw new NotFoundException("Warehouse account import not found");return found.getFirst();
    }
    private Map<String,Long> actualCounts(String tenant,UUID company,UUID id) {
        var result=new TreeMap<String,Long>();for(String kind:KINDS)result.put(kind,0L);
        jdbc.query("select kind,count(*) total from warehouse_account_entries where import_id=? and tenant_id=? and company_id=? group by kind",rs->{result.put(rs.getString("kind"),rs.getLong("total"));},id,tenant,company);
        return result;
    }
    void scope(String tenant,UUID company) {
        if(!TenantContext.valid(tenant)||!tenant.equals(TenantContext.currentTenant())||company==null
            ||CompanyContext.DEFAULT_COMPANY_ID.equals(company)||!company.equals(CompanyContext.currentCompany())
            ||companies.findByTenantIdAndIdAndIsDeletedFalse(tenant,company).isEmpty())
            throw new BadRequestException("Warehouse accounts require an explicit company in the current tenant");
    }
    void validate(JsonNode d,String organization,LocalDate snapshot) {
        if(!d.isObject()||!organization.equals(text(d,"organizationSourceId"))||!KINDS.contains(text(d,"kind"))
            ||!TYPES.contains(text(d,"sourceType"))) throw new BadRequestException("Invalid warehouse source organization or type");
        required(d,"sourceKey",240);required(d,"accountName",1000);required(d,"materialName",2000);required(d,"warehouseName",2000);
        for(String f:List.of("accountSourceId","materialSourceId","warehouseSourceId"))
            if(text(d,f)==null||!text(d,f).matches("[a-f0-9]{32}")) throw new BadRequestException("Invalid warehouse source identity");
        if(!text(d,"sourceKey").matches("[a-zA-Z0-9_: .-]{1,240}")||text(d,"accountCode")==null||!text(d,"accountCode").matches("[0-9]{3,6}")
            ||!Set.of("00000036","00000037","000016f4","NONE").contains(text(d,"materialReferenceType"))) throw new BadRequestException("Invalid warehouse source account or catalog");
        if("NONE".equals(text(d,"materialReferenceType"))
            && (!"VALUE_ONLY".equals(text(d,"sourceType"))||!"0".repeat(32).equals(text(d,"materialSourceId"))
                ||new BigDecimal(text(d,"quantity")).signum()!=0||d.hasNonNull("materialId")||!"NO_QUANTITY".equals(text(d,"uom"))))
            throw new BadRequestException("Warehouse-only valuations cannot invent a material or physical quantity");
        if("000016f4".equals(text(d,"materialReferenceType"))&&!"GOODS".equals(text(d,"sourceType")))
            throw new BadRequestException("The separate goods catalog must retain its source type");
        LocalDate date;
        try {date=LocalDate.parse(text(d,"sourceDate"));}catch(RuntimeException ex){throw new BadRequestException("Invalid warehouse source date");}
        if(date.isAfter(snapshot)||("BALANCE".equals(text(d,"kind"))&&!date.equals(snapshot))) throw new BadRequestException("Warehouse source date is outside the snapshot");
        for(String f:List.of("quantity","amount")) {
            String number=text(d,f);int scale=f.equals("quantity")?7:4;
            if(number==null||!d.get(f).isTextual()||!number.matches("-?[0-9]{1,15}(\\.[0-9]{1,"+scale+"})?")) throw new BadRequestException("Warehouse decimals must be exact signed strings");
        }
        if(new BigDecimal(text(d,"quantity")).signum()==0&&new BigDecimal(text(d,"amount")).signum()==0)
            throw new BadRequestException("Empty warehouse position");
        for(String f:List.of("materialCode","warehouseCode","sourceMaterialType","uom","uomNameRu","correspondingAccount","counterparty")) required(d,f,2000);
        required(d,"stockCategory",40);
        if(!category(text(d,"accountCode")).equals(text(d,"stockCategory"))) throw new BadRequestException("Warehouse account classification changed");
        if("00000037".equals(text(d,"materialReferenceType"))&&!Set.of("FINISHED_GOODS","SEMI_FINISHED_GOODS","GOODS","SERVICES","UNSPECIFIED").contains(text(d,"sourceType"))
            ||"00000036".equals(text(d,"materialReferenceType"))&&!Set.of("MATERIAL","EQUIPMENT","ANIMALS","FIXED_ASSET","UNSPECIFIED").contains(text(d,"sourceType")))
            throw new BadRequestException("Product and material source catalogs must keep their original types");
        if(!d.path("additionalDimensions").isObject()) throw new BadRequestException("Warehouse accounting dimensions required");
        for(String f:List.of("materialId","warehouseId")) if(d.hasNonNull(f)) {
            try {UUID.fromString(text(d,f));}catch(RuntimeException ex){throw new BadRequestException("Invalid mapped warehouse reference");}
        }
        if("MOVEMENT".equals(text(d,"kind"))) {
            required(d,"documentKey",240);
            String identity=String.join(":",text(d,"documentKey"),text(d,"sourceDate"),text(d,"accountSourceId"),text(d,"materialReferenceType"),text(d,"materialSourceId"),text(d,"warehouseSourceId"));
            String extended=identity+":"+canonicalDimensions(d.get("additionalDimensions"));
            boolean addition=Set.of("000016f4","NONE").contains(text(d,"materialReferenceType"));
            if(!text(d,"sourceKey").equals("movement:"+sha(identity))&&(!addition||!text(d,"sourceKey").equals("movement:"+sha(extended))))
                throw new BadRequestException("Warehouse movement source identity changed");
        } else {
            String[] key=text(d,"sourceKey").split(":");
            var dimensions=new TreeMap<String,JsonNode>();d.get("additionalDimensions").fields().forEachRemaining(e->dimensions.put(e.getKey(),e.getValue()));
            if(key.length!=4 || !Set.of("_accrgat14297","_accrgat24298","_accrgat34299","_accrgat44300").contains(key[1])
                || !text(d,"sourceKey").equals("balance:"+key[1]+":"+text(d,"accountSourceId")+":"+sha(json(dimensions))))
                throw new BadRequestException("Warehouse balance source identity changed");
        }
    }
    private void validateLinks(List<JsonNode> data,String tenant,UUID company) {
        for(var field:Map.of("materialId","materials","warehouseId","warehouses").entrySet()) {
            var ids=data.stream().map(d->uuid(d,field.getKey())).filter(Objects::nonNull).distinct().toList();
            if(ids.isEmpty())continue;
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>(ids);args.add(tenant);args.add(company);
            var links=jdbc.queryForList("select id,description from "+field.getValue()+" where id in ("+placeholders+") and tenant_id=? and company_id=? and is_delete=false",args.toArray());
            if(links.size()!=ids.size()) throw new BadRequestException("Mapped warehouse references belong to another scope or are deleted");
            var descriptions=new HashMap<UUID,String>();for(var link:links)descriptions.put((UUID)link.get("id"),(String)link.get("description"));
            for(var d:data) {
                UUID mapped=uuid(d,field.getKey());if(mapped==null)continue;
                String catalog=switch(text(d,"materialReferenceType")){case "00000037"->"Catalog55";case "000016f4"->"Catalog5876";default->"Catalog54";};
                String marker=field.getKey().equals("materialId")?":"+catalog+":"+text(d,"materialSourceId")+"]":":Catalog85:"+text(d,"warehouseSourceId")+"]";
                if(descriptions.get(mapped)==null || !descriptions.get(mapped).contains(marker)
                    || (field.getKey().equals("materialId") && "NONE".equals(text(d,"materialReferenceType"))))
                    throw new BadRequestException("Mapped reference does not match its 1C source identity");
            }
        }
    }
    static String category(String account) {
        if(account.equals("1070"))return "PRECIOUS_METALS_CUSTODY";
        if(account.startsWith("10"))return "OWNED_MATERIAL";
        if(account.equals("002"))return "CUSTODY";
        if(account.equals("006"))return "STRICT_FORMS";
        if(account.equals("014"))return "MATERIALS_IN_USE";
        if(Set.of("018","019").contains(account)||account.startsWith("591"))return "SHORTAGE";
        if(account.startsWith("07"))return "INSTALLATION_EQUIPMENT";
        if(account.equals("0820"))return "ASSET_ACQUISITION";
        if(account.startsWith("28"))return "FINISHED_PRODUCTS";
        if(account.startsWith("29"))return "TRADING_GOODS";
        return "OTHER_ACCOUNT";
    }
    private String canonicalDimensions(JsonNode node) {
        var values=new TreeMap<String,JsonNode>();node.fields().forEachRemaining(e->values.put(e.getKey(),e.getValue()));return json(values);
    }
    private void verifyTyped(Map<String,Object> stored,JsonNode source) {
        for(var field:Map.ofEntries(Map.entry("source_key","sourceKey"),Map.entry("kind","kind"),Map.entry("account_source_id","accountSourceId"),
            Map.entry("account_code","accountCode"),Map.entry("account_name","accountName"),Map.entry("material_reference_type","materialReferenceType"),
            Map.entry("material_source_id","materialSourceId"),Map.entry("warehouse_source_id","warehouseSourceId"),Map.entry("material_code","materialCode"),
            Map.entry("material_name","materialName"),Map.entry("warehouse_code","warehouseCode"),Map.entry("warehouse_name","warehouseName"),
            Map.entry("source_type","sourceType"),Map.entry("source_material_type","sourceMaterialType"),Map.entry("stock_category","stockCategory"),
            Map.entry("uom","uom"),Map.entry("uom_name_ru","uomNameRu"),Map.entry("corresponding_account","correspondingAccount"),Map.entry("counterparty","counterparty"),Map.entry("document_key","documentKey")).entrySet())
            if(!Objects.equals(stored.get(field.getKey()),text(source,field.getValue()))) throw new ConflictException("Stored warehouse report fields changed");
        if(!Objects.equals(stored.get("material_id"),uuid(source,"materialId"))||!Objects.equals(stored.get("warehouse_id"),uuid(source,"warehouseId"))
            || !((java.sql.Date)stored.get("source_date")).toLocalDate().equals(LocalDate.parse(text(source,"sourceDate")))
            || ((BigDecimal)stored.get("quantity")).compareTo(new BigDecimal(text(source,"quantity")))!=0
            || ((BigDecimal)stored.get("amount")).compareTo(new BigDecimal(text(source,"amount")))!=0
            || !parse(stored.get("additional_dimensions").toString()).equals(source.get("additionalDimensions")))
            throw new ConflictException("Stored warehouse report values changed");
    }
    private void required(JsonNode d,String field,int max) {
        String value=text(d,field);if(value==null||value.isBlank()||value.length()>max)throw new BadRequestException("Invalid warehouse field "+field);
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new BadRequestException("Invalid warehouse JSON");}}
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(Exception ex){throw new BadRequestException("Invalid warehouse source JSON");}}
    private Map<String,Long> counts(String value) {var result=new TreeMap<String,Long>();parse(value).fields().forEachRemaining(e->result.put(e.getKey(),e.getValue().asLong()));return result;}
    private static String text(JsonNode d,String field) {var n=d.get(field);return n==null||n.isNull()?null:n.asText();}
    private static UUID uuid(JsonNode d,String field) {String value=text(d,field);return value==null?null:UUID.fromString(value);}
    static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
}
