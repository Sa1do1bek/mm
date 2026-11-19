package com.enterprise.mm.erp;

import static com.enterprise.mm.erp.ErpPeriodEvidence.*;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.service.InventoryStockAsOfQuery;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ErpPeriodReviewService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final InventoryStockAsOfQuery historical;
    private final RoleDataScopeService access;
    private final OneCApiAccess api;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @org.springframework.beans.factory.annotation.Autowired
    private OneCTransport transport;
    static final ZoneId ZONE=ZoneId.of("Asia/Tashkent");
    record Period(String type,LocalDate from,LocalDate to,boolean partial) {}

    public ErpPeriodReviewService(JdbcTemplate jdbc,NamedParameterJdbcTemplate named,InventoryStockAsOfQuery historical,
                                 RoleDataScopeService access,OneCApiAccess api,ObjectMapper mapper) {
        this.jdbc=jdbc;this.named=named;this.historical=historical;this.access=access;this.api=api;
        this.mapper=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    static Period period(String type,LocalDate date,LocalDate today) {
        if(type==null||date==null||date.isBefore(LocalDate.of(2000,1,1))||date.isAfter(today)||!Set.of("DAY","MONTH").contains(type))
            throw new BadRequestException("Выберите прошедший или текущий день/месяц", "ERP_PERIOD_INVALID");
        LocalDate from="MONTH".equals(type)?date.withDayOfMonth(1):date;
        LocalDate last="MONTH".equals(type)?from.plusMonths(1).minusDays(1):date;
        return new Period(type,from,last.isAfter(today)?today:last,last.isAfter(today));
    }
    private MapSqlParameterSource params() {return new MapSqlParameterSource("tenant",TenantContext.currentTenant()).addValue("company",CompanyContext.currentCompany());}
    private static String actor(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!a.isAuthenticated()||"anonymousUser".equals(a.getName()))throw new BadRequestException("Требуется авторизованный пользователь");return a.getName();}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> generate(String type,LocalDate date) {
        Period period=period(type,date,LocalDate.now(ZONE));
        return generateReport(period,calculate(period),null,null);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> generateFromEvidence(String type,LocalDate date,com.fasterxml.jackson.databind.JsonNode evidence,UUID taskId) {
        Period requested=period(type,date,LocalDate.now(ZONE));
        LocalDate from=LocalDate.parse(evidence.path("from").asText()),to=LocalDate.parse(evidence.path("to").asText());
        if(!from.equals(requested.from)||!to.equals(date))throw new BadRequestException("Native period does not match the query");
        Period period=new Period(type,from,to,"MONTH".equals(type)&&to.isBefore(from.plusMonths(1).minusDays(1)));
        return generateReport(period,calculate(period,evidence),evidence,taskId);
    }
    private Map<String,Object> generateReport(Period period,Map<String,Object> report,com.fasterxml.jackson.databind.JsonNode evidence,UUID taskId) {
        String by=actor();
        UUID id=UUID.randomUUID();String fingerprint=fingerprint(report);
        var p=params().addValue("id",id).addValue("type",period.type).addValue("from",period.from).addValue("to",period.to)
            .addValue("actor",by).addValue("hash",fingerprint).addValue("report",json(report));
        named.update("""
            insert into erp_period_review_run(id,tenant_id,company_id,period_type,period_start,period_end,created_by,fingerprint,report)
            values(:id,:tenant,:company,:type,:from,:to,:actor,:hash,cast(:report as jsonb))
            """,p);
        if(evidence!=null)jdbc.update("update erp_period_review_run set native_evidence=?,native_task_id=? where id=?",json(evidence),taskId,id);
        return get(id);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> history() {
        List<Map<String,Object>> rows=named.queryForList("""
            select id,period_type,period_start,period_end,captured_at,created_by,fingerprint,review_version,review_state,
                   review_comment,reviewed_by,reviewed_at,jsonb_build_object('scope',report->'scope','summary',report->'summary')::text as report
            from erp_period_review_run where tenant_id=:tenant and company_id=:company
            order by captured_at desc,id desc limit 30
            """,params());
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            Map<String,Object> report=read(String.valueOf(row.get("report")));
            if(allowed(report)){var item=describe(row);item.put("summary",report.get("summary"));result.add(item);}
        }
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(UUID id) {
        var row=row(id,false);var report=read(String.valueOf(row.get("report")));assertAccess(report);
        var result=describe(row);result.put("report",report);
        List<Map<String,Object>> audit=named.queryForList("""
            select review_version,decision,comment,actor,created_at from erp_period_review_audit
            where tenant_id=:tenant and company_id=:company and run_id=:id order by review_version
            """,params().addValue("id",id));
        result.put("audit",audit.stream().map(a->Map.of("version",a.get("review_version"),"decision",a.get("decision"),"comment",a.get("comment"),"actor",a.get("actor"),"at",instant(a.get("created_at")))).toList());
        return result;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> review(UUID id,String decision,String comment,long expectedVersion,String expectedFingerprint) {
        return review(id,decision,comment,expectedVersion,expectedFingerprint,null);
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> review(UUID id,String decision,String comment,long expectedVersion,String expectedFingerprint,UUID nativeEvidenceJobId) {
        String by=actor();
        if(decision==null||!Set.of("REVIEWED","NEEDS_ATTENTION").contains(decision)||comment==null||comment.isBlank()||comment.trim().length()>2000)
            throw new BadRequestException("Выберите результат проверки и введите комментарий до 2000 символов", "ERP_REVIEW_INVALID");
        var row=row(id,true);var report=read(String.valueOf(row.get("report")));assertAccess(report);
        if(((Number)row.get("review_version")).longValue()!=expectedVersion || !Objects.equals(row.get("fingerprint"),expectedFingerprint))
            throw new ConflictException("Результат уже изменён другим проверяющим. Обновите сводку");
        Boolean newer=named.queryForObject("""
            select exists(select 1 from erp_period_review_run where tenant_id=:tenant and company_id=:company
                and period_type=:type and period_start=:from and captured_at>:captured and (:pull=false or fingerprint<>:hash))
            """,params().addValue("type",row.get("period_type")).addValue("from",row.get("period_start")).addValue("captured",row.get("captured_at")).addValue("pull",transport!=null&&transport.pullEnabled()).addValue("hash",row.get("fingerprint")),Boolean.class);
        if(Boolean.TRUE.equals(newer))throw new ConflictException("Есть более новая сводка за этот период. Проверьте её");
        // Approval is attached to exact source evidence, not just a date. Recheck both ledgers and delivery state.
        Period period=new Period(String.valueOf(row.get("period_type")),LocalDate.parse(String.valueOf(row.get("period_start"))),LocalDate.parse(String.valueOf(row.get("period_end"))),Boolean.TRUE.equals(report.get("partialPeriod")));
        Map<String,Object> refreshed;
        if(transport!=null&&transport.pullEnabled()) {
            if(row.get("native_evidence")==null)throw new ConflictException("Create a new period query through the 1C queue");
            if(nativeEvidenceJobId==null)throw new ConflictException("Refresh native 1C evidence before recording the review");
            var evidenceRows=jdbc.queryForList("""
                select result from one_c_pull_task where id=? and tenant_id=? and company_id=? and requested_by=?
                    and query_type='PERIOD' and period_type=? and state='COMPLETE'
                    and payload::jsonb->>'date'=? and payload::jsonb->>'from'=?
                    and created_at>? and completed_at>current_timestamp-interval '2 minutes'
                """,nativeEvidenceJobId,TenantContext.currentTenant(),CompanyContext.currentCompany(),by,period.type,
                    period.to.toString(),period.from.toString(),row.get("captured_at"));
            if(evidenceRows.size()!=1)throw new ConflictException("Native evidence refresh is missing, stale or outside this review scope");
            String evidence=String.valueOf(evidenceRows.getFirst().get("result"));
            try { refreshed=calculate(period,mapper.readTree(evidence)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
        } else refreshed=calculate(period);
        if(!fingerprint(refreshed).equals(row.get("fingerprint")))throw new ConflictException("Данные MM или 1С изменились после расчёта. Сформируйте новую сводку");
        var p=params().addValue("id",id).addValue("state",decision).addValue("comment",comment.trim()).addValue("actor",by).addValue("version",expectedVersion+1);
        named.update("""
            update erp_period_review_run set review_state=:state,review_comment=:comment,reviewed_by=:actor,
                reviewed_at=current_timestamp,review_version=:version where id=:id and tenant_id=:tenant and company_id=:company
            """,p);
        named.update("""
            insert into erp_period_review_audit(id,run_id,tenant_id,company_id,review_version,decision,comment,actor)
            values(:audit,:id,:tenant,:company,:version,:state,:comment,:actor)
            """,p.addValue("audit",UUID.randomUUID()));
        return get(id);
    }
    Map<String,Object> calculate(Period period) { return calculate(period,null); }
    Map<String,Object> calculate(Period period,com.fasterxml.jackson.databind.JsonNode evidence) {
        String tenant=TenantContext.currentTenant();UUID company=CompanyContext.currentCompany();
        Snapshot remote=evidence==null?load(tenant,company,period):parse(evidence,tenant,company,period.from,period.to);
        var p=params().addValue("warehouses",remote.warehouses()).addValue("materials",remote.materials()).addValue("from",period.from).addValue("to",period.to);
        var warehouseRows=named.queryForList("select id,name from warehouses where tenant_id=:tenant and company_id=:company and is_delete=false and id in (:warehouses) order by id",p);
        var materialRows=named.queryForList("""
            select m.id,m.name,coalesce(u.measurement_name_short_ru,u.measurement_name_short,u.code,'') as unit
            from materials m left join unit_of_measures u on u.id=m.unit_of_measure_id and u.tenant_id=m.tenant_id and u.company_id=m.company_id
            where m.tenant_id=:tenant and m.company_id=:company and m.is_delete=false and m.id in (:materials) order by m.id
            """,p);
        if(warehouseRows.size()!=remote.warehouses().size()||materialRows.size()!=remote.materials().size())throw invalid("Состав сопоставлений 1С не принадлежит компании MM");
        for(UUID warehouse:remote.warehouses())access.assertWarehouseAccess(warehouse);
        var opening=mmBalances(tenant,company,remote,period.from.minusDays(1));var closing=mmBalances(tenant,company,remote,period.to);
        var flowRows=named.queryForList("""
            select coalesce(m.movement_date,cast(m.posted_at as date)) as day,a.material_id,b.warehouse_id,
                sum(case when a.on_hand_delta>0 then a.on_hand_delta else 0 end) as in_quantity,
                sum(case when a.total_value_delta>0 then a.total_value_delta else 0 end) as in_amount,
                sum(case when a.on_hand_delta<0 then -a.on_hand_delta else 0 end) as out_quantity,
                sum(case when a.total_value_delta<0 then -a.total_value_delta else 0 end) as out_amount
            from stock_movement_allocations a join stock_movements m on m.id=a.movement_id and m.tenant_id=a.tenant_id and m.company_id=a.company_id and m.material_id=a.material_id
            join stock_balances b on b.id=a.balance_id and b.tenant_id=a.tenant_id and b.company_id=a.company_id and b.material_id=a.material_id
            where a.tenant_id=:tenant and a.company_id=:company and m.posted_at is not null and b.is_delete=false
                and a.material_id in (:materials) and b.warehouse_id in (:warehouses)
                and coalesce(m.movement_date,cast(m.posted_at as date)) between :from and :to
            group by coalesce(m.movement_date,cast(m.posted_at as date)),a.material_id,b.warehouse_id
            """,p);
        NavigableMap<LocalDate,Map<Key,Flow>> mmFlows=new TreeMap<>();
        for(var row:flowRows)mmFlows.computeIfAbsent(LocalDate.parse(String.valueOf(row.get("day"))),x->new HashMap<>()).put(new Key((UUID)row.get("material_id"),(UUID)row.get("warehouse_id")),new Flow(new Amount(bd(row,"in_quantity"),bd(row,"in_amount")),new Amount(bd(row,"out_quantity"),bd(row,"out_amount"))));
        Map<UUID,String> materialNames=new HashMap<>(),warehouseNames=new HashMap<>(),units=new HashMap<>();
        for(var r:materialRows){materialNames.put((UUID)r.get("id"),String.valueOf(r.get("name")));units.put((UUID)r.get("id"),String.valueOf(r.get("unit")));}
        for(var r:warehouseRows)warehouseNames.put((UUID)r.get("id"),String.valueOf(r.get("name")));
        List<Key> keys=new ArrayList<>();for(UUID m:remote.materials())for(UUID w:remote.warehouses())keys.add(new Key(m,w));
        Map<Key,Amount> mmRunning=new HashMap<>(opening),oneCRunning=new HashMap<>(remote.opening());
        Map<Key,Flow> mmTotal=new HashMap<>(),oneCTotal=new HashMap<>();Set<Key> changed=new HashSet<>();
        List<Map<String,Object>> days=new ArrayList<>();int dailyDifferences=0;
        for(var day:remote.days().entrySet()) {
            List<Map<String,Object>> rows=new ArrayList<>();int count=0;
            for(Key key:keys) {
                Flow mm=mmFlows.getOrDefault(day.getKey(),Map.of()).getOrDefault(key,Flow.zero()),oneC=day.getValue().get(key);
                var row=position(key,materialNames,warehouseNames,units,mmRunning.getOrDefault(key,Amount.zero()),oneCRunning.get(key),mm,oneC);
                if(Boolean.TRUE.equals(row.get("hasDifference"))){count++;changed.add(key);}
                rows.add(row);mmRunning.put(key,mmRunning.getOrDefault(key,Amount.zero()).plus(mm.net()));oneCRunning.put(key,oneCRunning.get(key).plus(oneC.net()));
                mmTotal.merge(key,mm,Flow::plus);oneCTotal.merge(key,oneC,Flow::plus);
            }
            dailyDifferences+=count;
            var item=new LinkedHashMap<String,Object>();item.put("date",day.getKey().toString());item.put("differenceCount",count);item.put("rows",rows);item.put("totals",totals(rows));days.add(item);
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Key key:keys){
            if(!mmRunning.getOrDefault(key,Amount.zero()).same(closing.getOrDefault(key,Amount.zero())))throw invalid("История оборотов MM не сходится с конечными остатками");
            var row=position(key,materialNames,warehouseNames,units,opening.getOrDefault(key,Amount.zero()),remote.opening().get(key),mmTotal.get(key),oneCTotal.get(key));
            row.put("hasDifference",changed.contains(key));rows.add(row);
        }
        var documents=documents(p,remote,period);long docDifferences=documents.stream().filter(d->!"MATCHED".equals(d.get("state"))).count();
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("positionCount",keys.size());summary.put("dayCount",days.size());summary.put("differenceCount",changed.size());summary.put("dailyDifferenceCount",dailyDifferences);summary.put("documentDifferenceCount",docDifferences);summary.put("nativeDocumentCount",remote.documents().size());summary.put("comparedDocumentCount",documents.size());summary.put("hasDifferences",!changed.isEmpty()||docDifferences>0);summary.put("totals",totals(rows));
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("periodType",period.type);report.put("from",period.from.toString());report.put("to",period.to.toString());report.put("partialPeriod",period.partial);report.put("timeZone",ZONE.toString());report.put("basis","POSTED_ACCOUNTING_MOVEMENTS");report.put("stockChanged",false);
        report.put("scope",Map.of("materialIds",remote.materials(),"warehouseIds",remote.warehouses()));report.put("summary",summary);report.put("rows",rows);report.put("days",days);report.put("documents",documents);return report;
    }
    private Snapshot load(String tenant,UUID company,Period period) {
        if(transport!=null&&transport.pullEnabled())throw new ConflictException("Create an asynchronous native 1C period query");
        List<String> bases=jdbc.queryForList("select base_url from erp_exchange_connection where tenant_id=? and company_id=? and enabled=true",String.class,tenant,company);
        if(bases.size()!=1||bases.getFirst()==null||bases.getFirst().isBlank())throw new BadRequestException("Подключение 1С не настроено", "ERP_CONNECTION_UNAVAILABLE");
        String base=bases.getFirst();
        try {
            var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/+$","")+"/mm/v1/balances?from="+period.from+"&date="+period.to)).timeout(Duration.ofSeconds(60)).GET();api.authorize(request,base,tenant,company);
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw invalid("Сервис 1С вернул HTTP "+response.statusCode()+". Проверьте обновление MMAPI и журнал публикации");
            return parse(mapper.readTree(response.body()),tenant,company,period.from,period.to);
        }catch(BadRequestException ex){throw ex;}catch(InterruptedException ex){Thread.currentThread().interrupt();throw invalid("Запрос к 1С прерван");}catch(Exception ex){throw invalid("Не удалось получить полную сводку из 1С");}
    }
    private Map<Key,Amount> mmBalances(String tenant,UUID company,Snapshot remote,LocalDate date) {
        var evidence=historical.historical(tenant,company,remote.warehouses(),date);Map<Key,Amount> result=new HashMap<>();
        var p=params().addValue("warehouses",remote.warehouses()).addValue("materials",remote.materials());
        for(var row:named.queryForList("select id,material_id,warehouse_id from stock_balances where tenant_id=:tenant and company_id=:company and is_delete=false and material_id in (:materials) and warehouse_id in (:warehouses)",p)) {
            var value=evidence.get((UUID)row.get("id"));if(value==null)throw invalid("Исторические остатки MM неполны");result.merge(new Key((UUID)row.get("material_id"),(UUID)row.get("warehouse_id")),new Amount(value.quantity(),value.value()),Amount::plus);
        }return result;
    }
    private List<Map<String,Object>> documents(MapSqlParameterSource p,Snapshot remote,Period period) {
        p.addValue("materialTexts",remote.materials().stream().map(UUID::toString).toList()).addValue("warehouseTexts",remote.warehouses().stream().map(UUID::toString).toList());
        var local=named.queryForList("""
            select e.id,e.document_id,e.document_kind,e.posting_date,e.remote_document_ref,e.remote_posted,e.delivery_state,e.rejection_reason as last_error
            from erp_exchange_envelope e where e.tenant_id=:tenant and e.company_id=:company and e.operation is not null
                and e.posting_date between :from and :to and exists(select 1 from jsonb_array_elements(cast(e.payload as jsonb)->'lines') l
                    where l->>'materialId' in (:materialTexts) and l->>'warehouseId' in (:warehouseTexts)) order by e.id
            """,p);
        var imported=named.queryForList("""
            select distinct i.event_id as id,i.document_id,i.document_kind,coalesce(m.movement_date,cast(m.posted_at as date)) as posting_date,
                i.document_id as remote_document_ref,true as remote_posted,'POSTED' as delivery_state,'' as last_error
            from one_c_api_inbox i join stock_movements m on m.reference_id=i.document_id and m.reference_type='ONE_C_API'
                and m.tenant_id=i.tenant_id and m.company_id=i.company_id
            join stock_movement_allocations a on a.movement_id=m.id and a.tenant_id=m.tenant_id and a.company_id=m.company_id
            join stock_balances b on b.id=a.balance_id and b.tenant_id=a.tenant_id and b.company_id=a.company_id
            where i.tenant_id=:tenant and i.company_id=:company and m.posted_at is not null and a.material_id in (:materials)
                and b.warehouse_id in (:warehouses) and coalesce(m.movement_date,cast(m.posted_at as date)) between :from and :to order by id
            """,p);
        Map<UUID,NativeDocument> natives=new HashMap<>();for(var d:remote.documents())natives.put(d.id(),d);Set<UUID> linked=new HashSet<>();List<Map<String,Object>> result=new ArrayList<>();
        for(int direction=0;direction<2;direction++)for(var row:direction==0?local:imported) {
            UUID ref=(UUID)row.get("remote_document_ref");NativeDocument nativeDoc=natives.get(ref);if(nativeDoc!=null)linked.add(ref);
            String date=String.valueOf(row.get("posting_date"));String delivery=String.valueOf(row.get("delivery_state"));
            String state=!"POSTED".equals(delivery)||!Boolean.TRUE.equals(row.get("remote_posted"))?"PENDING":nativeDoc==null?"MISSING_IN_ONE_C":!nativeDoc.date().toString().equals(date)?"DATE_MISMATCH":"MATCHED";
            var item=new LinkedHashMap<String,Object>();item.put("id",String.valueOf(row.get("id")));item.put("mmDocumentId",String.valueOf(row.get("document_id")));item.put("nativeDocumentId",ref==null?null:ref.toString());item.put("date",date);item.put("kind",row.get("document_kind"));item.put("direction",direction==0?"MM_TO_ONE_C":"ONE_C_TO_MM");item.put("label",nativeDoc==null?String.valueOf(row.get("document_id")):nativeDoc.label());item.put("state",state);item.put("deliveryState",delivery);item.put("error",row.get("last_error"));result.add(item);
        }
        for(var d:remote.documents())if(!linked.contains(d.id()))result.add(new LinkedHashMap<>(Map.of("id",d.id().toString(),"nativeDocumentId",d.id().toString(),"date",d.date().toString(),"kind",d.kind(),"direction","ONE_C_ONLY","label",d.label(),"state","MISSING_IN_MM","deliveryState","UNKNOWN","error","Документ 1С не связан с обменом MM в этом периоде")));
        // Also show MM ledger operations never captured by the exchange, rather than relying on the outbox alone.
        var uncaptured=named.queryForList("""
            select distinct m.id,coalesce(m.reference_id,m.document_id,m.id) as reference_id,m.code,m.movement_type,coalesce(m.movement_date,cast(m.posted_at as date)) as day
            from stock_movements m join stock_movement_allocations a on a.movement_id=m.id and a.tenant_id=m.tenant_id and a.company_id=m.company_id
            join stock_balances b on b.id=a.balance_id and b.tenant_id=a.tenant_id and b.company_id=a.company_id
            where m.tenant_id=:tenant and m.company_id=:company and m.posted_at is not null
                and coalesce(m.reference_type,'')<>'ONE_C_API' and a.material_id in (:materials) and b.warehouse_id in (:warehouses)
                and coalesce(m.movement_date,cast(m.posted_at as date)) between :from and :to
                and not exists(select 1 from erp_exchange_envelope e where e.tenant_id=m.tenant_id and e.company_id=m.company_id
                    and (e.document_id=m.id or e.document_id=m.document_id or e.document_id=m.reference_id) and e.operation is not null and e.posting_date=coalesce(m.movement_date,cast(m.posted_at as date)))
            order by m.id
            """,p);
        for(var row:uncaptured)result.add(new LinkedHashMap<>(Map.of("id",String.valueOf(row.get("id")),"mmDocumentId",String.valueOf(row.get("reference_id")),"date",String.valueOf(row.get("day")),"kind",String.valueOf(row.get("movement_type")),"direction","MM_ONLY","label",String.valueOf(row.get("code")),"state","UNLINKED_MM","deliveryState","UNKNOWN","error","Проведённое движение MM не связано с событием обмена")));
        result.sort(Comparator.comparing((Map<String,Object> x)->String.valueOf(x.get("date"))).thenComparing(x->String.valueOf(x.get("id"))));return result;
    }
    static Map<String,Object> comparison(Amount mm,Amount nativeValue) {
        return Map.of("mmQuantity",decimal(mm.quantity()),"oneCQuantity",decimal(nativeValue.quantity()),"quantityDelta",decimal(mm.quantity().subtract(nativeValue.quantity())),"mmAmount",decimal(mm.value()),"oneCAmount",decimal(nativeValue.value()),"amountDelta",decimal(mm.value().subtract(nativeValue.value())));
    }
    private Map<String,Object> position(Key key,Map<UUID,String> materials,Map<UUID,String> warehouses,Map<UUID,String> units,Amount mmOpen,Amount nativeOpen,Flow mm,Flow nativeFlow) {
        Amount mmClose=mmOpen.plus(mm.net()),nativeClose=nativeOpen.plus(nativeFlow.net());var row=new LinkedHashMap<String,Object>();row.put("materialId",key.material().toString());row.put("warehouseId",key.warehouse().toString());row.put("materialName",materials.get(key.material()));row.put("warehouseName",warehouses.get(key.warehouse()));row.put("unit",units.get(key.material()));row.put("opening",comparison(mmOpen,nativeOpen));row.put("inflow",comparison(mm.in(),nativeFlow.in()));row.put("outflow",comparison(mm.out(),nativeFlow.out()));row.put("closing",comparison(mmClose,nativeClose));row.put("hasDifference",!mmOpen.same(nativeOpen)||!mm.in().same(nativeFlow.in())||!mm.out().same(nativeFlow.out())||!mmClose.same(nativeClose));return row;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> totals(List<Map<String,Object>> rows) {
        Map<String,Object> result=new LinkedHashMap<>();for(String part:List.of("opening","inflow","outflow","closing")) {
            BigDecimal mm=BigDecimal.ZERO,oneC=BigDecimal.ZERO;
            for(var row:rows){var value=(Map<String,Object>)row.get(part);mm=mm.add(new BigDecimal(String.valueOf(value.get("mmAmount"))));oneC=oneC.add(new BigDecimal(String.valueOf(value.get("oneCAmount"))));}
            result.put(part,Map.of("mmAmount",decimal(mm),"oneCAmount",decimal(oneC),"amountDelta",decimal(mm.subtract(oneC))));
        }return result; // Quantities in different units are deliberately never added together.
    }
    private Map<String,Object> row(UUID id,boolean lock) {
        var rows=named.queryForList("select *,report::text as report from erp_period_review_run where id=:id and tenant_id=:tenant and company_id=:company"+(lock?" for update":""),params().addValue("id",id));if(rows.size()!=1)throw new NotFoundException("Сводка не найдена");return rows.getFirst();
    }
    private Map<String,Object> describe(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();for(var entry:Map.of("id","id","periodType","period_type","from","period_start","to","period_end","createdBy","created_by","fingerprint","fingerprint","reviewVersion","review_version","reviewState","review_state","reviewComment","review_comment","reviewedBy","reviewed_by").entrySet())result.put(entry.getKey(),row.get(entry.getValue()));
        result.put("capturedAt",instant(row.get("captured_at")));result.put("reviewedAt",instant(row.get("reviewed_at")));return result;
    }
    private boolean allowed(Map<String,Object> report){for(var id:mapper.valueToTree(report).path("scope").path("warehouseIds"))if(!access.canAccessWarehouse(UUID.fromString(id.asText())))return false;return true;}
    private void assertAccess(Map<String,Object> report){if(!allowed(report))throw new NotFoundException("Сводка вне доступной области складов");}
    static String fingerprint(ObjectMapper mapper,Map<String,Object> report) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(report)));}catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private String fingerprint(Map<String,Object> report){return fingerprint(mapper,report);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Map<String,Object> read(String value){try{return mapper.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static BigDecimal bd(Map<String,Object> row,String field){return (BigDecimal)row.get(field);}
    private static String instant(Object value){if(value==null)return "";if(value instanceof Timestamp t)return t.toInstant().toString();if(value instanceof OffsetDateTime t)return t.toInstant().toString();return String.valueOf(value);}
}
