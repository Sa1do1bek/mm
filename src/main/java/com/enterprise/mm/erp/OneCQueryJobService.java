package com.enterprise.mm.erp;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** User jobs retain the complete native evidence and their derived report durably. */
@Service
public class OneCQueryJobService {
    private final JdbcTemplate jdbc;
    private final OneCPullExchangeService queue;
    private final OneCTransport transport;
    private final ErpPeriodReviewService periods;
    private final ErpReconciliationService balances;
    public OneCQueryJobService(JdbcTemplate jdbc,OneCPullExchangeService queue,OneCTransport transport,ErpPeriodReviewService periods,ErpReconciliationService balances){
        this.jdbc=jdbc;this.queue=queue;this.transport=transport;this.periods=periods;this.balances=balances;
    }
    private String tenant(){return TenantContext.currentTenant();}
    private UUID company(){return CompanyContext.currentCompany();}
    private String actor(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated()||"anonymousUser".equals(auth.getName()))throw new BadRequestException("Authentication required");return auth.getName();}
    @Transactional
    public Map<String,Object> create(String type,String periodType,LocalDate date) {
        if(!transport.pullEnabled())throw new ConflictException("Native pull transport is disabled");
        if(queue.usesCentralPostingProfile())throw new ConflictException("Use the 1C warehouse snapshots for centrally mapped companies");
        if(type==null||!Set.of("PERIOD","BALANCE").contains(type))throw new BadRequestException("Query type must be PERIOD or BALANCE");
        LocalDate today=LocalDate.now(ErpPeriodReviewService.ZONE);if(date==null)date=today;
        if(date.isAfter(today)||date.isBefore(LocalDate.of(2000,1,1)))throw new BadRequestException("Invalid query date");
        var payload=new LinkedHashMap<String,Object>();String actualPeriodType=null;
        if("PERIOD".equals(type)){var period=ErpPeriodReviewService.period(periodType,date,today);payload.put("from",period.from().toString());payload.put("date",period.to().toString());actualPeriodType=period.type();}
        else payload.put("date",date.toString());
        String by=actor(),json=queue.json(payload);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(cast(? as text)||':'||cast(? as text)||':one-c-pull',0))",Object.class,tenant(),company());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from erp_exchange_connection where tenant_id=? and company_id=? and enabled=true)",Boolean.class,tenant(),company())))throw new ConflictException("1C exchange is disabled for this company");
        var pending=jdbc.queryForList("select * from one_c_pull_task where tenant_id=? and company_id=? and requested_by=? and query_type=? and coalesce(period_type,'')=coalesce(?,'') and payload=? and state<>'COMPLETE' order by created_at limit 1",tenant(),company(),by,type,actualPeriodType,json);
        if(!pending.isEmpty())return describe(pending.getFirst(),false);
        Integer count=jdbc.queryForObject("select count(*) from one_c_pull_task where tenant_id=? and company_id=? and query_type is not null and state<>'COMPLETE'",Integer.class,tenant(),company());
        if(count!=null&&count>=20)throw new ConflictException("Too many pending 1C queries; wait for the native worker");
        UUID id=UUID.randomUUID();jdbc.update("""
            insert into one_c_pull_task(id,tenant_id,company_id,task_type,event_id,payload,query_type,period_type,requested_by)
            values(?,?,?,'BALANCES',?,?,?,?,?)
            """,id,tenant(),company(),id,json,type,actualPeriodType,by);
        return describe(jdbc.queryForMap("select * from one_c_pull_task where id=?",id),false);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(UUID id) {
        var rows=jdbc.queryForList("select * from one_c_pull_task where id=? and tenant_id=? and company_id=? and requested_by=? and query_type is not null for update",id,tenant(),company(),actor());
        if(rows.isEmpty())throw new NotFoundException("1C query job not found");
        return describe(rows.getFirst(),true);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(){
        return jdbc.queryForList("select * from one_c_pull_task where tenant_id=? and company_id=? and requested_by=? and query_type is not null order by created_at desc limit 30",tenant(),company(),actor()).stream().map(r->describe(r,false)).toList();
    }
    private Map<String,Object> describe(Map<String,Object> row,boolean materialize) {
        JsonNode payload=queue.read(String.valueOf(row.get("payload")));var response=new LinkedHashMap<String,Object>();
        response.put("id",row.get("id"));response.put("status",row.get("state"));response.put("type",row.get("query_type"));response.put("date",payload.path("date").asText());
        response.put("periodType",row.get("period_type"));response.put("createdAt",instant(row.get("created_at")));response.put("completedAt",instant(row.get("completed_at")));response.put("error",row.get("last_error"));
        if("COMPLETE".equals(row.get("state"))) {
            Object report;
            if(row.get("report_result")!=null) {
                report=queue.read(String.valueOf(row.get("report_result")));
                // Period snapshots already have existing warehouse access enforcement on read.
                if(materialize&&"PERIOD".equals(row.get("query_type")))report=periods.get(UUID.fromString(((JsonNode)report).path("id").asText()));
                else if(materialize)balances.assertReportAccess((JsonNode)report);
            } else if(materialize) {
                JsonNode evidence=queue.read(String.valueOf(row.get("result")));LocalDate date=LocalDate.parse(payload.path("date").asText());
                report="PERIOD".equals(row.get("query_type"))?periods.generateFromEvidence(String.valueOf(row.get("period_type")),date,evidence,(UUID)row.get("id")):
                    balances.reconcileFromEvidence(tenant(),company(),date,evidence);
                jdbc.update("update one_c_pull_task set report_result=? where id=? and tenant_id=? and company_id=?",queue.json(report),row.get("id"),tenant(),company());
            } else report=null;
            if(report!=null&&materialize)response.put("result",report);
        }
        return response;
    }
    private String instant(Object value){if(value==null)return null;if(value instanceof java.sql.Timestamp t)return t.toInstant().toString();if(value instanceof OffsetDateTime t)return t.toInstant().toString();return String.valueOf(value);}
}
