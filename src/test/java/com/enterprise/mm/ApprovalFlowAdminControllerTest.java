package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.approval.PurchaseRequestApprovalAdapter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ApprovalFlowAdminControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired UserRepository userRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ensureDefaultCompanyExists() {
        jdbc.update("""
            insert into companies (id, tenant_id, company_id, created_at, version, is_delete, name,
                status, non_moving_stock_threshold_months, non_moving_stock_auto_detect_enabled)
            select ?, 'default', ?, current_timestamp, 0, false, 'Default test company', 'ACTIVE', 12, false
            where not exists (select 1 from companies where id = ?)
            """, CompanyContext.DEFAULT_COMPANY_ID, CompanyContext.DEFAULT_COMPANY_ID,
            CompanyContext.DEFAULT_COMPANY_ID);
    }

    @Test
    void purchaseRequestApprovalAdapterUsesAdminModuleKey() {
        assertThat(PurchaseRequestApprovalAdapter.MODULE_KEY).isEqualTo("PURCHASE_REQUEST");
    }

    @Test
    void selectingAndMovingFinalApproverKeepsOnlyLastStepFinal() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String flowPayload = """
            {
              "companyId": "%s", "code": "FLOW-FINAL-%s", "name": "Final step flow %s",
              "moduleKey": "PURCHASE_REQUEST", "requestType": "GOODS", "status": "ACTIVE",
              "steps": [
                {"code":"STEP-FINAL-%s-1", "name":"First", "stepOrder":1,
                 "assigneeType":"ROLE", "assigneeRole":"PROCUREMENT_SPECIALIST", "isApprover":false},
                {"code":"STEP-FINAL-%s-2", "name":"Second", "stepOrder":2,
                 "assigneeType":"ROLE", "assigneeRole":"PROCUREMENT_MANAGER", "isApprover":true}
              ]
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix, suffix, suffix, suffix);
        String created = mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON).content(flowPayload))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String flowId = JsonPath.read(created, "$.data.id");
        String firstId = JsonPath.read(created, "$.data.steps[0].id");
        String secondId = JsonPath.read(created, "$.data.steps[1].id");

        String added = mockMvc.perform(post("/api/approval-flows/{id}/steps", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"STEP-FINAL-%s-3", "name":"Third", "stepOrder":3,
                     "assigneeType":"ROLE", "assigneeRole":"PROCUREMENT_MANAGER", "isApprover":true}
                    """.formatted(suffix)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String thirdId = JsonPath.read(added, "$.data.id");
        mockMvc.perform(get("/api/approval-flows/{id}", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[1].isApprover").value(false))
            .andExpect(jsonPath("$.data.steps[2].isApprover").value(true));

        mockMvc.perform(put("/api/approval-flows/{id}/steps/order", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\"%s\",\"%s\",\"%s\"]".formatted(firstId, thirdId, secondId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[1].isApprover").value(false))
            .andExpect(jsonPath("$.data[2].id").value(secondId))
            .andExpect(jsonPath("$.data[2].isApprover").value(true));

        mockMvc.perform(put("/api/approval-flow-steps/{id}", firstId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"STEP-FINAL-%s-1", "name":"First", "stepOrder":1,
                     "assigneeType":"ROLE", "assigneeRole":"PROCUREMENT_SPECIALIST", "isApprover":true}
                    """.formatted(suffix)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/approval-flows/{id}", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps[0].isApprover").value(false))
            .andExpect(jsonPath("$.data.steps[1].isApprover").value(false))
            .andExpect(jsonPath("$.data.steps[2].id").value(firstId))
            .andExpect(jsonPath("$.data.steps[2].isApprover").value(true));

        mockMvc.perform(delete("/api/approval-flow-steps/{id}", secondId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/approval-flows/{id}", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].stepOrder").value(1))
            .andExpect(jsonPath("$.data.steps[1].stepOrder").value(2))
            .andExpect(jsonPath("$.data.steps[1].id").value(firstId))
            .andExpect(jsonPath("$.data.steps[1].isApprover").value(true));
        assertThat(jdbc.queryForObject(
            "select step_order from approval_flow_steps where id = ?", Integer.class, UUID.fromString(secondId)))
            .isNegative();

        String fourth = mockMvc.perform(post("/api/approval-flows/{id}/steps", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"STEP-FINAL-%s-4", "name":"Fourth", "stepOrder":3,
                     "assigneeType":"ROLE", "assigneeRole":"PROCUREMENT_MANAGER", "isApprover":true}
                    """.formatted(suffix)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.stepOrder").value(3))
            .andReturn().getResponse().getContentAsString();
        String fourthId = JsonPath.read(fourth, "$.data.id");
        mockMvc.perform(delete("/api/approval-flow-steps/{id}", fourthId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/approval-flows/{id}", flowId)
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[1].stepOrder").value(2))
            .andExpect(jsonPath("$.data.steps[1].id").value(firstId))
            .andExpect(jsonPath("$.data.steps[1].isApprover").value(true));
    }

    @Test
    void departmentHeadCanReachSeededApprovalTaskActionEndpoint() throws Exception {
        mockMvc.perform(post("/api/approval-tasks/{id}/approve", UUID.randomUUID())
                .with(user("department-head").roles("DEPARTMENT_HEAD"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void procurementSpecialistCannotMutateApprovalFlowConfiguration() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String flowPayload = """
            {
              "companyId": "%s",
              "code": "FLOW-DENIED-%s",
              "name": "Denied approval flow %s",
              "moduleKey": "PURCHASE_REQUEST",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE"
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix, suffix);
        String stepPayload = """
            {
              "code": "STEP-DENIED-%s",
              "name": "Denied approval step %s",
              "stepOrder": 1,
              "assigneeType": "ROLE",
              "assigneeRole": "PROCUREMENT_MANAGER"
            }
            """.formatted(suffix, suffix);
        UUID flowId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();

        mockMvc.perform(post("/api/approval-flows")
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(flowPayload))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/approval-flows/{id}", flowId)
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(flowPayload))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/approval-flows/{id}", flowId)
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/approval-flows/{id}/steps", flowId)
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(stepPayload))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/approval-flow-steps/{id}", stepId)
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(stepPayload))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/approval-flow-steps/{id}", stepId)
                .with(user("specialist").roles("PROCUREMENT_SPECIALIST"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(status().isForbidden());
    }

    @Test
    void createApprovalFlowAcceptsInlineSteps() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Warehouse warehouse = new Warehouse();
        warehouse.setCode("AF-STOCK-" + suffix);
        warehouse.setName("Approval stock warehouse " + suffix);
        warehouse.setStatus("ACTIVE");
        warehouse = warehouseRepository.save(warehouse);
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-%s",
              "name": "Inline approval flow %s",
              "moduleKey": "DEMAND_PLAN",
              "requestType": "GOODS",
              "active": true,
              "status": "DRAFT",
              "steps": [
                {
                  "code": "STEP-%s-1",
                  "name": "Manager review",
                  "stepOrder": 1,
                  "stepType": "DEMAND_STOCK_CHECK",
                  "warehouseId": "%s",
                  "assigneeType": "ROLE",
                  "assigneeRole": "PROCUREMENT_MANAGER",
                  "canReturn": false,
                  "isApprover": false,
                  "commentRequired": true
                },
                {
                  "code": "STEP-%s-2",
                  "name": "Finance review",
                  "stepOrder": 2,
                  "stepType": "STANDARD",
                  "assigneeType": "USER",
                  "assigneeUsername": "finance.approver",
                  "canReturn": true,
                  "isApprover": true,
                  "commentRequired": false
                }
              ]
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix, suffix, suffix, warehouse.getId(), suffix);

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.companyId").value(CompanyContext.DEFAULT_COMPANY_ID.toString()))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
            .andExpect(jsonPath("$.data.steps.length()").value(2))
            .andExpect(jsonPath("$.data.steps[0].stepOrder").value(1))
            .andExpect(jsonPath("$.data.steps[0].stepType").value("DEMAND_STOCK_CHECK"))
            .andExpect(jsonPath("$.data.steps[0].warehouseId").value(warehouse.getId().toString()))
            .andExpect(jsonPath("$.data.steps[0].assigneeRole").value("PROCUREMENT_MANAGER"))
            .andExpect(jsonPath("$.data.steps[1].stepType").value("STANDARD"))
            .andExpect(jsonPath("$.data.steps[1].commentRequired").value(false))
            .andExpect(jsonPath("$.data.steps[1].assigneeUsername").value("finance.approver"));
    }

    @Test
    void hierarchyTemplateBuildsStepsFromDepartmentParentChain() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Department parent = new Department();
        parent.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        parent.setCode("AF-PARENT-" + suffix);
        parent.setName("Approval parent department " + suffix);
        parent.setStatus("ACTIVE");
        parent = departmentRepository.save(parent);

        Department child = new Department();
        child.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        child.setCode("AF-CHILD-" + suffix);
        child.setName("Approval child department " + suffix);
        child.setStatus("ACTIVE");
        child.setParentDepartment(parent);
        child = departmentRepository.saveAndFlush(child);

        mockMvc.perform(get("/api/approval-flows/steps/hierarchy-template")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("departmentId", child.getId().toString())
                .param("moduleKey", "PURCHASE_REQUEST")
                .param("requestType", "GOODS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.departmentId").value(child.getId().toString()))
            .andExpect(jsonPath("$.data.steps.length()").value(3))
            .andExpect(jsonPath("$.data.steps[0].stepOrder").value(1))
            .andExpect(jsonPath("$.data.steps[0].departmentId").value(child.getId().toString()))
            .andExpect(jsonPath("$.data.steps[0].assigneeRole").value("DEPARTMENT_HEAD"))
            .andExpect(jsonPath("$.data.steps[0].isApprover").value(false))
            .andExpect(jsonPath("$.data.steps[1].departmentId").value(parent.getId().toString()))
            .andExpect(jsonPath("$.data.steps[1].assigneeRole").value("DEPARTMENT_HEAD"))
            .andExpect(jsonPath("$.data.steps[2].assigneeRole").value("PROCUREMENT_MANAGER"))
            .andExpect(jsonPath("$.data.steps[2].isApprover").value(true))
            .andExpect(jsonPath("$.data.steps[2].canReturn").value(true));
    }

    @Test
    void createApprovalFlowResolvesUsernameWhenUserStepIsSubmittedById() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        User approver = new User();
        approver.setCode("USR-" + suffix);
        approver.setName("Approval user " + suffix);
        approver.setStatus("ACTIVE");
        approver.setUsername("named.approver." + suffix.toLowerCase());
        approver.setFullName("Named Approver " + suffix);
        approver.setPasswordHash("test");
        approver.setEnabled(true);
        approver = userRepository.save(approver);
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-USER-ID-%s",
              "name": "User ID approval flow %s",
              "moduleKey": "PURCHASE_REQUEST",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE",
              "steps": [
                {
                  "code": "STEP-USER-ID-%s-1",
                  "name": "Executor check",
                  "stepOrder": 1,
                  "assigneeType": "ROLE",
                  "assigneeRole": "PROCUREMENT_SPECIALIST",
                  "isApprover": false
                },
                {
                  "code": "STEP-USER-ID-%s-2",
                  "name": "Named approver review",
                  "stepOrder": 2,
                  "assigneeType": "USER",
                  "assigneeUserId": "%s",
                  "isApprover": true
                }
              ]
            }
            """.formatted(
                CompanyContext.DEFAULT_COMPANY_ID,
                suffix,
                suffix,
                suffix,
                suffix,
                approver.getId()
            );

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.steps[1].assigneeUserId").value(approver.getId().toString()))
            .andExpect(jsonPath("$.data.steps[1].assigneeUsername").value(approver.getFullName()));
    }

    @Test
    void createApprovalFlowDefaultsStepCommentsToRequired() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-DEFAULT-COMMENT-%s",
              "name": "Default comment approval flow %s",
              "moduleKey": "PURCHASE_REQUEST",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE",
              "steps": [
                {
                  "code": "STEP-DEFAULT-COMMENT-%s-1",
                  "name": "Executor review",
                  "stepOrder": 1,
                  "assigneeType": "ROLE",
                  "assigneeRole": "PROCUREMENT_SPECIALIST",
                  "isApprover": false
                },
                {
                  "code": "STEP-DEFAULT-COMMENT-%s-2",
                  "name": "Manager review",
                  "stepOrder": 2,
                  "assigneeType": "ROLE",
                  "assigneeRole": "PROCUREMENT_MANAGER",
                  "isApprover": true
                }
              ]
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix, suffix, suffix, suffix);

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.steps[0].commentRequired").value(true));
    }

    @Test
    void createParallelAllApprovalFlowAcceptsInlineAssigneesWithoutSteps() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-PARALLEL-%s",
              "name": "Parallel approval flow %s",
              "moduleKey": "PURCHASE_REQUEST",
              "flowType": "PARALLEL_ALL",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE",
              "parallelAssignees": [
                {
                  "assigneeUsername": "parallel.one.%s"
                },
                {
                  "assigneeUsername": "parallel.two.%s"
                }
              ]
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix, suffix, suffix.toLowerCase(), suffix.toLowerCase());

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.flowType").value("PARALLEL_ALL"))
            .andExpect(jsonPath("$.data.steps.length()").value(0))
            .andExpect(jsonPath("$.data.parallelAssignees.length()").value(2))
            .andExpect(jsonPath("$.data.parallelAssignees[0].assigneeUsername").value("parallel.one." + suffix.toLowerCase()))
            .andExpect(jsonPath("$.data.parallelAssignees[1].assigneeUsername").value("parallel.two." + suffix.toLowerCase()));
    }

    @Test
    void createParallelThenFinalFlowRequiresAgreementParticipantsAndOneFinalApprover() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-HYBRID-%s",
              "name": "Warehouse agreement and approval %s",
              "moduleKey": "STOCK_MOVEMENT",
              "flowType": "PARALLEL_THEN_FINAL",
              "active": true,
              "status": "ACTIVE",
              "parallelAssignees": [
                { "assigneeUsername": "agreement.one.%s" },
                { "assigneeUsername": "agreement.two.%s" }
              ],
              "steps": [{
                "code": "FINAL-%s",
                "name": "TASDIQLOVCHI",
                "stepOrder": 2,
                "stepType": "STANDARD",
                "assigneeType": "USER",
                "assigneeUsername": "final.approver.%s",
                "canReturn": false,
                "isApprover": true,
                "commentRequired": true
              }]
            }
            """.formatted(
                CompanyContext.DEFAULT_COMPANY_ID, suffix.toUpperCase(), suffix,
                suffix, suffix, suffix.toUpperCase(), suffix);

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "WAREHOUSE_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.flowType").value("PARALLEL_THEN_FINAL"))
            .andExpect(jsonPath("$.data.parallelAssignees.length()").value(2))
            .andExpect(jsonPath("$.data.steps.length()").value(1))
            .andExpect(jsonPath("$.data.steps[0].stepOrder").value(2))
            .andExpect(jsonPath("$.data.steps[0].isApprover").value(true))
            .andExpect(jsonPath("$.data.steps[0].canReturn").value(false))
            .andExpect(jsonPath("$.data.steps[0].name").value("TASDIQLOVCHI"));
    }

    @Test
    void rejectParallelThenFinalFlowWithoutFinalApprover() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-HYBRID-INVALID-%s",
              "name": "Invalid warehouse approval %s",
              "moduleKey": "STOCK_MOVEMENT",
              "flowType": "PARALLEL_THEN_FINAL",
              "active": true,
              "parallelAssignees": [{ "assigneeUsername": "agreement.%s" }],
              "steps": []
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, suffix.toUpperCase(), suffix, suffix);

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "WAREHOUSE_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createParallelAllApprovalFlowResolvesAssigneesByUserId() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toLowerCase();
        User first = new User();
        first.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        first.setUsername("parallel.uid.one." + suffix);
        first.setPasswordHash("hash");
        first.setFullName("Parallel One");
        first.setEnabled(true);
        first.setCode("USR-P1-" + suffix);
        first.setName("Parallel One");
        first.setStatus("ACTIVE");
        first = userRepository.saveAndFlush(first);

        User second = new User();
        second.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        second.setUsername("parallel.uid.two." + suffix);
        second.setPasswordHash("hash");
        second.setFullName("Parallel Two");
        second.setEnabled(true);
        second.setCode("USR-P2-" + suffix);
        second.setName("Parallel Two");
        second.setStatus("ACTIVE");
        second = userRepository.saveAndFlush(second);

        String payload = """
            {
              "companyId": "%s",
              "code": "FLOW-PARALLEL-UID-%s",
              "name": "Parallel approval by userId %s",
              "moduleKey": "PURCHASE_REQUEST",
              "flowType": "PARALLEL_ALL",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE",
              "parallelAssignees": [
                { "assigneeUserId": "%s" },
                { "assigneeUserId": "%s" }
              ]
            }
            """.formatted(
                CompanyContext.DEFAULT_COMPANY_ID,
                suffix.toUpperCase(),
                suffix,
                first.getId(),
                second.getId()
            );

        var created = mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.flowType").value("PARALLEL_ALL"))
            .andExpect(jsonPath("$.data.parallelAssignees.length()").value(2))
            .andExpect(jsonPath("$.data.parallelAssignees[0].assigneeUsername").value("Parallel One"))
            .andExpect(jsonPath("$.data.parallelAssignees[1].assigneeUsername").value("Parallel Two"))
            .andExpect(jsonPath("$.data.parallelAssignees[0].assigneeUserId").value(first.getId().toString()))
            .andExpect(jsonPath("$.data.parallelAssignees[1].assigneeUserId").value(second.getId().toString()))
            .andReturn();

        String flowId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        String updatePayload = """
            {
              "companyId": "%s",
              "code": "FLOW-PARALLEL-UID-%s",
              "name": "Parallel approval by userId updated %s",
              "moduleKey": "PURCHASE_REQUEST",
              "flowType": "PARALLEL_ALL",
              "requestType": "GOODS",
              "active": true,
              "status": "ACTIVE",
              "parallelAssignees": [
                { "assigneeUserId": "%s" },
                { "assigneeUserId": "%s" }
              ]
            }
            """.formatted(
                CompanyContext.DEFAULT_COMPANY_ID,
                suffix.toUpperCase(),
                suffix,
                first.getId(),
                second.getId()
            );

        mockMvc.perform(put("/api/approval-flows/{id}", flowId)
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.flowType").value("PARALLEL_ALL"))
            .andExpect(jsonPath("$.data.parallelAssignees.length()").value(2))
            .andExpect(jsonPath("$.data.name").value("Parallel approval by userId updated " + suffix));
    }

    @Test
    void searchApprovalFlowsSupportsConstrainedSorting() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        createFlow("FLOW-" + suffix + "-A", "A " + suffix);
        createFlow("FLOW-" + suffix + "-Z", "Z " + suffix);

        mockMvc.perform(get("/api/approval-flows")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("search", suffix)
                .param("sort", "name,asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].name").value("A " + suffix));

        mockMvc.perform(get("/api/approval-flows")
                .with(user("admin").roles("ADMIN"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .param("sort", "unsupported,asc"))
            .andExpect(status().isOk());
    }

    private void createFlow(String code, String name) throws Exception {
        String payload = """
            {
              "companyId": "%s",
              "code": "%s",
              "name": "%s",
              "moduleKey": "PURCHASE_REQUEST",
              "active": true,
              "status": "ACTIVE"
            }
            """.formatted(CompanyContext.DEFAULT_COMPANY_ID, code, name);

        mockMvc.perform(post("/api/approval-flows")
                .with(user("admin").roles("ADMIN", "PROCUREMENT_MANAGER"))
                .header("X-Company-Id", CompanyContext.DEFAULT_COMPANY_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated());
    }
}
