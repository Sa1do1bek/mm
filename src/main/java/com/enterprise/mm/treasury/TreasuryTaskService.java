package com.enterprise.mm.treasury;

import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TreasuryTaskService {
    public static final List<String> MAPPING_FIELDS=List.of("nativeOrganizationId","nativeSupplierId","nativeContractId","nativeSupplierBankAccountId","nativeOrganizationBankAccountId","nativeCurrencyId","nativeExpenseItemId","nativeInitiatorId","nativeOperationTypeId");
    private static final List<String> NATIVE_FIELDS=List.of("organizationId","counterpartyId","contractId","bankAccountId","organizationBankAccountId","currencyId","expenseArticleId","initiatorId","operationTypeId");
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final TreasuryNativeAccess access;
    public TreasuryTaskService(JdbcTemplate jdbc,ObjectMapper mapper,TreasuryNativeAccess access){this.jdbc=jdbc;this.mapper=mapper;this.access=access;}
    String tenant(){return TenantContext.currentTenant();} UUID company(){return CompanyContext.currentCompany();}
    void lock(){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text)||':'||cast(? as text)||':treasury',0))",Object.class,tenant(),company());}
    void protocol(JsonNode value){if(value==null||!value.path("protocolVersion").isIntegralNumber()||value.path("protocolVersion").asInt()!=1||value.toString().getBytes(StandardCharsets.UTF_8).length>2*1024*1024)throw new BadRequestException("Treasury protocolVersion 1 and bounded payload required");access.requireScope(value.path("sourceBaseId").asText());}
    public Map<String,Object> config(){return Map.of("nativeMode",access.nativeEnabled(),"manualMarkPaidAllowed",!access.nativeEnabled());}
    /** Invoke through the service proxy; collaborators must never inspect fields on a CGLIB proxy. */
    public void requireBusinessScope(String organization,String operation){access.requireBusinessScope(organization,operation);}
    public boolean hasRequest(UUID payment){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from treasury_native_request where tenant_id=? and company_id=? and mm_payment_id=?)",Boolean.class,tenant(),company(),payment));}
    public BigDecimal reservedAmount(UUID payment,BigDecimal requested){
        BigDecimal paid=jdbc.queryForObject("select coalesce(sum(case when s.kind='SETTLED' then s.amount else -s.amount end),0) from treasury_native_settlement s join treasury_native_request r on r.id=s.request_id where r.tenant_id=? and r.company_id=? and r.mm_payment_id=?",BigDecimal.class,tenant(),company(),payment);
        return requested.subtract(paid).max(BigDecimal.ZERO);
    }
    void requireLineage(UUID payment,JsonNode snapshot) {
        var rows=jdbc.queryForList("select payload,source_base_id from treasury_native_mapping where tenant_id=? and company_id=? and mm_payment_id=?",tenant(),company(),payment);
        if(rows.isEmpty()||!access.source().equals(rows.getFirst().get("source_base_id")))throw new ConflictException("Confirmed native supplier and contract mappings are required");
        JsonNode m=read(String.valueOf(rows.getFirst().get("payload")));access.requireBusinessScope(m.path("nativeOrganizationId").asText(),m.path("nativeOperationTypeId").asText());
        access.requireBusinessScope(snapshot.path("organizationId").asText(),snapshot.path("operationTypeId").asText());
        if(!m.path("nativeSupplierId").asText().equalsIgnoreCase(snapshot.path("counterpartyId").asText())
            ||!m.path("nativeContractId").asText().equalsIgnoreCase(snapshot.path("contractId").asText()))throw new ConflictException("Native request supplier or contract does not match the confirmed MM mapping");
        for(var fields:List.of(List.of("nativeSupplierBankAccountId","РасчетныйСчет"),List.of("nativeOrganizationBankAccountId","РасчетныйСчетОрганизации"))) {
            String actual=snapshot.path("nativeFields").path(fields.get(1)).path("nativeId").asText();
            if(!m.path(fields.getFirst()).asText().equalsIgnoreCase(actual))throw new ConflictException("Native request bank account does not match confirmed MM mapping");
        }
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mappings(UUID payment){return jdbc.queryForList("select mm_payment_id,payload,source_base_id,updated_at from treasury_native_mapping where tenant_id=? and company_id=?"+(payment==null?"":" and mm_payment_id=?"),payment==null?new Object[]{tenant(),company()}:new Object[]{tenant(),company(),payment}).stream().map(r->{var out=new LinkedHashMap<String,Object>();out.put("mmPaymentId",r.get("mm_payment_id"));out.put("mapping",read(String.valueOf(r.get("payload"))));out.put("sourceBaseId",r.get("source_base_id"));out.put("updatedAt",r.get("updated_at"));return (Map<String,Object>)out;}).toList();}
    Map<String,Object> payment(UUID id){var rows=jdbc.queryForList("select * from contract_payments where id=? and tenant_id=? and company_id=? and is_delete=false",id,tenant(),company());if(rows.isEmpty())throw new NotFoundException("MM payment not found");return rows.getFirst();}
    @Transactional
    public Map<String,Object> saveMapping(UUID payment,JsonNode value) {
        var mmPayment=payment(payment);access.requireScope(value.path("sourceBaseId").asText());
        access.requireBusinessScope(value.path("nativeOrganizationId").asText(),value.path("nativeOperationTypeId").asText());
        for(String f:MAPPING_FIELDS){String v=value.path(f).asText();if(v.isBlank()||v.length()>128)throw new BadRequestException("Required mapping: "+f);if(!f.equals("nativeOperationTypeId"))uuid(v,f);}
        if(value.path("purpose").asText().isBlank()||value.path("purpose").asText().length()>1000)throw new BadRequestException("Payment purpose is required (up to 1000 characters)");
        if(!"UZS".equals(mmPayment.get("currency_code"))&&!validRate(value.path("nativeExchangeRate")))throw new BadRequestException("Explicit positive nativeExchangeRate is required for non-UZS Treasury requests");
        if(value.hasNonNull("nativeExchangeRate")&&!value.path("nativeExchangeRate").asText().isBlank()&&!validRate(value.path("nativeExchangeRate")))throw new BadRequestException("nativeExchangeRate must be a positive decimal string");
        lock();if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from treasury_native_request where tenant_id=? and company_id=? and mm_payment_id=?)",Boolean.class,tenant(),company(),payment)))throw new ConflictException("Submitted request mapping is immutable; reconcile the existing native request");
        jdbc.update("insert into treasury_native_mapping(tenant_id,company_id,mm_payment_id,source_base_id,payload) values(?,?,?,?,?) on conflict(tenant_id,company_id,mm_payment_id) do update set source_base_id=excluded.source_base_id,payload=excluded.payload,updated_at=current_timestamp",tenant(),company(),payment,access.source(),json(value));return readiness(payment);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> readiness(UUID id) {
        var mmPayment=payment(id);var reasons=new ArrayList<String>();JsonNode mapping=mapper.createObjectNode();
        if(access.nativeEnabled()) {
            if(access.source().isBlank())reasons.add("Treasury source base is not configured");
            if(access.organization().isBlank())reasons.add("Treasury native organization is not configured");
            var rows=jdbc.queryForList("select payload from treasury_native_mapping where tenant_id=? and company_id=? and mm_payment_id=? and source_base_id=?",tenant(),company(),id,access.source());
            if(rows.isEmpty())reasons.add("Confirmed native Treasury mappings are required");else{mapping=read(String.valueOf(rows.getFirst().get("payload")));for(String f:MAPPING_FIELDS)if(mapping.path(f).asText().isBlank())reasons.add("Missing mapping: "+f);}
            if(!rows.isEmpty())try{access.requireBusinessScope(mapping.path("nativeOrganizationId").asText(),mapping.path("nativeOperationTypeId").asText());}catch(RuntimeException ex){reasons.add("Native Treasury mapping does not match the approved organization and supplier operation");}
            if(!"UZS".equals(mmPayment.get("currency_code"))&&!validRate(mapping.path("nativeExchangeRate")))reasons.add("Explicit native exchange rate is required for non-UZS requests");
        }
        return Map.of("ready",reasons.isEmpty(),"blockingReasons",reasons,"mapping",mapping,"nativeMode",access.nativeEnabled(),"manualMarkPaidAllowed",!access.nativeEnabled());
    }
    @Transactional
    public void enqueue(UUID payment,UUID event,JsonNode sourcePayload) {
        if(!access.nativeEnabled())return;lock();var ready=readiness(payment);if(!Boolean.TRUE.equals(ready.get("ready")))throw new ConflictException(String.join("; ",(List<String>)ready.get("blockingReasons")));
        access.requireScope(access.source());var p=payment(payment);JsonNode mapping=(JsonNode)ready.get("mapping");access.requireBusinessScope(mapping.path("nativeOrganizationId").asText(),mapping.path("nativeOperationTypeId").asText());
        var payload=sourcePayload.deepCopy();if(!payload.isObject())throw new BadRequestException("Payment payload must be object");var out=(com.fasterxml.jackson.databind.node.ObjectNode)payload;
        out.put("mmPaymentId",payment.toString());out.put("contractPaymentId",payment.toString());out.put("amount",new BigDecimal(String.valueOf(p.get("amount"))).toPlainString());out.put("currency",String.valueOf(p.get("currency_code")));out.put("purpose",mapping.path("purpose").asText());
        var nativeFields=out.putObject("native");for(int i=0;i<MAPPING_FIELDS.size();i++)nativeFields.put(NATIVE_FIELDS.get(i),mapping.path(MAPPING_FIELDS.get(i)).asText());
        if(validRate(mapping.path("nativeExchangeRate")))nativeFields.put("exchangeRate",mapping.path("nativeExchangeRate").asText());
        String serialized=json(out);UUID id=UUID.randomUUID();
        jdbc.update("insert into treasury_native_request(id,tenant_id,company_id,source_base_id,mm_payment_id,origin,amount,currency,purpose,native_organization_id,operation_type) values(?,?,?,?,?,'MM',?,?,?,?,?)",id,tenant(),company(),access.source(),payment,p.get("amount"),p.get("currency_code"),mapping.path("purpose").asText(),uuid(mapping.path("nativeOrganizationId").asText(),"nativeOrganizationId"),mapping.path("nativeOperationTypeId").asText());
        jdbc.update("insert into treasury_native_task(id,tenant_id,company_id,source_base_id,request_id,event_id,payload,payload_hash) values(?,?,?,?,?,?,?,?)",UUID.randomUUID(),tenant(),company(),access.source(),id,event,serialized,hash(serialized));
    }
    @Transactional
    public Map<String,Object> poll(JsonNode value) {
        protocol(value);String worker=value.path("workerId").asText();if(!worker.matches("[A-Za-z0-9_.:-]{1,128}")||!value.path("limit").isIntegralNumber()||value.path("limit").asInt()!=1)throw new BadRequestException("Stable workerId and limit=1 required");
        lock();var active=jdbc.queryForList("select * from treasury_native_task where tenant_id=? and company_id=? and source_base_id=? and state='LEASED' and lease_expires_at>current_timestamp for update",tenant(),company(),access.source());
        if(!active.isEmpty())return response(Objects.equals(worker,active.getFirst().get("worker_id"))?List.of(task(active.getFirst())):List.of());
        jdbc.update("update treasury_native_task set state='QUEUED',worker_id=null where tenant_id=? and company_id=? and source_base_id=? and state='LEASED'",tenant(),company(),access.source());
        var rows=jdbc.queryForList("select * from treasury_native_task where tenant_id=? and company_id=? and source_base_id=? and state='QUEUED' order by created_at,id limit 1 for update",tenant(),company(),access.source());if(rows.isEmpty())return response(List.of());
        UUID id=(UUID)rows.getFirst().get("id");jdbc.update("update treasury_native_task set state='LEASED',worker_id=?,lease_token=?,lease_expires_at=current_timestamp+interval '120 seconds',attempt_count=attempt_count+1,updated_at=current_timestamp where id=?",worker,UUID.randomUUID(),id);
        return response(List.of(task(jdbc.queryForMap("select * from treasury_native_task where id=?",id))));
    }
    Map<String,Object> response(List<Map<String,Object>> tasks){return Map.of("protocolVersion",1,"tasks",tasks);}
    Map<String,Object> task(Map<String,Object> row){return Map.of("taskId",row.get("id"),"leaseToken",row.get("lease_token"),"eventId",row.get("event_id"),"type","PAYMENT_REQUEST","payload",read(String.valueOf(row.get("payload"))),"payloadJson",row.get("payload"),"payloadHash",row.get("payload_hash"),"leaseExpiresAt",row.get("lease_expires_at"));}
    @Transactional
    public Map<String,Object> result(UUID id,JsonNode value) {
        protocol(value);lock();var rows=jdbc.queryForList("select * from treasury_native_task where id=? and tenant_id=? and company_id=? and source_base_id=? for update",id,tenant(),company(),access.source());if(rows.isEmpty())throw new NotFoundException("Treasury task not found");var row=rows.getFirst();
        if(!Objects.equals(String.valueOf(row.get("lease_token")),value.path("leaseToken").asText())||!Objects.equals(row.get("worker_id"),value.path("workerId").asText()))throw new ConflictException("Invalid or stale Treasury lease");
        if(!String.valueOf(row.get("event_id")).equals(value.path("eventId").asText())||!row.get("payload_hash").equals(value.path("payloadHash").asText()))throw new ConflictException("Task event or payload hash mismatch");
        String resultHash=hash(json(value));if(row.get("result_hash")!=null){if(resultHash.equals(row.get("result_hash"))){JsonNode recorded=read(String.valueOf(row.get("result")));synchronizeSourceEvent(row,recorded.path("status").asText(),recorded.path("message").asText(null));return accepted(id);}throw new ConflictException("Treasury result already recorded with changed payload");}
        if(!"LEASED".equals(row.get("state"))||((java.sql.Timestamp)row.get("lease_expires_at")).toInstant().isBefore(java.time.Instant.now()))throw new ConflictException("Treasury lease expired");
        String status=value.path("status").asText();if(!Set.of("CREATED","REJECTED").contains(status)||!value.path("payload").isObject())throw new BadRequestException("CREATED or REJECTED with payload required");
        if("CREATED".equals(status)){UUID nativeId=uuid(value.path("nativeRequestId").asText(),"nativeRequestId");String number=text(value,"nativeNumber",240);var request=jdbc.queryForMap("select * from treasury_native_request where id=? for update",row.get("request_id"));requireLineage((UUID)request.get("mm_payment_id"),value.path("payload"));
            if(positiveAmount(value.path("payload").path("amount")).compareTo((BigDecimal)request.get("amount"))!=0)throw new ConflictException("Created native request amount mismatch");
            if(!String.valueOf(request.get("currency")).equals(value.path("payload").path("currency").asText()))throw new ConflictException("Created native request currency mismatch");jdbc.update("update treasury_native_request set native_request_id=?,native_number=?,approval_status=?,bank_status=?,snapshot=?,last_error=null,updated_at=current_timestamp where id=?",nativeId,number,value.path("payload").path("approvalStatus").asText("DRAFT"),value.path("payload").path("bankStatus").asText("NOT_SENT"),json(value.path("payload")),row.get("request_id"));}
        else {String error=text(value,"message",2000);jdbc.update("update treasury_native_request set last_error=?,updated_at=current_timestamp where id=?",error,row.get("request_id"));}
        jdbc.update("update treasury_native_task set state=?,result=?,result_hash=?,last_error=?,updated_at=current_timestamp where id=?","CREATED".equals(status)?"ACKNOWLEDGED":"FAILED",json(value),resultHash,"REJECTED".equals(status)?value.path("message").asText(null):null,id);synchronizeSourceEvent(row,status,value.path("message").asText(null));return accepted(id);
    }
    /** Source integration state represents delivery only; this never changes a request or financial ledger. */
    private void synchronizeSourceEvent(Map<String,Object> task,String nativeStatus,String message) {
        String state=switch(nativeStatus){case "CREATED"->"DELIVERED";case "REJECTED"->"FAILED";case "QUEUED"->"NEW";default->throw new ConflictException("Unsupported recorded Treasury transport result");};
        boolean delivered="DELIVERED".equals(state);String error="FAILED".equals(state)?message:null;
        jdbc.update("""
            update integration_events e set status=?,external_id=case when ? then r.native_request_id::text else null end,
                last_error=?,next_retry_at=null,last_attempt_at=case when ?='NEW' then e.last_attempt_at else current_timestamp end,
                updated_at=current_timestamp,version=e.version+1
            from treasury_native_request r
            where r.id=? and r.tenant_id=? and r.company_id=? and r.source_base_id=?
              and e.id=? and e.tenant_id=r.tenant_id and e.company_id=r.company_id and e.aggregate_id=r.mm_payment_id
              and e.aggregate_type='TREASURY' and e.event_type='TREASURY.PAYMENT_REQUEST' and e.direction='OUTBOUND' and e.is_delete=false
              and (?='DELIVERED' or e.status is distinct from 'DELIVERED')
              and (e.status is distinct from ? or e.external_id is distinct from case when ? then r.native_request_id::text else null end
                   or e.last_error is distinct from ? or e.next_retry_at is not null)
            """,state,delivered,error,state,task.get("request_id"),tenant(),company(),access.source(),task.get("event_id"),state,state,delivered,error);
    }
    Map<String,Object> accepted(UUID id){return Map.of("protocolVersion",1,"accepted",true,"taskId",id);}
    @Transactional
    public Map<String,Object> retry(UUID id) {
        lock();var rows=jdbc.queryForList("select * from treasury_native_task where id=? and tenant_id=? and company_id=? and source_base_id=? for update",id,tenant(),company(),access.source());if(rows.isEmpty())throw new NotFoundException("Treasury task not found");var row=rows.getFirst();if(!"FAILED".equals(row.get("state")))throw new ConflictException("Only rejected Treasury tasks can be retried");
        jdbc.update("update treasury_native_task set state='QUEUED',worker_id=null,lease_token=null,lease_expires_at=null,result_hash=null,last_error=null,updated_at=current_timestamp where id=?",id);synchronizeSourceEvent(row,"QUEUED",null);return accepted(id);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> requests(UUID payment,boolean externalOnly) {
        var args=new ArrayList<Object>(List.of(tenant(),company()));String filter="";if(payment!=null){filter+=" and mm_payment_id=?";args.add(payment);}if(externalOnly)filter+=" and mm_payment_id is null";
        return jdbc.queryForList("select id from treasury_native_request where tenant_id=? and company_id=?"+filter+" order by updated_at desc limit 500",args.toArray()).stream().map(r->request((UUID)r.get("id"))).toList();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> request(UUID id) {
        var rows=jdbc.queryForList("select * from treasury_native_request where id=? and tenant_id=? and company_id=?",id,tenant(),company());if(rows.isEmpty())throw new NotFoundException("Treasury request not found");var r=rows.getFirst();var out=new LinkedHashMap<String,Object>();out.put("quarantined",r.get("quarantined"));
        out.put("id",id);out.put("sourceBaseId",r.get("source_base_id"));out.put("nativeRequestId",r.get("native_request_id"));out.put("nativeNumber",r.get("native_number"));out.put("mmPaymentId",r.get("mm_payment_id"));out.put("origin",r.get("origin"));out.put("amount",decimal(r.get("amount")));out.put("currency",r.get("currency"));out.put("purpose",r.get("purpose"));out.put("approvalStatus",r.get("approval_status"));out.put("bankStatus",r.get("bank_status"));out.put("updatedAt",r.get("updated_at"));out.put("lastError",r.get("last_error"));
        var task=jdbc.queryForList("select id,state,last_error from treasury_native_task where request_id=?",id);out.put("taskId",task.isEmpty()?null:task.getFirst().get("id"));out.put("deliveryStatus",task.isEmpty()?"IMPORTED":task.getFirst().get("state"));if(!task.isEmpty()&&task.getFirst().get("last_error")!=null)out.put("lastError",task.getFirst().get("last_error"));
        var totals=jdbc.queryForMap("select coalesce(sum(case when kind='SETTLED' then amount else 0 end),0) settled,coalesce(sum(case when kind='REVERSED' then amount else 0 end),0) reversed from treasury_native_settlement where request_id=?",id);BigDecimal settled=(BigDecimal)totals.get("settled"),reversed=(BigDecimal)totals.get("reversed"),net=settled.subtract(reversed);out.put("settledAmount",decimal(settled));out.put("reversedAmount",decimal(reversed));out.put("paidAmount",decimal(net));out.put("remainingAmount",decimal(((BigDecimal)r.get("amount")).subtract(net).max(BigDecimal.ZERO)));
        var reasons=new ArrayList<String>();if(r.get("mm_payment_id")==null)reasons.add("External request is not linked to MM payment");if(out.get("lastError")!=null)reasons.add(String.valueOf(out.get("lastError")));if(net.compareTo((BigDecimal)r.get("amount"))>0)reasons.add("Bank execution exceeds requested amount");if("REPORTED_UNVERIFIED".equals(r.get("bank_status"))&&net.compareTo((BigDecimal)r.get("amount"))<0)reasons.add("Native payment status is unverified; final bank execution evidence is required");out.put("blockingReasons",reasons);out.put("snapshot",read(String.valueOf(r.get("snapshot"))));
        out.put("settlements",jdbc.queryForList("select native_payment_id as \"nativePaymentId\",kind,original_payment_id as \"originalPaymentId\",amount::text,currency,payment_date as \"paymentDate\",source_kind as \"sourceKind\",bank_reference as \"bankReference\",created_at as \"createdAt\" from treasury_native_settlement where request_id=? order by created_at,id",id));
        out.put("history",jdbc.queryForList("select event_id as \"eventId\",event_type as \"eventType\",revision,created_at as \"createdAt\",payload from treasury_native_event where tenant_id=? and company_id=? and source_base_id=? and native_request_id=? order by created_at,id",tenant(),company(),r.get("source_base_id"),r.get("native_request_id")).stream().map(h->{h.put("payload",read(String.valueOf(h.get("payload"))));return h;}).toList());
        out.put("mmPaymentNumber",null);out.put("purchaseOrderId",null);out.put("supplierName",null);if(r.get("mm_payment_id")!=null){var p=payment((UUID)r.get("mm_payment_id"));out.put("mmPaymentNumber",p.get("code"));out.put("purchaseOrderId",p.get("purchase_order_id"));}return out;
    }
    @Transactional
    public Map<String,Object> link(UUID id,UUID payment) {
        lock();var r=request(id);var scope=jdbc.queryForMap("select native_organization_id,operation_type from treasury_native_request where id=?",id);access.requireBusinessScope(String.valueOf(scope.get("native_organization_id")),String.valueOf(scope.get("operation_type")));var p=payment(payment);if(r.get("mmPaymentId")!=null&&!payment.equals(r.get("mmPaymentId")))throw new ConflictException("Treasury request already linked");if(!String.valueOf(p.get("currency_code")).equals(r.get("currency"))||new BigDecimal(String.valueOf(p.get("amount"))).compareTo(new BigDecimal(String.valueOf(r.get("amount"))))!=0)throw new ConflictException("MM payment amount and currency must match native request");
        access.requireScope(String.valueOf(r.get("sourceBaseId")));
        if(Boolean.TRUE.equals(r.get("quarantined")))throw new ConflictException("Treasury request is quarantined for reconciliation");
        if(payment.equals(r.get("mmPaymentId")))return r;
        if(!Set.of("DRAFT","SENT_TO_TREASURY").contains(String.valueOf(p.get("status"))))throw new ConflictException("Only unpaid unlinked MM payments can be linked to native Treasury");
        requireLineage(payment,(JsonNode)r.get("snapshot"));
        jdbc.update("update treasury_native_request set mm_payment_id=?,updated_at=current_timestamp where id=?",payment,id);TreasurySettlementService.refresh(jdbc,id);return request(id);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> reconciliation(){var requests=requests(null,false);var issues=new ArrayList<Map<String,Object>>();for(var r:requests)for(var reason:(List<?>)r.get("blockingReasons")){var issue=new LinkedHashMap<String,Object>();issue.put("requestId",r.get("id"));issue.put("nativeNumber",r.get("nativeNumber"));issue.put("code","REVIEW_REQUIRED");issue.put("message",reason);issue.put("updatedAt",r.get("updatedAt"));issues.add(issue);}return Map.of("requests",requests,"issues",issues,"issueCount",issues.size());}
    static String decimal(Object n){return new BigDecimal(String.valueOf(n)).stripTrailingZeros().toPlainString();}
    static BigDecimal positiveAmount(JsonNode value){if(!value.isTextual()||!value.asText().matches("[0-9]{1,15}(\\.[0-9]{1,4})?")||new BigDecimal(value.asText()).signum()<=0)throw new BadRequestException("Amount must be a positive decimal string");return new BigDecimal(value.asText());}
    static boolean validRate(JsonNode value){return value.isTextual()&&value.asText().matches("[0-9]{1,9}(\\.[0-9]{1,6})?")&&new BigDecimal(value.asText()).signum()>0;}
    static UUID uuid(String v,String field){try{return UUID.fromString(v);}catch(Exception ex){throw new BadRequestException(field+" must be a UUID");}}
    static String text(JsonNode n,String field,int max){String v=n.path(field).asText();if(v.isBlank()||v.length()>max)throw new BadRequestException(field+" is required (max "+max+")");return v;}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    JsonNode read(String value){try{return mapper.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
}
