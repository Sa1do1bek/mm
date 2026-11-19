package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.enterprise.mm.erp.ErpPeriodEvidence.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ErpPeriodReviewTest {
    final ObjectMapper mapper=new ObjectMapper(); final String tenant="period-tests";
    final UUID company=UUID.randomUUID(),material=UUID.randomUUID(),warehouse=UUID.randomUUID(),balance=UUID.randomUUID();
    final LocalDate start=LocalDate.of(2025,4,1),end=start.plusDays(1);
    ObjectNode evidence() {
        var root=mapper.createObjectNode();root.put("protocolVersion",1);root.put("tenantId",tenant);root.put("companyId",company.toString());root.put("from",start.toString());root.put("to",end.toString());root.put("basis","DAILY_ACCOUNTING_TURNOVER");root.put("complete",true);
        var scope=root.putObject("scope");scope.putArray("materialIds").add(material.toString());scope.putArray("warehouseIds").add(warehouse.toString());
        root.putArray("openingBalances").add(position().put("quantity","1").put("amount","10"));
        root.putArray("closingBalances").add(position().put("quantity","6").put("amount","60"));
        var days=root.putArray("days");
        for(int i=0;i<2;i++){var day=days.addObject();day.put("date",start.plusDays(i).toString());day.putArray("turnovers").add(position().put("inQuantity",i==0?"2":"3").put("inAmount",i==0?"20":"30").put("outQuantity","0").put("outAmount","0"));}
        root.putArray("documents");return root;
    }
    ObjectNode position(){return mapper.createObjectNode().put("materialId",material.toString()).put("warehouseId",warehouse.toString());}
    @BeforeEach void context(){TenantContext.setTenant(tenant);CompanyContext.setCompany(company);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer","ignored",List.of()));}
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();SecurityContextHolder.clearContext();}
    @Test void calendarUsesFullClosedMonthAndExplicitPartialCurrentMonth(){
        var leap=ErpPeriodReviewService.period("MONTH",LocalDate.of(2024,2,17),LocalDate.of(2026,11,16));
        assertThat(leap.from()).isEqualTo(LocalDate.of(2024,2,1));assertThat(leap.to()).isEqualTo(LocalDate.of(2024,2,29));assertThat(leap.partial()).isFalse();
        var current=ErpPeriodReviewService.period("MONTH",LocalDate.of(2026,11,1),LocalDate.of(2026,11,16));assertThat(current.to()).isEqualTo(LocalDate.of(2026,11,16));assertThat(current.partial()).isTrue();
        assertThatThrownBy(()->ErpPeriodReviewService.period("DAY",LocalDate.of(2026,11,17),LocalDate.of(2026,11,16))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->ErpPeriodReviewService.period(null,start,end)).isInstanceOf(BadRequestException.class);
    }
    @Test void incompleteWrongScopeOrInconsistentNativeEvidenceCannotBecomeZeroDifferences(){
        assertThat(parse(evidence(),tenant,company,start,end).days()).hasSize(2);
        var missing=evidence();((ArrayNode)missing.path("days")).remove(0);assertThatThrownBy(()->parse(missing,tenant,company,start,end)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->parse(evidence(),tenant,UUID.randomUUID(),start,end)).isInstanceOf(BadRequestException.class);
        var changed=evidence();((ObjectNode)changed.path("closingBalances").get(0)).put("quantity","7");assertThatThrownBy(()->parse(changed,tenant,company,start,end)).hasMessageContaining("opening plus turnover");
        var duplicate=evidence();((ObjectNode)duplicate.path("days").get(1)).put("date",start.toString());assertThatThrownBy(()->parse(duplicate,tenant,company,start,end)).hasMessageContaining("duplicate");
        var precision=evidence();((ObjectNode)precision.path("days").get(0).path("turnovers").get(0)).put("inQuantity","0.12345678");assertThatThrownBy(()->parse(precision,tenant,company,start,end)).hasMessageContaining("precision");
    }
    @Test void monthlyTotalsCannotHideOffsettingDailyDifferences() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mm/v1/balances",exchange->{byte[] bytes=mapper.writeValueAsBytes(evidence());exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();var jdbc=mock(JdbcTemplate.class);var named=mock(NamedParameterJdbcTemplate.class);var historical=mock(InventoryStockAsOfQuery.class);var access=mock(RoleDataScopeService.class);
            when(jdbc.queryForList(anyString(),eq(String.class),eq(tenant),eq(company))).thenReturn(List.of(base));
            when(historical.historical(tenant,company,List.of(warehouse),start.minusDays(1))).thenReturn(Map.of(balance,new InventoryStockAsOfQuery.HistoricalBalance(new BigDecimal("1"),new BigDecimal("10"))));
            when(historical.historical(tenant,company,List.of(warehouse),end)).thenReturn(Map.of(balance,new InventoryStockAsOfQuery.HistoricalBalance(new BigDecimal("6"),new BigDecimal("60"))));
            when(named.queryForList(anyString(),any(SqlParameterSource.class))).thenAnswer(call->{String sql=call.getArgument(0);
                if(sql.startsWith("select id,name from warehouses"))return List.of(Map.of("id",warehouse,"name","A"));
                if(sql.contains("from materials m"))return List.of(Map.of("id",material,"name","Material","unit","kg"));
                if(sql.startsWith("select id,material_id,warehouse_id"))return List.of(Map.of("id",balance,"material_id",material,"warehouse_id",warehouse));
                if(sql.contains("as in_quantity")){List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<2;i++)rows.add(Map.of("day",start.plusDays(i).toString(),"material_id",material,"warehouse_id",warehouse,"in_quantity",new BigDecimal(i==0?"3":"2"),"in_amount",new BigDecimal(i==0?"30":"20"),"out_quantity",BigDecimal.ZERO,"out_amount",BigDecimal.ZERO));return rows;}
                return List.of();});
            var service=new ErpPeriodReviewService(jdbc,named,historical,access,new OneCApiAccess(base,"x".repeat(32),tenant,company.toString()),mapper);
            var report=service.calculate(new ErpPeriodReviewService.Period("MONTH",start,end,false));var summary=mapper.valueToTree(report).path("summary");
            assertThat(summary.path("differenceCount").asInt()).isEqualTo(1);assertThat(summary.path("dailyDifferenceCount").asInt()).isEqualTo(2);
            assertThat(summary.path("totals").path("closing").path("amountDelta").asText()).isEqualTo("0");
            assertThat(mapper.valueToTree(report).path("rows").get(0).path("hasDifference").asBoolean()).isTrue();
            assertThat(summary.path("totals").toString()).doesNotContain("Quantity");verify(access).assertWarehouseAccess(warehouse);verify(named,never()).update(anyString(),any(SqlParameterSource.class));
        } finally {server.stop(0);}
    }
    @Test void exactComparisonAndHashDoNotRoundOrDependOnMapInsertionOrder(){
        var value=new Amount(new BigDecimal("0.1234567"),new BigDecimal("9007199254740993.1234"));
        assertThat(ErpPeriodReviewService.comparison(value,Amount.zero()).get("amountDelta")).isEqualTo("9007199254740993.1234");
        assertThat(ErpPeriodReviewService.fingerprint(mapper,new LinkedHashMap<>(Map.of("b",2,"a",1)))).isEqualTo(ErpPeriodReviewService.fingerprint(mapper,new TreeMap<>(Map.of("a",1,"b",2))));
    }
    @Test void staleEvidenceCannotBeMarkedReviewed() throws Exception {
        UUID id=UUID.randomUUID();var named=mock(NamedParameterJdbcTemplate.class);var access=mock(RoleDataScopeService.class);
        var savedReport=new LinkedHashMap<String,Object>();savedReport.put("scope",Map.of("warehouseIds",List.of(warehouse.toString())));savedReport.put("partialPeriod",false);savedReport.put("value","original");
        String hash=ErpPeriodReviewService.fingerprint(mapper,savedReport);
        Map<String,Object> saved=Map.of("id",id,"report",mapper.writeValueAsString(savedReport),"fingerprint",hash,"review_version",0L,"period_type","DAY","period_start",start.toString(),"period_end",start.toString(),"captured_at",java.sql.Timestamp.from(Instant.now()));
        when(named.queryForList(anyString(),any(SqlParameterSource.class))).thenReturn(List.of(saved));when(named.queryForObject(anyString(),any(SqlParameterSource.class),eq(Boolean.class))).thenReturn(false);when(access.canAccessWarehouse(warehouse)).thenReturn(true);
        var service=spy(new ErpPeriodReviewService(mock(JdbcTemplate.class),named,mock(InventoryStockAsOfQuery.class),access,mock(OneCApiAccess.class),mapper));
        var changed=new LinkedHashMap<String,Object>(savedReport);changed.put("value","changed");doReturn(changed).when(service).calculate(any());
        assertThatThrownBy(()->service.review(id,"REVIEWED","Checked",0,hash)).isInstanceOf(ConflictException.class).hasMessageContaining("изменились");
        verify(named,never()).update(anyString(),any(SqlParameterSource.class));
        when(access.canAccessWarehouse(warehouse)).thenReturn(false);assertThatThrownBy(()->service.get(id)).isInstanceOf(NotFoundException.class);
    }
}
