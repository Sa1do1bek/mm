package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.service.InventoryScopeLockService;
import com.enterprise.mm.warehouse.history.WarehouseHistoryDtos.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Import-only historical evidence. No stock, costing or workflow services are called. */
@Service @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class WarehouseHistoryImportService {
    static final Set<String> KINDS=Set.of("SCHEMA","DOCUMENT","LINE","MOVEMENT");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final CompanyRepository companies;
    private final EntityManager em;
    private final InventoryScopeLockService locks;

    @Transactional
    public Run start(String tenant,UUID company,Start request) {
        scope(tenant,company);
        if(request==null||!validator.validate(request).isEmpty()||new UUID(0,0).equals(request.importId())
            ||!KINDS.containsAll(request.counts().keySet())||request.counts().values().stream().anyMatch(Objects::isNull))
            throw new BadRequestException("Invalid historical import manifest");
        var sourceCompany=companies.findByTenantIdAndIdAndIsDeletedFalse(tenant,company).orElseThrow(()->new BadRequestException("Company missing"));
        if(sourceCompany.getDescription()==null||!sourceCompany.getDescription().contains(":Catalog61:"+request.organizationSourceId()+"]"))
            throw new BadRequestException("Historical source organization differs from the mapped company");
        long total=request.counts().values().stream().mapToLong(Long::longValue).sum();
        if(total<1||total>20000000) throw new BadRequestException("Historical import count outside bounds");
        locks.lock(company,List.of());
        var existing=jdbc.queryForList("select * from warehouse_history_runs where id=?",request.importId());
        if(!existing.isEmpty()) {
            var run=existing.getFirst();
            if(!tenant.equals(run.get("tenant_id"))||!company.equals(run.get("company_id"))
                ||!request.manifestSha256().equals(run.get("manifest_sha256"))
                ||!request.expectedChainSha256().equals(run.get("expected_chain"))
                ||!request.organizationSourceId().equals(run.get("organization_source_id"))
                ||total!=((Number)run.get("expected_records")).longValue()
                ||!request.counts().equals(readCounts((String)run.get("expected_counts"))))
                throw new ConflictException("Historical import identity or manifest changed");
            return status(tenant,company,request.importId());
        }
        var auth=SecurityContextHolder.getContext().getAuthentication();
        jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status,created_by) values(?,?,?,?,?,?,?,?,?,'IMPORTING',?)",
            request.importId(),tenant,company,request.manifestSha256(),request.organizationSourceId(),json(request.counts()),total,
            request.expectedChainSha256(),"0".repeat(64),auth==null?null:auth.getName());
        return status(tenant,company,request.importId());
    }

    @Transactional
    public ChunkResult chunk(String tenant,UUID company,UUID id,Chunk request) {
        scope(tenant,company);
        if(request==null||!validator.validate(request).isEmpty()) throw new BadRequestException("Invalid historical chunk");
        var runs=jdbc.queryForList("select * from warehouse_history_runs where id=? and tenant_id=? and company_id=? for update",id,tenant,company);
        if(runs.size()!=1) throw new NotFoundException("Historical import not found");
        var run=runs.getFirst();
        long first=request.rows().getFirst().seq(), previous=first-1;
        var parsed=new ArrayList<JsonNode>();var keys=new HashSet<String>();
        StringBuilder fingerprint=new StringBuilder();
        for(var row:request.rows()) {
            if(row.seq()!=++previous||!sha(row.content()).equals(row.sha256())) throw new BadRequestException("Historical row sequence or SHA256 mismatch");
            var data=parse(row.content());
            validate(data,(String)run.get("organization_source_id"));
            if(!keys.add(data.path("sourceKey").asText())) throw new BadRequestException("Duplicate historical source identity");
            parsed.add(data);fingerprint.append(row.seq()).append(':').append(data.path("sourceKey").asText()).append(':').append(row.sha256()).append('\n');
        }
        String payloadSha=sha(fingerprint.toString());
        var old=jdbc.queryForList("select * from warehouse_history_chunks where import_id=? and first_seq=?",id,first);
        if(!old.isEmpty()) {
            var chunk=old.getFirst();
            if(!payloadSha.equals(chunk.get("payload_sha256"))||request.rows().size()!=((Number)chunk.get("row_count")).intValue())
                throw new ConflictException("Historical chunk was already accepted with different data");
            return new ChunkResult(first,request.rows().size(),payloadSha,(String)chunk.get("chain_sha256"));
        }
        if(!"IMPORTING".equals(run.get("status"))||first!=((Number)run.get("accepted_records")).longValue()+1
            ||previous>((Number)run.get("expected_records")).longValue()) throw new ConflictException("Historical import does not accept this sequence");
        validateLinks(parsed,tenant,company);
        String chain=(String)run.get("chain_sha256");
        var values=new ArrayList<Object[]>();
        for(int i=0;i<parsed.size();i++) {
            var data=parsed.get(i);var row=request.rows().get(i);String key=text(data,"sourceKey");
            chain=sha(chain+"\n"+key+":"+row.sha256()+"\n");
            UUID recordId=UUID.nameUUIDFromBytes((tenant+":"+company+":"+key).getBytes(StandardCharsets.UTF_8));
            values.add(new Object[]{recordId,tenant,company,id,row.seq(),text(data,"kind"),key,text(data,"sourceTable"),
                text(data,"documentKey"),text(data,"documentType"),date(data),text(data,"documentNumber"),bool(data,"posted"),bool(data,"marked"),
                uuid(data,"materialId"),uuid(data,"warehouseId"),uuid(data,"targetWarehouseId"),text(data,"quantity"),text(data,"value"),text(data,"direction"),row.sha256(),row.content()});
        }
        jdbc.batchUpdate("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,document_date,document_number,posted,marked,material_id,warehouse_id,target_warehouse_id,quantity,book_value,direction,row_sha256,content) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
        new WarehouseSourceProfileProjection(jdbc,mapper).apply(tenant,company,parsed);
        jdbc.update("insert into warehouse_history_chunks values(?,?,?,?,?)",id,first,request.rows().size(),payloadSha,chain);
        jdbc.update("update warehouse_history_runs set accepted_records=?,chain_sha256=? where id=?",previous,chain,id);
        return new ChunkResult(first,request.rows().size(),payloadSha,chain);
    }

    @Transactional
    public Run complete(String tenant,UUID company,UUID id) {
        scope(tenant,company);
        jdbc.queryForList("select id from warehouse_history_runs where id=? and tenant_id=? and company_id=? for update",id,tenant,company);
        Run run=status(tenant,company,id);
        var expected=jdbc.queryForObject("select expected_counts from warehouse_history_runs where id=?",String.class,id);
        if(run.acceptedRecords()!=run.expectedRecords()||!run.chainSha256().equals(run.expectedChainSha256())||!run.counts().equals(readCounts(expected)))
            throw new ConflictException("Historical import counts or full source hash chain do not reconcile");
        jdbc.update("update warehouse_history_runs set status='COMPLETED',completed_at=CURRENT_TIMESTAMP where id=?",id);
        return status(tenant,company,id);
    }

    @Transactional(readOnly=true)
    public Run status(String tenant,UUID company,UUID id) {
        scope(tenant,company);
        var rows=jdbc.queryForList("select * from warehouse_history_runs where id=? and tenant_id=? and company_id=?",id,tenant,company);
        if(rows.size()!=1) throw new NotFoundException("Historical import not found");
        var r=rows.getFirst();var counts=new TreeMap<String,Long>();
        jdbc.query("select kind,count(*) as total from warehouse_history_records where import_id=? and tenant_id=? and company_id=? group by kind",
            rs->{counts.put(rs.getString("kind"),rs.getLong("total"));},id,tenant,company);
        return new Run(id,company.toString(),(String)r.get("manifest_sha256"),(String)r.get("status"),
            ((Number)r.get("accepted_records")).longValue(),((Number)r.get("expected_records")).longValue(),
            (String)r.get("chain_sha256"),(String)r.get("expected_chain"),counts);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> chunks(String tenant,UUID company,UUID id,long after) {
        status(tenant,company,id);
        return jdbc.queryForList("select first_seq,row_count,payload_sha256,chain_sha256 from warehouse_history_chunks where import_id=? and first_seq>? order by first_seq limit 500",id,after);
    }

    /** Rehash bounded stored pages, independently of the upload/chunk journals. */
    @Transactional(readOnly=true)
    public Map<String,Object> proof(String tenant,UUID company,UUID id,long after,String previousChain) {
        scope(tenant,company);
        if(after<0||previousChain==null||!previousChain.matches("[a-f0-9]{64}")) throw new BadRequestException("Invalid verification cursor");
        var runs=jdbc.queryForList("select id from warehouse_history_runs where id=? and tenant_id=? and company_id=?",id,tenant,company);
        if(runs.isEmpty()) throw new NotFoundException("Historical import not found");
        var stored=jdbc.queryForList("select source_seq,source_key,row_sha256,content from warehouse_history_records where import_id=? and tenant_id=? and company_id=? and source_seq>? order by source_seq limit 5000",id,tenant,company,after);
        String chain=previousChain;long next=after;
        for(var row:stored) {
            if(((Number)row.get("source_seq")).longValue()!=++next) throw new ConflictException("Stored history sequence is incomplete");
            String hash=sha((String)row.get("content"));
            if(!hash.equals(row.get("row_sha256"))) throw new ConflictException("Stored history content SHA256 mismatch");
            chain=sha(chain+"\n"+row.get("source_key")+":"+hash+"\n");
        }
        return Map.of("after",after,"next",next,"rows",stored.size(),"chainSha256",chain);
    }

    void scope(String tenant,UUID company) {
        if(!TenantContext.valid(tenant)||!tenant.equals(TenantContext.currentTenant())||company==null
            ||new UUID(0,0).equals(company)||CompanyContext.DEFAULT_COMPANY_ID.equals(company)||!company.equals(CompanyContext.currentCompany())
            ||companies.findByTenantIdAndIdAndIsDeletedFalse(tenant,company).isEmpty())
            throw new BadRequestException("Historical import requires an explicit company in the current tenant");
    }
    private void validate(JsonNode data,String organization) {
        if(!data.isObject()||!KINDS.contains(text(data,"kind"))||!organization.equals(text(data,"organizationSourceId"))
            ||text(data,"sourceKey")==null||!text(data,"sourceKey").matches("[a-zA-Z0-9_: .-]{1,180}")
            ||text(data,"sourceTable")==null||!text(data,"sourceTable").matches("_[a-z0-9_]{1,79}")
            ||text(data,"documentType")==null||text(data,"documentType").length()>240||!data.path("data").isObject())
            throw new BadRequestException("Invalid historical source row");
        for(String field:List.of("documentKey","documentNumber","direction")) {
            String value=text(data,field);int limit=field.equals("direction")?20:field.equals("documentKey")?180:240;
            if(value!=null&&value.length()>limit) throw new BadRequestException("Historical field exceeds limit");
        }
        if(!"SCHEMA".equals(text(data,"kind"))&&(date(data)==null||text(data,"documentKey")==null))
            throw new BadRequestException("Historical document identity and date required");
        for(String field:List.of("quantity","value")) {
            var value=data.get(field);
            if(value!=null&&!value.isNull()&&(!value.isTextual()||!value.asText().matches("-?[0-9]{1,30}(\\.[0-9]{1,20})?")))
                throw new BadRequestException("Historical decimals must remain exact strings");
        }
        for(String field:List.of("posted","marked")) if(data.hasNonNull(field)&&!data.get(field).isBoolean()) throw new BadRequestException("Historical source flags must be boolean");
    }
    private void validateLinks(List<JsonNode> rows,String tenant,UUID company) {
        Map<String,Class<? extends BusinessEntity>> types=Map.of("materialId",Material.class,"warehouseId",Warehouse.class,"targetWarehouseId",Warehouse.class);
        for(var entry:types.entrySet()) {
            var ids=new HashSet<UUID>();for(var row:rows) {UUID id=uuid(row,entry.getKey());if(id!=null)ids.add(id);}
            if(ids.isEmpty())continue;
            var found=em.createQuery("select e.id from "+entry.getValue().getSimpleName()+" e where e.tenantId=:tenant and e.companyId=:company and e.isDeleted=false and e.id in :ids",UUID.class)
                .setParameter("tenant",tenant).setParameter("company",company).setParameter("ids",ids).getResultList();
            if(found.size()!=ids.size())throw new BadRequestException("Historical link is outside the current company or missing");
        }
    }
    static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode parse(String value) {try{return mapper.readTree(value);}catch(Exception e){throw new BadRequestException("Invalid historical JSON");}}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Long> readCounts(String value) {var out=new TreeMap<String,Long>();parse(value).fields().forEachRemaining(e->out.put(e.getKey(),e.getValue().asLong()));return out;}
    static String text(JsonNode data,String key) {return data.hasNonNull(key)?data.get(key).asText():null;}
    static UUID uuid(JsonNode data,String key) {try{String value=text(data,key);return value==null?null:UUID.fromString(value);}catch(Exception e){throw new BadRequestException("Invalid historical MM link");}}
    private Boolean bool(JsonNode data,String key) {return data.hasNonNull(key)?data.get(key).asBoolean():null;}
    private LocalDateTime date(JsonNode data) {try{String value=text(data,"documentDate");return value==null?null:LocalDateTime.parse(value.replace(' ','T'));}catch(Exception e){throw new BadRequestException("Invalid historical source date");}}
}
