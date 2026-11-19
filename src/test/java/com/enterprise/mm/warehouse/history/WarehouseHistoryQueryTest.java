package com.enterprise.mm.warehouse.history;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WarehouseHistoryQueryTest {
    JdbcTemplate jdbc;WarehouseHistoryQueryService service;UUID company=UUID.randomUUID(),warehouse=UUID.randomUUID(),record=UUID.randomUUID();RoleDataScopeService roles;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__warehouse_source_history.sql")).execute(ds);jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table materials(id uuid,tenant_id varchar,company_id uuid,name varchar)");jdbc.execute("create table warehouses(id uuid,tenant_id varchar,company_id uuid,name varchar)");
        roles=mock(RoleDataScopeService.class);service=new WarehouseHistoryQueryService(new NamedParameterJdbcTemplate(ds),new ObjectMapper(),roles);
        TenantContext.setTenant("test");CompanyContext.setCompany(company);
        UUID run=UUID.randomUUID();jdbc.update("insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status) values(?,'test',?,'hash','org','{}',1,'hash','hash','COMPLETED')",run,company);
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,document_date,warehouse_id,quantity,row_sha256,content) values(?,'test',?,?,1,'DOCUMENT','key','_document162','document:key','Transfer',TIMESTAMP '2026-10-07 00:00:00',?,'0.0000001','hash',?)",record,company,run,warehouse,"{\"kind\":\"DOCUMENT\",\"quantity\":\"0.0000001\",\"data\":{}}");
    }
    @AfterEach void clear(){TenantContext.clear();CompanyContext.clear();}
    @Test void readsStayInCurrentCompanyAndTenant() {
        assertThat(service.records("DOCUMENT",null,null,null,null,null,null,null,0,50)).containsEntry("totalElements",1L);
        CompanyContext.setCompany(UUID.randomUUID());assertThat(service.records("DOCUMENT",null,null,null,null,null,null,null,0,50)).containsEntry("totalElements",0L);
        assertThatThrownBy(()->service.detail(record)).isInstanceOf(NotFoundException.class);
        CompanyContext.setCompany(company);TenantContext.setTenant("other");assertThat(service.records("DOCUMENT",null,null,null,null,null,null,null,0,50)).containsEntry("totalElements",0L);
    }
    @Test void warehouseAssignmentRestrictsListSummaryAndDetail() {
        when(roles.effectiveWarehouseId(any())).thenReturn(UUID.randomUUID());
        assertThat(service.records("DOCUMENT",null,null,null,null,null,null,null,0,50)).containsEntry("totalElements",0L);
        assertThat((List<?>)service.summary().get("counts")).isEmpty();assertThatThrownBy(()->service.detail(record)).isInstanceOf(NotFoundException.class);
        when(roles.effectiveWarehouseId(any())).thenReturn(warehouse);assertThat(service.records("DOCUMENT",null,null,null,null,null,null,null,0,50)).containsEntry("totalElements",1L);
    }
    @Test void detailKeepsExactQuantityAndDateFiltersUseInclusiveEndDate() {
        assertThat(service.detail(record)).containsEntry("quantity","0.0000001");
        assertThat(service.records("DOCUMENT",null,null,null,null,null,"2026-10-07","2026-10-07",0,50)).containsEntry("totalElements",1L);
        assertThat(service.records("DOCUMENT",null,null,null,null,null,"2026-10-08",null,0,50)).containsEntry("totalElements",0L);
    }
    @Test void archivedReferenceNamesResolveWithoutCreatingOperationalMaterials() {
        String source="a".repeat(32);
        jdbc.update("update warehouse_history_records set content=? where id=?", "{\"kind\":\"DOCUMENT\",\"materialSourceId\":\""+source+"\",\"data\":{}}",record);
        UUID run=jdbc.queryForObject("select import_id from warehouse_history_records where id=?",UUID.class,record);
        jdbc.update("insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_type,row_sha256,content) values(?,'test',?,?,2,'SCHEMA',?,'_reference54','Material','hash',?)",UUID.randomUUID(),company,run,"reference:materials:"+source,"{\"data\":{\"name\":\"Original archived material\"}}");
        var page=service.records("DOCUMENT",null,null,null,null,null,null,null,0,50);
        var first=(Map<?,?>)((List<?>)page.get("content")).getFirst();assertThat(first.get("materialName")).isEqualTo("Original archived material");
        assertThat(jdbc.queryForObject("select count(*) from materials",Long.class)).isZero();
    }
}
