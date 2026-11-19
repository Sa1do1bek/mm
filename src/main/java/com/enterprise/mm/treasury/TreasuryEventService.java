package com.enterprise.mm.treasury;

import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TreasuryEventService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final TreasuryTaskService tasks;private final TreasurySettlementService settlements;
    public TreasuryEventService(JdbcTemplate jdbc,ObjectMapper mapper,TreasuryTaskService tasks,TreasurySettlementService settlements){this.jdbc=jdbc;this.mapper=mapper;this.tasks=tasks;this.settlements=settlements;}
    @Transactional
    public Map<String,Object> receive(JsonNode value) {
        tasks.protocol(value);tasks.lock();String event=TreasuryTaskService.text(value,"eventId",160),type=value.path("eventType").asText();
        if(!Set.of("REQUEST.UPSERT","PAYMENT.SETTLED","PAYMENT.REVERSED","MASTERDATA.UPSERT").contains(type)||!value.path("revision").isIntegralNumber()||value.path("revision").asLong()<1||!value.path("payload").isObject())throw new BadRequestException("Unsupported Treasury event or invalid revision/payload");
        String hash=TreasuryTaskService.hash(tasks.json(value)),source=value.path("sourceBaseId").asText();
        var duplicate=jdbc.queryForList("select payload_hash from treasury_native_event where tenant_id=? and company_id=? and source_base_id=? and event_id=?",tasks.tenant(),tasks.company(),source,event);
        if(!duplicate.isEmpty()){if(!hash.equals(duplicate.getFirst().get("payload_hash")))throw new ConflictException("Treasury event identity reused with changed payload");return Map.of("protocolVersion",1,"accepted",true,"eventId",event,"duplicate",true);}
        UUID nativeId="MASTERDATA.UPSERT".equals(type)?null:TreasuryTaskService.uuid(value.path("nativeRequestId").asText(),"nativeRequestId");JsonNode p=value.path("payload");long revision=value.path("revision").asLong();
        if(nativeId!=null) {
            var rows=jdbc.queryForList("select * from treasury_native_request where tenant_id=? and company_id=? and source_base_id=? and native_request_id=? for update",tasks.tenant(),tasks.company(),source,nativeId);
            if("REQUEST.UPSERT".equals(type)) {
                tasks.requireBusinessScope(p.path("organizationId").asText(),p.path("operationTypeId").asText());
                JsonNode raw=p.path("amount");if(!raw.isTextual()||!raw.asText().matches("[0-9]{1,15}(\\.[0-9]{1,4})?")||new BigDecimal(raw.asText()).signum()<=0)throw new BadRequestException("Request amount must be positive decimal string");BigDecimal amount=new BigDecimal(raw.asText());String currency=p.path("currency").asText();if(!currency.matches("[A-Z]{3}"))throw new BadRequestException("Request currency required");
                String number=TreasuryTaskService.text(p,"number",240);
                if(rows.isEmpty())jdbc.update("insert into treasury_native_request(id,tenant_id,company_id,source_base_id,native_request_id,native_number,origin,amount,currency,purpose,approval_status,bank_status,revision,snapshot,native_organization_id,operation_type,quarantined,last_error) values(?,?,?,?,?,?,'NATIVE',?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),tasks.tenant(),tasks.company(),source,nativeId,number,amount,currency,p.path("purpose").asText(null),p.path("approvalStatus").asText(null),p.path("bankStatus").asText(null),revision,tasks.json(p),TreasuryTaskService.uuid(p.path("organizationId").asText(),"organizationId"),p.path("operationTypeId").asText(),p.path("deleted").asBoolean(false),p.path("deleted").asBoolean(false)?"RECONCILIATION_REQUIRED: Native Treasury request is marked deleted":null);
                else {var r=rows.getFirst();if(revision>((Number)r.get("revision")).longValue()) {
                    String discrepancy=null;
                    if(((BigDecimal)r.get("amount")).compareTo(amount)!=0||!currency.equals(r.get("currency")))discrepancy="Native request economic terms changed";
                    if(r.get("mm_payment_id")!=null)try{tasks.requireLineage((UUID)r.get("mm_payment_id"),p);}catch(RuntimeException ex){discrepancy=ex.getMessage();}
                    if(p.path("deleted").asBoolean(false))discrepancy="Native Treasury request is marked deleted";
                    if(discrepancy!=null)jdbc.update("update treasury_native_request set quarantined=true,last_error=?,revision=?,updated_at=current_timestamp where id=?","RECONCILIATION_REQUIRED: "+discrepancy,revision,r.get("id"));
                    else jdbc.update("update treasury_native_request set native_number=?,purpose=?,approval_status=?,bank_status=?,revision=?,snapshot=?,updated_at=current_timestamp where id=?",number,p.path("purpose").asText(null),p.path("approvalStatus").asText(null),p.path("bankStatus").asText(null),revision,tasks.json(p),r.get("id"));
                } else if(revision==((Number)r.get("revision")).longValue()&&!tasks.read(String.valueOf(r.get("snapshot"))).equals(p))jdbc.update("update treasury_native_request set quarantined=true,last_error=?,updated_at=current_timestamp where id=?","RECONCILIATION_REQUIRED: Conflicting native request payload for the same revision",r.get("id"));}
            } else {
                if(rows.isEmpty())throw new ConflictException("Treasury request not found; deliver REQUEST.UPSERT before bank executions");var r=rows.getFirst();tasks.requireBusinessScope(String.valueOf(r.get("native_organization_id")),String.valueOf(r.get("operation_type")));if(Boolean.TRUE.equals(r.get("quarantined")))throw new ConflictException("Treasury request is quarantined for reconciliation; bank evidence cannot change MM totals");if(r.get("mm_payment_id")!=null)tasks.requireLineage((UUID)r.get("mm_payment_id"),tasks.read(String.valueOf(r.get("snapshot"))));settlements.apply(r,p,"PAYMENT.REVERSED".equals(type));
            }
        } else {TreasuryTaskService.text(p,"objectType",80);TreasuryTaskService.text(p,"nativeId",160);}
        jdbc.update("insert into treasury_native_event(id,tenant_id,company_id,source_base_id,event_id,event_type,native_request_id,revision,payload,payload_hash) values(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),tasks.tenant(),tasks.company(),source,event,type,nativeId,revision,tasks.json(p),hash);
        return Map.of("protocolVersion",1,"accepted",true,"eventId",event,"duplicate",false);
    }
}
