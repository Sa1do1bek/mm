package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.WorkCompletionActCreateRequest;
import com.enterprise.mm.payload.WorkCompletionActResponse;
import com.enterprise.mm.payload.WorkCompletionActSearchRequest;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkCompletionActDataScopeIntegrationTest {
    @Autowired private WorkCompletionActService service;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ContractRepository contractRepository;
    @Autowired private PurchaseRequestRepository purchaseRequestRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private PurchaseOrderItemRepository itemRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SupplierPortalAccountRepository portalAccountRepository;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        authenticate("scope-seed-admin", "ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void getHidesForeignDepartmentWorkActButReturnsOwnedWorkAct() {
        String actor = "works-viewer-" + shortId();
        Department ownDepartment = department("OWN");
        Department foreignDepartment = department("FOREIGN");
        employee(actor, ownDepartment);
        String milestone = "SCOPE-" + shortId();
        Origin owned = origin(supplier("Owned supplier"), request(actor, ownDepartment), milestone);
        Origin foreign = origin(supplier("Foreign supplier"), request("another-worker", foreignDepartment), milestone);

        authenticate(actor, "VIEWER");

        assertThat(service.get(owned.act().getId()).getId()).isEqualTo(owned.act().getId());
        assertThatThrownBy(() -> service.get(foreign.act().getId()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("WorkCompletionAct not found");
    }

    @Test
    void searchFiltersForeignDepartmentRecordsBeforePagingAndCount() {
        String actor = "works-worker-" + shortId();
        Department ownDepartment = department("OWN");
        Department foreignDepartment = department("FOREIGN");
        employee(actor, ownDepartment);
        String milestone = "SEARCH-SCOPE-" + shortId();
        Origin owned = origin(supplier("Owned supplier"), request(actor, ownDepartment), milestone);
        origin(supplier("Foreign supplier"), request("unrelated-worker", foreignDepartment), milestone);

        authenticate(actor, "DEPARTMENT_WORKER");
        Page<WorkCompletionActResponse> result = service.search(searchByMilestone(milestone), PageRequest.of(0, 1));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(WorkCompletionActResponse::getId)
            .containsExactly(owned.act().getId());
    }

    @Test
    void supplierManagerSeesOnlyWorkActsLinkedToOwnSupplier() {
        String login = "works-supplier-" + UUID.randomUUID() + "@vendor.test";
        Supplier linkedSupplier = supplier("Linked works supplier");
        Supplier unrelatedSupplier = supplier("Unrelated works supplier");
        portalAccount(linkedSupplier, login);
        String milestone = "SUPPLIER-SCOPE-" + shortId();
        Origin linked = origin(linkedSupplier, null, milestone);
        Origin unrelated = origin(unrelatedSupplier, null, milestone);

        authenticate(login, "SUPPLIER_MANAGER");
        Page<WorkCompletionActResponse> result = service.search(searchByMilestone(milestone), PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(WorkCompletionActResponse::getId)
            .containsExactly(linked.act().getId());
        assertThat(service.get(linked.act().getId()).getId()).isEqualTo(linked.act().getId());
        assertThatThrownBy(() -> service.get(unrelated.act().getId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierManagerCanReadLinkedWorkActThroughHttpEndpoint() throws Exception {
        String login = "works-http-" + UUID.randomUUID() + "@vendor.test";
        Supplier linkedSupplier = supplier("HTTP linked works supplier");
        portalAccount(linkedSupplier, login);
        Origin linked = origin(linkedSupplier, null, "HTTP-SCOPE-" + shortId());

        mockMvc.perform(get("/api/work-completion-acts/{id}", linked.act().getId())
                .with(user(login).roles("SUPPLIER_MANAGER"))
                .header("X-Tenant-Id", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(linked.act().getId().toString()));
    }

    private WorkCompletionActSearchRequest searchByMilestone(String milestone) {
        return new WorkCompletionActSearchRequest(
            null, null, null, null, null, milestone,
            null, null, null, null, null, null, null, false
        );
    }

    private Origin origin(Supplier supplier, PurchaseRequest request, String milestone) {
        authenticate("scope-seed-admin", "ADMIN");
        Contract contract = new Contract();
        contract.setCode("CON-" + shortId());
        contract.setName("Works scope contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(supplier.getId());
        contract.setLimitAmount(BigDecimal.valueOf(100000));
        contract.setCurrencyCode("UZS");
        contract = contractRepository.save(contract);

        PurchaseOrder order = new PurchaseOrder();
        order.setCode("PO-" + shortId());
        order.setName("Works scope purchase order");
        order.setStatus(PurchaseOrderStatus.SENT.name());
        order.setRequestType(RequestType.SERVICE);
        order.setSupplierId(supplier.getId());
        order.setContractId(contract.getId());
        order.setPurchaseRequestId(request == null ? null : request.getId());
        order.setPurchaseRequest(request);
        order.setTotalAmount(BigDecimal.valueOf(1000));
        order = purchaseOrderRepository.save(order);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-" + shortId());
        item.setName("Works scope line");
        item.setStatus("OPEN");
        item.setPurchaseOrderId(order.getId());
        item.setLineType(ProcurementLineType.WORK);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.valueOf(100));
        item = itemRepository.save(item);

        WorkCompletionActCreateRequest create = new WorkCompletionActCreateRequest();
        create.setPurchaseOrderId(order.getId());
        create.setPurchaseOrderItemId(item.getId());
        create.setContractId(contract.getId());
        create.setMilestone(milestone);
        create.setPeriodStart(LocalDate.of(2026, 7, 1));
        create.setPeriodEnd(LocalDate.of(2026, 7, 15));
        create.setCompletedQuantity(BigDecimal.ONE);
        create.setAmount(BigDecimal.valueOf(100));
        create.setInspectionResult("PASSED");
        WorkCompletionActResponse act = service.create(create);
        return new Origin(order, item, act);
    }

    private PurchaseRequest request(String requestedBy, Department department) {
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-" + shortId());
        request.setName("Works scope request");
        request.setStatus("APPROVED");
        request.setRequestType(RequestType.SERVICE);
        request.setRequestedBy(requestedBy);
        request.setDepartmentId(department.getId());
        return purchaseRequestRepository.save(request);
    }

    private Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + shortId());
        supplier.setName(name);
        supplier.setLegalName(name + " LLC");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private Department department(String prefix) {
        Department department = new Department();
        department.setCode(prefix + "-" + shortId());
        department.setName(prefix + " works department");
        department.setStatus("ACTIVE");
        return departmentRepository.save(department);
    }

    private Employee employee(String username, Department department) {
        Employee employee = new Employee();
        employee.setCode("EMP-" + shortId());
        employee.setName(username);
        employee.setStatus("ACTIVE");
        employee.setPersonnelNumber(username);
        employee.setDepartment(department);
        return employeeRepository.save(employee);
    }

    private void portalAccount(Supplier supplier, String login) {
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setCode("SPA-" + shortId());
        account.setName("Works supplier portal account");
        account.setStatus("ACTIVE");
        account.setSupplierId(supplier.getId());
        account.setLoginEmail(login);
        portalAccountRepository.save(account);
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record Origin(PurchaseOrder order, PurchaseOrderItem item, WorkCompletionActResponse act) {
    }
}
