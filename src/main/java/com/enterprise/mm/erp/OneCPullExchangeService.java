package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, company-scoped pull transport. The native worker executes existing native business logic. */
@Service
public class OneCPullExchangeService {
    static final int MAX_RESULT_BYTES=16*1024*1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OneCTransport transport;
    private final OneCNativePostingProfile nativePosting;

    @Autowired
    public OneCPullExchangeService(JdbcTemplate jdbc, ObjectMapper mapper, OneCTransport transport,
                                  OneCNativePostingProfile nativePosting) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transport = transport;
        this.nativePosting = nativePosting;
    }

    public OneCPullExchangeService(JdbcTemplate jdbc,ObjectMapper mapper,OneCTransport transport) {
        this(jdbc, mapper, transport, new OneCNativePostingProfile(null));
    }
    private String tenant(){return TenantContext.currentTenant();}
    private UUID company(){return CompanyContext.currentCompany();}
    private void lockScope(){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text)||':'||cast(? as text)||':one-c-pull',0))",Object.class,tenant(),company());}
    private void enabled(){
        if(!transport.pullEnabled())throw new ConflictException("Native pull transport is disabled");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from erp_exchange_connection where tenant_id=? and company_id=? and enabled=true)",Boolean.class,tenant(),company())))
            throw new ConflictException("1C exchange is disabled for this company");
    }
    @Transactional
    public Map<String,Object> poll(JsonNode request) {
        if(request==null || !request.path("protocolVersion").isIntegralNumber() || request.path("protocolVersion").asInt()!=1
            || !request.path("workerId").isTextual() || !request.path("workerId").asText().matches("[A-Za-z0-9_.:-]{1,128}")
            || !request.path("limit").isIntegralNumber() || request.path("limit").asInt()!=1
            || request.has("nativePostingV1") && !request.path("nativePostingV1").isBoolean())
            throw new BadRequestException("Protocol 1, stable workerId and limit=1 are required");
        enabled();lockScope();String worker=request.path("workerId").asText();
        boolean acceptsNativePosting = request.path("nativePostingV1").asBoolean();
        var active=jdbc.queryForList("select * from one_c_pull_task where tenant_id=? and company_id=? and state='LEASED' and lease_expires_at>current_timestamp for update",tenant(),company());
        if(!active.isEmpty()) {
            var task = active.getFirst();
            return pollResponse(Objects.equals(worker, task.get("worker_id")) && canDeliver(task, acceptsNativePosting)
                ? List.of(describeTask(task)) : List.of());
        }
        jdbc.update("update one_c_pull_task set state='RETRY',worker_id=null where tenant_id=? and company_id=? and state='LEASED'",tenant(),company());
        // Only the oldest unfinished accounting document can enter the queue. Awaiting confirmation blocks later documents.
        var head=jdbc.queryForList("""
            select e.id,e.payload,e.confirm_requested,e.delivery_state,e.attempt_count,
                   exists(select 1 from one_c_pull_task t where t.envelope_id=e.id
                       and t.tenant_id=e.tenant_id and t.company_id=e.company_id) as has_pull_task
            from erp_exchange_envelope e
            where e.tenant_id=? and e.company_id=? and e.operation is not null and e.delivery_state not in ('POSTED','LEGACY_LOCAL')
            order by delivery_order limit 1 for update
            """,tenant(),company());
        if(!head.isEmpty()) {
            var e=head.getFirst();String state=String.valueOf(e.get("delivery_state"));
            boolean confirm=Boolean.TRUE.equals(e.get("confirm_requested"));
            if(Set.of("PENDING","RETRY").contains(state)||"AWAITING_CONFIRMATION".equals(state)&&confirm) {
                enqueueDocumentTask(e, state, confirm, acceptsNativePosting);
            }
        }
        var due=jdbc.queryForList("""
            select * from one_c_pull_task where tenant_id=? and company_id=? and state in ('PENDING','RETRY') and next_attempt_at<=current_timestamp
            and (case when task_type='BALANCES' then false else jsonb_exists(cast(payload as jsonb),'nativePosting') end)=?
            order by created_at,id limit 1 for update
            """,tenant(),company(),acceptsNativePosting);
        if(due.isEmpty())return pollResponse(List.of());
        UUID id=(UUID)due.getFirst().get("id"),token=UUID.randomUUID();
        jdbc.update("""
            update one_c_pull_task set state='LEASED',worker_id=?,lease_token=?,lease_expires_at=current_timestamp+interval '120 seconds',
                result_hash=null,result=null,attempt_count=attempt_count+1 where id=?
            """,worker,token,id);
        var row=jdbc.queryForMap("select * from one_c_pull_task where id=?",id);
        return pollResponse(List.of(describeTask(row)));
    }

    private void enqueueDocumentTask(Map<String, Object> envelope, String state, boolean confirm,
                                     boolean acceptsNativePosting) {
        String payload = String.valueOf(envelope.get("payload"));
        JsonNode event = read(payload);
        boolean firstDelivery = "PENDING".equals(state) && !confirm
            && ((Number) envelope.get("attempt_count")).intValue() == 0
            && !Boolean.TRUE.equals(envelope.get("has_pull_task"));
        if (firstDelivery && nativePosting.appliesTo(tenant(), company()) && !event.has("nativePosting")) {
            try {
                if (!(event instanceof ObjectNode document)) {
                    throw new OneCNativePostingProfile.MissingMapping("Invalid MM document payload");
                }
                payload = nativePosting.resolve(tenant(), company(), document).toString();
            } catch (OneCNativePostingProfile.MissingMapping missing) {
                String reason = "MAPPING_REQUIRED: " + missing.getMessage();
                jdbc.update("""
                    update erp_exchange_envelope set rejection_reason=?
                    where id=? and tenant_id=? and company_id=? and rejection_reason is distinct from ?
                    """,reason,envelope.get("id"),tenant(),company(),reason);
                return;
            }
            jdbc.update("""
                update erp_exchange_envelope set payload=?,rejection_reason=null where id=? and tenant_id=? and company_id=?
                """,payload,envelope.get("id"),tenant(),company());
            event = read(payload);
        }
        if (event.has("nativePosting") != acceptsNativePosting) return;
        jdbc.update("""
            insert into one_c_pull_task(id,tenant_id,company_id,task_type,envelope_id,event_id,payload)
            values(?,?,?,?,?,?,?) on conflict(envelope_id,task_type) do nothing
            """,UUID.randomUUID(),tenant(),company(),confirm?"CONFIRM":"DOCUMENT",envelope.get("id"),envelope.get("id"),payload);
    }

    private boolean canDeliver(Map<String, Object> task, boolean acceptsNativePosting) {
        if ("BALANCES".equals(task.get("task_type"))) return !acceptsNativePosting;
        return read(String.valueOf(task.get("payload"))).has("nativePosting") == acceptsNativePosting;
    }

    boolean usesCentralPostingProfile() { return nativePosting.appliesTo(tenant(), company()); }

    private Map<String,Object> pollResponse(List<Map<String,Object>> tasks){return Map.of("protocolVersion",1,"tasks",tasks);}
    private Map<String,Object> describeTask(Map<String,Object> row) {
        return Map.of("taskId",row.get("id"),"leaseToken",row.get("lease_token"),"type",row.get("task_type"),"eventId",row.get("event_id"),
            "payload",read(String.valueOf(row.get("payload"))),"leaseExpiresAt",instant(row.get("lease_expires_at")));
    }
    @Transactional
    public Map<String,Object> result(UUID id,JsonNode request) {
        if(!transport.pullEnabled())throw new ConflictException("Native pull transport is disabled");
        lockScope();var rows=jdbc.queryForList("select * from one_c_pull_task where id=? and tenant_id=? and company_id=? for update",id,tenant(),company());
        if(rows.isEmpty())throw new NotFoundException("1C task not found");
        var row=rows.getFirst();validateResult(request,(UUID)row.get("lease_token"));String hash=hash(request);
        if(row.get("result_hash")!=null) {
            if(hash.equals(row.get("result_hash")))return accepted(id);
            throw new ConflictException("A different result is already recorded for this lease");
        }
        if(!"LEASED".equals(row.get("state")))throw new ConflictException("Task is not leased");
        boolean success=request.path("success").asBoolean();JsonNode evidence=request.path("result");
        if(success) {
            if("BALANCES".equals(row.get("task_type"))) {
                JsonNode payload=read(String.valueOf(row.get("payload")));LocalDate date=LocalDate.parse(payload.path("date").asText());
                if(payload.hasNonNull("from"))ErpPeriodEvidence.parse(evidence,tenant(),company(),LocalDate.parse(payload.path("from").asText()),date);
                else ErpReconciliationService.parse(evidence,tenant(),company(),date);
            } else {
                ErpDeliveryWorker.Acknowledgment ack;
                try { ack=ErpDeliveryWorker.parseAcknowledgment(evidence,(UUID)row.get("event_id")); }
                catch(IllegalStateException ex){throw new BadRequestException(ex.getMessage());}
                var envelope=jdbc.queryForMap("select remote_document_ref from erp_exchange_envelope where id=? and tenant_id=? and company_id=? for update",row.get("envelope_id"),tenant(),company());
                if("CONFIRM".equals(row.get("task_type")) && (!ack.posted()||!ack.documentRef().equals(envelope.get("remote_document_ref"))))
                    throw new ConflictException("Confirmation must post the originally acknowledged native document");
                jdbc.update("""
                    update erp_exchange_envelope set delivery_state=?,remote_document_ref=?,remote_posted=?,confirm_requested=false,
                        rejection_reason=null,attempt_count=attempt_count+1 where id=? and tenant_id=? and company_id=?
                    """,ack.status(),ack.documentRef(),ack.posted(),row.get("envelope_id"),tenant(),company());
            }
            jdbc.update("update one_c_pull_task set state='COMPLETE',result=?,result_hash=?,last_error=null,completed_at=current_timestamp where id=?",json(evidence),hash,id);
        } else {
            String error=sanitize(request.path("error").asText());
            jdbc.update("""
                update one_c_pull_task set state='RETRY',result_hash=?,last_error=?,next_attempt_at=current_timestamp+interval '30 seconds' where id=?
                """,hash,error,id);
            if(row.get("envelope_id")!=null)jdbc.update("update erp_exchange_envelope set delivery_state='RETRY',rejection_reason=?,attempt_count=attempt_count+1,next_attempt_at=current_timestamp+interval '30 seconds' where id=? and tenant_id=? and company_id=?",error,row.get("envelope_id"),tenant(),company());
        }
        return accepted(id);
    }
    private Map<String,Object> accepted(UUID id){return Map.of("protocolVersion",1,"taskId",id,"accepted",true);}
    static void validateResult(JsonNode value,UUID token) {
        if(value==null || !value.path("protocolVersion").isIntegralNumber()||value.path("protocolVersion").asInt()!=1
            || token==null || !token.toString().equals(value.path("leaseToken").asText()) || !value.path("success").isBoolean()
            || value.toString().getBytes(StandardCharsets.UTF_8).length>MAX_RESULT_BYTES
            || value.path("success").asBoolean()&&!value.path("result").isObject()
            || !value.path("success").asBoolean()&&(!value.path("error").isTextual()||value.path("error").asText().isBlank()||value.path("error").asText().length()>2000))
            throw new BadRequestException("Invalid native task result or stale lease token");
    }
    static String hash(JsonNode value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static String canonical(JsonNode node) {
        if(node.isObject()) {
            var fields=new TreeMap<String,JsonNode>();node.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            var values=new ArrayList<String>();fields.forEach((key,value)->{try{values.add(com.fasterxml.jackson.databind.node.TextNode.valueOf(key).toString()+":"+canonical(value));}catch(Exception ex){throw new IllegalStateException(ex);}});
            return "{"+String.join(",",values)+"}";
        }
        if(node.isArray()){var values=new ArrayList<String>();node.forEach(v->values.add(canonical(v)));return "["+String.join(",",values)+"]";}return node.toString();
    }
    private String instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant().toString();if(value instanceof java.time.OffsetDateTime t)return t.toInstant().toString();return String.valueOf(value);}
    private String sanitize(String value){return value.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(500,value.length())).trim();}
    JsonNode read(String value){try{return mapper.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
}
