package com.enterprise.mm.erp;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.service.InventoryStockAsOfQuery;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ErpReconciliationService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final InventoryStockAsOfQuery historical;
    private final ObjectMapper mapper;
    private final RoleDataScopeService access;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @org.springframework.beans.factory.annotation.Autowired
    private OneCApiAccess nativeApi;
    @org.springframework.beans.factory.annotation.Autowired
    private OneCTransport transport;
    public ErpReconciliationService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, InventoryStockAsOfQuery historical, ObjectMapper mapper, RoleDataScopeService access) {
        this.jdbc = jdbc; this.named = named; this.historical = historical; this.mapper = mapper; this.access = access;
    }
    void assertReportAccess(JsonNode report){for(JsonNode warehouse:report.path("scope").path("warehouseIds"))access.assertWarehouseAccess(UUID.fromString(warehouse.asText()));}
    record Key(UUID material, UUID warehouse) {}
    record Balance(BigDecimal quantity, BigDecimal amount) {
        Balance plus(Balance other) { return new Balance(quantity.add(other.quantity), amount.add(other.amount)); }
        static Balance zero() { return new Balance(BigDecimal.ZERO, BigDecimal.ZERO); }
    }
    record Snapshot(List<UUID> materials, List<UUID> warehouses, Map<Key,Balance> balances) {}

    public Map<String,Object> reconcile(String tenant, UUID company, LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now())) throw new BadRequestException("Reconciliation date must not be in the future", "ERP_DATE_INVALID");
        List<String> urls=jdbc.queryForList("select base_url from erp_exchange_connection where tenant_id=? and company_id=? and enabled=true", String.class, tenant, company);
        if (urls.size()!=1 || urls.get(0)==null || urls.get(0).isBlank()) throw new BadRequestException("1C connection is not enabled or configured", "ERP_CONNECTION_UNAVAILABLE");
        Snapshot remote=load(urls.get(0), tenant, company, date);
        return reconcileSnapshot(tenant,company,date,remote);
    }
    public Map<String,Object> reconcileFromEvidence(String tenant,UUID company,LocalDate date,JsonNode evidence) {
        return reconcileSnapshot(tenant,company,date,parse(evidence,tenant,company,date));
    }
    private Map<String,Object> reconcileSnapshot(String tenant,UUID company,LocalDate date,Snapshot remote) {
        var scopeParams=new MapSqlParameterSource("tenant",tenant).addValue("company",company)
            .addValue("warehouses",remote.warehouses()).addValue("materials",remote.materials());
        Integer warehouseCount=named.queryForObject("select count(*) from warehouses where tenant_id=:tenant and company_id=:company and is_delete=false and id in (:warehouses)",scopeParams,Integer.class);
        Integer materialCount=named.queryForObject("select count(*) from materials where tenant_id=:tenant and company_id=:company and is_delete=false and id in (:materials)",scopeParams,Integer.class);
        if (warehouseCount == null || warehouseCount != remote.warehouses().size() || materialCount == null || materialCount != remote.materials().size())
            throw new BadRequestException("1C balance scope contains unknown or out-of-company materials or warehouses", "ERP_SCOPE_INVALID");
        for (UUID warehouse : remote.warehouses()) access.assertWarehouseAccess(warehouse);
        var asOf=historical.historical(tenant, company, remote.warehouses(), date);
        var params=new MapSqlParameterSource("tenant",tenant).addValue("company",company).addValue("warehouses",remote.warehouses()).addValue("materials",remote.materials());
        List<Map<String,Object>> positions=named.queryForList("""
            select id,material_id,warehouse_id from stock_balances
            where tenant_id=:tenant and company_id=:company and is_delete=false
              and warehouse_id in (:warehouses) and material_id in (:materials)
            """,params);
        Map<Key,Balance> mm=new HashMap<>();
        for (Map<String,Object> position : positions) {
            UUID id=(UUID)position.get("id"); var entry=asOf.get(id);
            if (entry == null) throw new BadRequestException("Historical MM balance evidence is incomplete", "ERP_MM_HISTORY_INCOMPLETE");
            Key key=new Key((UUID)position.get("material_id"),(UUID)position.get("warehouse_id"));
            mm.merge(key,new Balance(entry.quantity(),entry.value()),Balance::plus);
        }
        List<Map<String,Object>> differences=new ArrayList<>();
        for (UUID material : remote.materials()) for (UUID warehouse : remote.warehouses()) {
            Key key=new Key(material,warehouse); Balance left=mm.getOrDefault(key,Balance.zero()), right=remote.balances().get(key);
            if (left.quantity().compareTo(right.quantity()) != 0 || left.amount().compareTo(right.amount()) != 0) {
                Map<String,Object> item=new LinkedHashMap<>(); item.put("materialId",material); item.put("warehouseId",warehouse);
                item.put("mmQuantity",left.quantity()); item.put("erpQuantity",right.quantity());
                item.put("mmAmount",left.amount()); item.put("erpAmount",right.amount()); differences.add(item);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("date",date.toString()); result.put("basis","END_OF_DAY");
        result.put("scope", Map.of("materialIds",remote.materials(),"warehouseIds",remote.warehouses()));
        result.put("source","ONE_C"); result.put("stockChanged",false); result.put("differenceCount",differences.size()); result.put("differences",differences);
        return result;
    }
    Snapshot load(String base, String tenant, UUID company, LocalDate date) {
        if(transport!=null&&transport.pullEnabled())throw new com.enterprise.mm.error.ConflictException("Create an asynchronous native 1C balance query");
        try {
            URI uri=URI.create(base.replaceAll("/+$", "")+"/mm/v1/balances?date="+date);
            if (nativeApi == null && (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getUserInfo()!=null || uri.getFragment()!=null))
                throw invalid("Only the local loopback adapter is allowed");
            HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET();
            if (nativeApi != null) nativeApi.authorize(builder, base, tenant, company);
            HttpRequest request=builder.build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw invalid("1C balance endpoint returned HTTP " + response.statusCode());
            return parse(mapper.readTree(response.body()),tenant,company,date);
        } catch (BadRequestException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw invalid("1C balance request was interrupted"); }
        catch (Exception ex) { throw invalid("Could not read complete 1C balance snapshot"); }
    }
    static Snapshot parse(JsonNode data, String tenant, UUID company, LocalDate date) {
        if (data==null || !data.path("protocolVersion").isIntegralNumber() || data.path("protocolVersion").asInt()!=1
            || !tenant.equals(data.path("tenantId").asText()) || !company.toString().equals(data.path("companyId").asText())
            || !date.toString().equals(data.path("date").asText()) || !"END_OF_DAY".equals(data.path("basis").asText())
            || !data.path("complete").isBoolean() || !data.path("complete").asBoolean()) throw invalid("1C balance snapshot identity or completeness is invalid");
        List<UUID> materials=ids(data.path("scope").path("materialIds")); List<UUID> warehouses=ids(data.path("scope").path("warehouseIds"));
        long count=(long)materials.size()*warehouses.size(); JsonNode balances=data.path("balances");
        if (count>100000 || !balances.isArray() || balances.size()!=count) throw invalid("1C balance snapshot does not cover its complete scope");
        Set<UUID> materialSet=Set.copyOf(materials), warehouseSet=Set.copyOf(warehouses); Map<Key,Balance> values=new LinkedHashMap<>();
        for (JsonNode row : balances) {
            UUID material=id(row.path("materialId")), warehouse=id(row.path("warehouseId"));
            if (!materialSet.contains(material) || !warehouseSet.contains(warehouse)) throw invalid("1C balance row is outside its declared scope");
            Balance value=new Balance(number(row.path("quantity"),7,15),number(row.path("amount"),4,15));
            if (values.putIfAbsent(new Key(material,warehouse),value)!=null) throw invalid("1C balance snapshot contains duplicate positions");
        }
        return new Snapshot(List.copyOf(materials),List.copyOf(warehouses),Map.copyOf(values));
    }
    private static List<UUID> ids(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size()>100000) throw invalid("1C balance scope must be nonempty");
        List<UUID> result=new ArrayList<>(); Set<UUID> seen=new HashSet<>();
        for (JsonNode item:node) { UUID value=id(item); if (!seen.add(value)) throw invalid("1C balance scope contains duplicate IDs"); result.add(value); }
        return result;
    }
    private static UUID id(JsonNode node) {
        if (!node.isTextual()) throw invalid("1C balance ID must be a UUID string");
        try { UUID value=UUID.fromString(node.asText()); if (!value.toString().equals(node.asText())) throw invalid("1C balance UUID is not canonical"); return value; }
        catch (IllegalArgumentException ex) { throw invalid("1C balance UUID is invalid"); }
    }
    private static BigDecimal number(JsonNode node, int scale, int integralDigits) {
        if (!node.isTextual() || !node.asText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw invalid("1C balance value must be a decimal string");
        BigDecimal value=new BigDecimal(node.asText());
        if (value.scale()>scale || Math.max(0,value.precision()-value.scale())>integralDigits) throw invalid("1C balance value exceeds supported precision");
        return value;
    }
    private static BadRequestException invalid(String message) { return new BadRequestException(message,"ERP_BALANCE_SNAPSHOT_INVALID"); }
}
