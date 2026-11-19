package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest @ActiveProfiles("test")
class OperationalMasterMigrationServiceIntegrationTest {
    static final String SOURCE_HASH="a".repeat(64), PREPARED_HASH="b".repeat(64);
    @Autowired OperationalMasterMigrationService service;
    @Autowired EmployeeService employeeService;
    @Autowired WarehouseService warehouseService;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    String tenant; UUID company;

    @BeforeEach void setup() {
        tenant="operational-"+UUID.randomUUID(); TenantContext.setTenant(tenant); authenticate("ADMIN");
        company=tx.execute(status->{
            Company c=base(new Company(),"Company",UUID.randomUUID()); em.persist(c); c.setCompanyId(c.getId());
            DepartmentType t=base(new DepartmentType(),"DEPARTMENT",c.getId()); em.persist(t); return c.getId();
        }); CompanyContext.setCompany(company);
    }
    @AfterEach void cleanup() {
        AccessScope.clearRequestedCompanyScope(); SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear();
    }
    @Test void incompleteSuppliersStayUnaccreditedAndReplayWithoutDuplicates() {
        var a=supplier(1,"Current supplier"); var b=supplier(2,"Archived supplier");
        ((ObjectNode)b.payload()).put("markedForDeletion",true);
        var command=command(a,b); var first=service.importMasters("suppliers",tenant,company,command);
        assertThat(service.importMasters("suppliers",tenant,company,command)).isEqualTo(first);
        var partition=new MasterChunkRequest(command.importId(),UUID.randomUUID(),SOURCE_HASH,PREPARED_HASH,command.rows());
        assertThat(service.importMasters("suppliers",tenant,company,partition).rows()).isEqualTo(first.rows());
        assertThat(count("suppliers")).isEqualTo(2);
        assertThat(jdbc.queryForList("select status from suppliers where tenant_id=?",String.class,tenant)).containsExactlyInAnyOrder("NEW","ARCHIVED");
        assertThat(jdbc.queryForObject("select count(*) from suppliers where tenant_id=? and (email is not null or registration_number is not null)",Long.class,tenant)).isZero();
        assertThat(count("employees")).isZero(); assertThat(count("stock_movements")).isZero();
        var changed=supplier(1,"Changed supplier");
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,company,command(changed))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,company,
            new MasterChunkRequest(command.importId(),UUID.randomUUID(),SOURCE_HASH,PREPARED_HASH,List.of(changed))))
            .isInstanceOf(ConflictException.class);
    }
    @Test void bankAccountsRetainEvidenceWithoutInventingAPrimaryAccount() {
        var row=supplier(1,"Bank supplier");
        ((ObjectNode)row.payload()).putArray("bankAccounts").addObject().put("bankName","Known bank")
            .put("bankAccount","TEST123456789").put("primaryAccount",false);
        var id=service.importMasters("suppliers",tenant,company,command(row)).rows().getFirst().masterId();
        tx.executeWithoutResult(status->{
            var supplier=em.find(Supplier.class,id); assertThat(supplier.getBankAccount()).isNull();
            assertThat(supplier.getBankAccounts()).hasSize(1); assertThat(supplier.getBankAccounts().getFirst().isPrimaryAccount()).isFalse();
            assertThat(supplier.getBankAccounts().getFirst().getBankAccount()).isEqualTo("TEST123456789");
        });
        var duplicate=supplier(2,"Duplicate bank supplier");
        ((ObjectNode)duplicate.payload()).set("bankAccounts",row.payload().path("bankAccounts"));
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,company,command(duplicate))).isInstanceOf(ConflictException.class);
        assertThat(count("suppliers")).isEqualTo(1);
    }
    @Test void normalTaxUniquenessAndAtomicRollbackRemainEnforced() {
        var a=supplier(1,"One"); var b=supplier(2,"Two");
        ((ObjectNode)a.payload()).put("taxId","TEST-INN-1"); ((ObjectNode)b.payload()).put("taxId","TEST-INN-1");
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,company,command(a,b))).isInstanceOf(ConflictException.class);
        assertThat(count("suppliers")).isZero(); assertThat(count("idempotency_records")).isZero(); assertThat(count("audit_logs")).isZero();
    }
    @Test void supplierBusinessNamesContainingWorkflowWordsSurviveImportAndRecovery() {
        var row=supplier(1,"Transit Line Ltd.");
        var first=service.importMasters("suppliers",tenant,company,command(row));
        UUID id=first.rows().getFirst().masterId();
        assertThat(jdbc.queryForObject("select name from suppliers where id=?",String.class,id))
            .isEqualTo(LocalizedText.ensureComplete("Transit Line Ltd."));
        var recover=new MasterRow(row.sourceId(),row.sourceEntityType(),id,row.payload());
        assertThat(service.importMasters("suppliers",tenant,company,command(recover)).rows().getFirst().created()).isFalse();
        assertThat(count("suppliers")).isEqualTo(1);
    }
    @Test void legacyNameRepairChangesOnlyNameAndReplaysOneAudit() {
        var row=supplier(1,"Transit Line Ltd.");
        ((ObjectNode)row.payload()).put("markedForDeletion",true);
        ((ObjectNode)row.payload()).putArray("bankAccounts").addObject().put("bankName","Known bank")
            .put("bankAccount","TEST123456789").put("primaryAccount",false);
        UUID id=service.importMasters("suppliers",tenant,company,command(row)).rows().getFirst().masterId();
        jdbc.update("update suppliers set name=? where id=?",
            com.enterprise.mm.util.BusinessNameGenerator.normalize("Transit Line Ltd.","Supplier"),id);
        var before=jdbc.queryForMap("select * from suppliers where id=?",id);
        Long version=((Number)before.get("VERSION")).longValue();
        var request=new SupplierNameRepairRequest(row.sourceId(),PREPARED_HASH,"Transit Line Ltd.",version);
        var result=service.repairSupplierName(id,tenant,company,request);
        assertThat(service.repairSupplierName(id,tenant,company,request)).usingRecursiveComparison().isEqualTo(result);
        assertThat(result.getStatus()).isEqualTo("ARCHIVED");
        assertThat(result.getBankAccounts()).hasSize(1);
        assertThat(result.getBankAccounts().getFirst().getPrimaryAccount()).isFalse();
        var after=jdbc.queryForMap("select * from suppliers where id=?",id);
        for(String key:before.keySet()) if(!Set.of("NAME","VERSION","UPDATED_AT","UPDATED_BY").contains(key.toUpperCase(Locale.ROOT)))
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        assertThat(after.get("NAME")).isEqualTo(LocalizedText.ensureComplete("Transit Line Ltd."));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where tenant_id=? and action='LEGACY_NAME_REPAIR'",Long.class,tenant)).isEqualTo(1);
        var recover=new MasterRow(row.sourceId(),row.sourceEntityType(),id,row.payload());
        assertThat(service.importMasters("suppliers",tenant,company,command(recover)).rows().getFirst().created()).isFalse();
    }
    @Test void nameRepairRejectsWrongSourceScopeRoleAndConcurrentEdits() {
        var row=supplier(1,"Transit Line Ltd.");
        UUID id=service.importMasters("suppliers",tenant,company,command(row)).rows().getFirst().masterId();
        Long version=jdbc.queryForObject("select version from suppliers where id=?",Long.class,id);
        var request=new SupplierNameRepairRequest(row.sourceId(),PREPARED_HASH,"Transit Line Ltd.",version);
        assertThatThrownBy(()->service.repairSupplierName(id,"foreign",company,request)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.repairSupplierName(id,tenant,company,
            new SupplierNameRepairRequest("2".repeat(32),PREPARED_HASH,"Transit Line Ltd.",version))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.repairSupplierName(id,tenant,company,
            new SupplierNameRepairRequest(row.sourceId(),"c".repeat(64),"Transit Line Ltd.",version))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.repairSupplierName(id,tenant,company,
            new SupplierNameRepairRequest(row.sourceId(),PREPARED_HASH,"Transit Line Ltd.",version+1))).isInstanceOf(ConflictException.class);
        authenticate("WAREHOUSE_MANAGER");
        assertThatThrownBy(()->service.repairSupplierName(id,tenant,company,request)).isInstanceOf(AccessDeniedException.class);
        authenticate("ADMIN");
        jdbc.update("update suppliers set name=? where id=?",LocalizedText.ensureComplete("Manually corrected supplier"),id);
        assertThatThrownBy(()->service.repairSupplierName(id,tenant,company,request)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where tenant_id=? and action='LEGACY_NAME_REPAIR'",Long.class,tenant)).isZero();
    }
    @Test void scopeRoleAndAccessProvisioningFieldsAreRejected() {
        var row=supplier(1,"Supplier");
        assertThatThrownBy(()->service.importMasters("suppliers","foreign",company,command(row))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,CompanyContext.DEFAULT_COMPANY_ID,command(row))).isInstanceOf(BadRequestException.class);
        authenticate("WAREHOUSE_MANAGER");
        assertThatThrownBy(()->service.importMasters("suppliers",tenant,company,command(row))).isInstanceOf(AccessDeniedException.class);
        authenticate("ADMIN"); var employee=row(1,"Catalog90","Employee");
        ((ObjectNode)employee.payload()).putArray("roles").add("ADMIN");
        assertThatThrownBy(()->service.importMasters("employees",tenant,company,command(employee))).isInstanceOf(BadRequestException.class);
        assertThat(count("employees")).isZero();
    }
    @Test void departmentHierarchyAndPositionReferencesStayWithinCompany() {
        var department=row(1,"Catalog70","Department"); ((ObjectNode)department.payload()).put("departmentType","DEPARTMENT");
        UUID departmentId=service.importMasters("departments",tenant,company,command(department)).rows().getFirst().masterId();
        var section=row(2,"Catalog8844","Department / Section");
        ((ObjectNode)section.payload()).put("parentDepartmentId",departmentId.toString()).put("departmentType","DEPARTMENT");
        UUID sectionId=service.importMasters("departments",tenant,company,command(section)).rows().getFirst().masterId();
        var position=row(3,"Catalog36Department","Warehouse worker");
        ((ObjectNode)position.payload()).put("departmentId",sectionId.toString()).put("headcount",1);
        UUID positionId=service.importMasters("positions",tenant,company,command(position)).rows().getFirst().masterId();
        var employee=row(4,"Catalog90","Staff member");
        ((ObjectNode)employee.payload()).put("departmentId",departmentId.toString()).put("positionId",positionId.toString());
        assertThatThrownBy(()->service.importMasters("employees",tenant,company,command(employee))).isInstanceOf(BadRequestException.class);
        ((ObjectNode)employee.payload()).put("departmentId",sectionId.toString());
        var staffId=service.importMasters("employees",tenant,company,command(employee)).rows().getFirst().masterId();
        assertThat(employeeService.get(staffId).getRoles()).isEmpty(); assertThat(employeeService.get(staffId).getUserId()).isNull();
        UUID foreign=warehouse(UUID.randomUUID(),"ACTIVE");
        var bad=row(5,"Catalog70","Bad department"); ((ObjectNode)bad.payload()).put("parentDepartmentId",foreign.toString());
        assertThatThrownBy(()->service.importMasters("departments",tenant,company,command(bad))).isInstanceOf(BadRequestException.class);
        assertThat(count("employees")).isEqualTo(1);
    }
    @Test void warehouseAssignmentSupportsCreateReplaceClearAndOlderClients() throws Exception {
        UUID warehouse=warehouse(company,"ACTIVE"), other=warehouse(company,"ACTIVE");
        var request=mapper.readValue("{\"name\":\"Warehouse staff\",\"warehouseId\":\""+warehouse+"\"}",EmployeeCreateRequest.class);
        var created=employeeService.create(request); assertThat(created.getWarehouseId()).isEqualTo(warehouse);
        assertThat(created.getWarehouseName()).isEqualTo(warehouseService.get(warehouse).getName());
        assertThat(warehouseService.get(warehouse).getResponsibleEmployeeIds()).containsExactly(created.getId());
        var omitted=mapper.readValue("{\"name\":\"Renamed staff\"}",EmployeeUpdateRequest.class);
        assertThat(employeeService.update(created.getId(),omitted).getWarehouseId()).isEqualTo(warehouse);
        var replace=mapper.readValue("{\"name\":\"Renamed staff\",\"warehouseId\":\""+other+"\"}",EmployeeUpdateRequest.class);
        assertThat(employeeService.update(created.getId(),replace).getWarehouseId()).isEqualTo(other);
        assertThat(warehouseService.get(warehouse).getResponsibleEmployeeIds()).isEmpty();
        var clear=mapper.readValue("{\"name\":\"Renamed staff\",\"warehouseId\":null}",EmployeeUpdateRequest.class);
        assertThat(employeeService.update(created.getId(),clear).getWarehouseId()).isNull();
    }
    @Test void foreignInactiveAndDeletedWarehousesCannotBeAssigned() {
        for(UUID id:List.of(warehouse(UUID.randomUUID(),"ACTIVE"),warehouse(company,"INACTIVE"))) {
            EmployeeCreateRequest request=new EmployeeCreateRequest(); request.setName("Staff"); request.setWarehouseId(id);
            assertThatThrownBy(()->employeeService.create(request)).isInstanceOf(BadRequestException.class);
        }
        UUID deleted=warehouse(company,"ACTIVE"); tx.executeWithoutResult(s->em.find(Warehouse.class,deleted).softDelete());
        EmployeeCreateRequest request=new EmployeeCreateRequest(); request.setName("Staff"); request.setWarehouseId(deleted);
        assertThatThrownBy(()->employeeService.create(request)).isInstanceOf(BadRequestException.class);
        assertThat(count("employees")).isZero();
    }
    UUID warehouse(UUID owner,String status) { return tx.execute(s->{
        Warehouse w=base(new Warehouse(),"WH-"+UUID.randomUUID(),owner); em.persist(w); w.setName("Central materials store"); w.setStatus(status); return w.getId(); }); }
    MasterRow supplier(int n,String name) { var r=row(n,"Catalog45",name); ((ObjectNode)r.payload()).put("legalName",name); return r; }
    MasterRow row(int n,String type,String name) {
        String source=String.format("%032x",n);
        ObjectNode p=mapper.createObjectNode().put("code","1C-"+type.toUpperCase(Locale.ROOT)+"-"+source).put("name",name);
        var row=new MasterRow(source,type,null,p); p.put("description",CatalogMasterMigrationService.sourceMarker(PREPARED_HASH,row)); return row;
    }
    MasterChunkRequest command(MasterRow... rows) { return new MasterChunkRequest(UUID.randomUUID(),UUID.randomUUID(),SOURCE_HASH,PREPARED_HASH,List.of(rows)); }
    <T extends BusinessEntity> T base(T e,String code,UUID owner) { e.setTenantId(tenant); e.setCompanyId(owner); e.setCode(code); e.setName(code); e.setStatus("ACTIVE"); return e; }
    long count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where tenant_id=? and company_id=?",Long.class,tenant,company); }
    void authenticate(String role) { var auth=new UsernamePasswordAuthenticationToken("importer",null,List.of(new SimpleGrantedAuthority("ROLE_"+role))); auth.setDetails(new AccessScope.RequestScope(true)); SecurityContextHolder.getContext().setAuthentication(auth); }
}
