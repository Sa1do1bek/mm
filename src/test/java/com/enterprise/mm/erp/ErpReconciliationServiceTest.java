package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.InventoryStockAsOfQuery;
import com.enterprise.mm.service.RoleDataScopeService;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ErpReconciliationServiceTest {
    private static final String TENANT="reconcile-test";
    private final UUID company=UUID.randomUUID(), warehouse=UUID.randomUUID(), material=UUID.randomUUID();
    private final LocalDate date=LocalDate.of(2025,3,1);
    private final ObjectMapper mapper=new ObjectMapper();
    private String snapshot(String quantity, String amount) {
        return "{\"protocolVersion\":1,\"tenantId\":\""+TENANT+"\",\"companyId\":\""+company+"\",\"date\":\""+date+"\",\"basis\":\"END_OF_DAY\",\"complete\":true,\"scope\":{\"materialIds\":[\""+material+"\"],\"warehouseIds\":[\""+warehouse+"\"]},\"balances\":[{\"materialId\":\""+material+"\",\"warehouseId\":\""+warehouse+"\",\"quantity\":\""+quantity+"\",\"amount\":\""+amount+"\"}]}";
    }
    @Test void refusesIncompleteWrongIdentityDuplicateAndPrecision() throws Exception {
        String valid=snapshot("1.1234567","5.0010");
        assertThat(ErpReconciliationService.parse(mapper.readTree(valid),TENANT,company,date).balances()).hasSize(1);
        assertThatThrownBy(() -> ErpReconciliationService.parse(mapper.readTree(valid.replace("\"complete\":true","\"complete\":false")),TENANT,company,date)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ErpReconciliationService.parse(mapper.readTree(valid),TENANT,UUID.randomUUID(),date)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ErpReconciliationService.parse(mapper.readTree(snapshot("1.12345678","5")),TENANT,company,date)).isInstanceOf(BadRequestException.class);
        var duplicated=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(valid);
        var ids=(com.fasterxml.jackson.databind.node.ArrayNode)duplicated.path("scope").path("materialIds"); ids.add(material.toString());
        assertThatThrownBy(() -> ErpReconciliationService.parse(duplicated,TENANT,company,date)).isInstanceOf(BadRequestException.class);
        var missing=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(valid);
        ((com.fasterxml.jackson.databind.node.ArrayNode)missing.path("balances")).removeAll();
        assertThatThrownBy(() -> ErpReconciliationService.parse(missing,TENANT,company,date)).isInstanceOf(BadRequestException.class);
    }
    @Test void comparesHistoricalEvidenceAndRefusesUnreconciledLedger() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:reconcile-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds); var named=new NamedParameterJdbcTemplate(ds); var historical=new InventoryStockAsOfQuery(named);
        jdbc.execute("create table erp_exchange_connection (tenant_id varchar(80), company_id uuid, enabled boolean, base_url varchar(500))");
        jdbc.execute("create table warehouses (id uuid primary key, tenant_id varchar(80), company_id uuid, is_delete boolean)");
        jdbc.execute("create table materials (id uuid primary key, tenant_id varchar(80), company_id uuid, is_delete boolean)");
        jdbc.update("insert into warehouses values (?,?,?,false)",warehouse,TENANT,company);
        jdbc.update("insert into materials values (?,?,?,false)",material,TENANT,company);
        jdbc.execute("create table stock_balances (id uuid primary key, tenant_id varchar(80), company_id uuid, material_id uuid, warehouse_id uuid, is_delete boolean, quantity numeric(22,7), total_value numeric(19,4))");
        jdbc.execute("create table stock_movements (id uuid primary key, tenant_id varchar(80), company_id uuid, material_id uuid, warehouse_id uuid, target_warehouse_id uuid, movement_date date, posted_at timestamp)");
        jdbc.execute("create table stock_movement_allocations (id uuid primary key, tenant_id varchar(80), company_id uuid, movement_id uuid, material_id uuid, balance_id uuid, on_hand_delta numeric(22,7), total_value_delta numeric(19,4))");
        jdbc.execute("create table warehouse_posting_batches (id uuid primary key, tenant_id varchar(80), company_id uuid, status varchar(30))");
        jdbc.execute("create table warehouse_posting_effects (id uuid primary key, batch_id uuid, warehouse_id uuid, effect_type varchar(30))");
        UUID balance=UUID.randomUUID(); jdbc.update("insert into stock_balances values (?,?,?,?,?,?,?,?)",balance,TENANT,company,material,warehouse,false,new BigDecimal("7"),new BigDecimal("70"));
        movement(jdbc,balance,date,"10","100"); movement(jdbc,balance,date.plusDays(1),"-3","-30");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mm/v1/balances", exchange -> { byte[] body=snapshot("10","100").getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length); try(var out=exchange.getResponseBody()){out.write(body);} });
        server.start();
        try {
            jdbc.update("insert into erp_exchange_connection values (?,?,true,?)",TENANT,company,"http://127.0.0.1:"+server.getAddress().getPort());
            var access=mock(RoleDataScopeService.class);
            var service=new ErpReconciliationService(jdbc,named,historical,mapper,access);
            var result=service.reconcile(TENANT,company,date);
            verify(access).assertWarehouseAccess(warehouse);
            assertThat(result.get("differenceCount")).isEqualTo(0);
            assertThat(result.get("basis")).isEqualTo("END_OF_DAY");
            assertThat(result.get("stockChanged")).isEqualTo(false);
            jdbc.update("update warehouses set company_id=? where id=?",UUID.randomUUID(),warehouse);
            assertThatThrownBy(() -> service.reconcile(TENANT,company,date)).isInstanceOf(BadRequestException.class).hasMessageContaining("scope");
            jdbc.update("update warehouses set company_id=? where id=?",company,warehouse);
            jdbc.update("update stock_balances set quantity=8 where id=?",balance);
            assertThatThrownBy(() -> service.reconcile(TENANT,company,date)).isInstanceOf(BusinessException.class).hasMessageContaining("incomplete");
        } finally { server.stop(0); }
    }
    private void movement(JdbcTemplate jdbc, UUID balance, LocalDate day, String qty, String value) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into stock_movements values (?,?,?,?,?,?,?,?)",id,TENANT,company,material,warehouse,null,day,day.atStartOfDay());
        jdbc.update("insert into stock_movement_allocations values (?,?,?,?,?,?,?,?)",UUID.randomUUID(),TENANT,company,id,material,balance,new BigDecimal(qty),new BigDecimal(value));
    }
}
