package com.enterprise.mm.service.transfer;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.ApprovalAssigneeType;
import com.enterprise.mm.enums.ApprovalTaskStatus;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.ApprovalOperationSubmissionService;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"app.seed.enabled=false", "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate", "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false"})
@ActiveProfiles("test")
@EntityScan(basePackages = {"com.enterprise.mm.entity", "com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity", "com.enterprise.mm.common", "com.enterprise.mm.warehouse"})
@Testcontainers
class TransferSubmissionPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired TransferSubmissionService submissions;
    @Autowired TransferDocumentService documents;
    @Autowired ApprovalOperationSubmissionService operations;
    @Autowired ApprovalEngineService engine;
    @Autowired TransferDocumentRepository transfers;
    @Autowired TransferDocumentLineRepository lines;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    @Autowired EmployeeRepository employees;
    @Autowired ApprovalFlowRepository flows;
    @Autowired ApprovalFlowStepRepository steps;
    @Autowired ApprovalTaskRepository tasks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired UnitOfMeasureRepository units;
    @Autowired com.enterprise.mm.warehouse.history.IssueSourceDocumentService issueSources;
    @Autowired com.enterprise.mm.warehouse.history.IssueSourcePreparationService sourcePreparation;
    UUID transferId;

    @BeforeEach void setup() {
        TenantContext.setTenant("transfer-submit-" + UUID.randomUUID());
        CompanyContext.setCompany(UUID.randomUUID());
        authenticate("sender");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var source = new Warehouse(); source.setName("Source warehouse"); source = warehouses.save(source);
            var destination = new Warehouse(); destination.setName("Destination warehouse"); destination = warehouses.save(destination);
            var material = new Material(); material.setName("Bearing"); material = materials.save(material);
            var transfer = new TransferDocument(); transfer.setName("Warehouse transfer"); transfer.setStatus("DRAFT");
            transfer.setSourceWarehouseId(source.getId()); transfer.setDestinationWarehouseId(destination.getId());
            transferId = transfers.saveAndFlush(transfer).getId();
            var line = new TransferDocumentLine(); line.setName("Bearing"); line.setTransferDocumentId(transferId);
            line.setMaterialId(material.getId()); line.setPlannedQuantity(BigDecimal.TEN); lines.save(line);
        });
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void submitAutomaticallyAssignsTaskAndIndependentReviewerAppliesApproval() {
        configureFlow(true);
        var submitted = submissions.submit(transferId, "submit-once");
        assertThat(submitted.getStatus()).isEqualTo("SUBMITTED");
        var operation = operations.latest(TransferApproveOperationHandler.MODULE_KEY, transferId).orElseThrow();
        assertThat(operation.status()).isEqualTo("PENDING");
        assertThat(operation.requesterUsername()).isEqualTo("sender");
        var assigned = tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), operation.approvalInstanceId());
        assertThat(assigned).hasSize(1);
        var task = assigned.getFirst();
        assertThat(task.getAssigneeUsername()).isEqualTo("reviewer");
        assertThat(task.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        assertThatThrownBy(() -> engine.approveTask(task.getId(), "Self approval"))
            .hasMessageContaining("Requester cannot approve");
        authenticate("reviewer");
        engine.approveTask(task.getId(), "Reviewed");
        assertThat(documents.get(transferId).getStatus()).isEqualTo("APPROVED");
        assertThat(operations.latest(TransferApproveOperationHandler.MODULE_KEY, transferId).orElseThrow().status())
            .isEqualTo("APPLIED");
    }

    @Test void retryDoesNotCreateAnotherOperationOrTask() {
        configureFlow(true);
        var first = submissions.submit(transferId, "same-key");
        var replay = submissions.submit(transferId, "same-key");
        assertThat(replay.getVersion()).isEqualTo(first.getVersion());
        assertThat(count("approval_operation_requests")).isEqualTo(1);
        assertThat(count("approval_tasks")).isEqualTo(1);
        assertThatThrownBy(() -> submissions.submit(transferId, "different-key")).hasMessageContaining("unavailable");
        assertThat(count("approval_tasks")).isEqualTo(1);
    }

    @Test void reviewedOneCRequestCreatesAnAtomicDraftAndUsesTheNormalApprovalWorkflow() {
        var original = documents.get(transferId);
        UUID materialId = original.getLines().getFirst().getMaterialId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var unit = new UnitOfMeasure(); unit.setName("Kilogram"); unit.setCode("1C-UOM-review-unit");
            unit.setMeasurementNameShort("kg"); unit.setStockQuantityScale(7); unit = units.saveAndFlush(unit);
            var material = materials.findById(materialId).orElseThrow(); material.setUnitOfMeasureId(unit.getId()); materials.saveAndFlush(material);
        });
        UUID run = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("""
            insert into warehouse_history_runs(id,tenant_id,company_id,manifest_sha256,organization_source_id,expected_counts,expected_records,expected_chain,chain_sha256,status,completed_at)
            values (?,?,?,'review-manifest','org','{}',2,'hash','hash','COMPLETED',current_timestamp)
            """, run, TenantContext.currentTenant(), CompanyContext.currentCompany());
        jdbc.update("""
            insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,document_type,document_number,warehouse_id,posted,marked,row_sha256,content)
            values (?,?,?,?,1,'DOCUMENT',?,'_document4470',?,'Требование номенклатуры','REQ-1C',?,false,false,'header','{}')
            """, source, TenantContext.currentTenant(), CompanyContext.currentCompany(), run, source.toString(), source.toString(), original.getSourceWarehouseId());
        jdbc.update("""
            insert into warehouse_history_records(id,tenant_id,company_id,import_id,source_seq,kind,source_key,source_table,document_key,material_id,warehouse_id,target_warehouse_id,quantity,row_sha256,content)
            values (?,?,?,?,2,'LINE','review-line','_document4470_vt4512',?,?,?,?,'2.0000001','line',
                '{"lineNumber":"1","data":{"_fld4517rref":"review-unit","_fld4518":"1","_fld4527":"R-1C"}}')
            """, UUID.randomUUID(), TenantContext.currentTenant(), CompanyContext.currentCompany(), run, source.toString(), materialId, original.getSourceWarehouseId(), original.getDestinationWarehouseId());
        var detail = issueSources.detail(source, original.getSourceWarehouseId());
        assertThat(detail.canPrepare()).isTrue();
        var input = new com.enterprise.mm.warehouse.history.IssueSourcePreparationService.Request(original.getSourceWarehouseId(), detail.fingerprint(), true, "Checked current ledger");
        var draft = sourcePreparation.prepare(source, input, "reviewed-once");
        assertThat(sourcePreparation.prepare(source, input, "reviewed-once")).isEqualTo(draft);
        var created = documents.get(draft.documentId());
        assertThat(created.getStatus()).isEqualTo("DRAFT");
        assertThat(created.getLines().getFirst().getPlannedQuantity()).isEqualByComparingTo("2.0000001");
        assertThat(created.getSourceWarehouseId()).isEqualTo(original.getSourceWarehouseId());
        assertThat(created.getDestinationWarehouseId()).isEqualTo(original.getDestinationWarehouseId());
        assertThat(count("warehouse_issue_source_links")).isEqualTo(1);
        assertThat(count("stock_movements")).isZero();
        assertThat(count("approval_tasks")).isZero();
        var edit = new com.enterprise.mm.payload.TransferEditRequest(); edit.setExpectedVersion(created.getVersion());
        edit.setSourceWarehouseId(created.getSourceWarehouseId()); edit.setDestinationWarehouseId(created.getDestinationWarehouseId());
        var excessive = new com.enterprise.mm.payload.TransferCreateRequest.Line(); excessive.setMaterialId(materialId); excessive.setPlannedQuantity(new BigDecimal("2.0000002")); edit.setLines(List.of(excessive));
        assertThatThrownBy(() -> documents.edit(created.getId(), edit)).hasMessageContaining("cannot exceed the reviewed 1C request");
        configureFlow(true);
        assertThat(submissions.submit(created.getId(), "normal-submit").getStatus()).isEqualTo("SUBMITTED");
        var operation = operations.latest(TransferApproveOperationHandler.MODULE_KEY, created.getId()).orElseThrow();
        var task = tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(TenantContext.currentTenant(), operation.approvalInstanceId()).getFirst();
        authenticate("reviewer"); engine.approveTask(task.getId(), "Reviewed source and draft");
        assertThat(documents.get(created.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(count("stock_movements")).isZero();
        authenticate("auditor", "AUDITOR");
        assertThatThrownBy(() -> sourcePreparation.prepare(source, input, "reviewed-once")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void assignedSecondReviewerCanReadTransferOutsideTheirWarehouse(boolean roleAssignment) {
        UUID taskId = prepareSecondReviewer(roleAssignment);
        assertThatThrownBy(() -> documents.get(transferId)).hasMessageContaining("Transfer document not found");
        var detail = engine.myTaskDetail(taskId);
        assertThat(detail.sourceLines().status()).isEqualTo("READY");
        assertThat(detail.sourceLines().items()).anySatisfy(item ->
            assertThat((BigDecimal) item.values().get("plannedQuantity")).isEqualByComparingTo(BigDecimal.TEN));

        authenticate("unassigned", "WAREHOUSE_KEEPER");
        assertThatThrownBy(() -> engine.myTaskDetail(taskId)).hasMessageContaining("Approval task not found");
        authenticate("second-reviewer", "WAREHOUSE_MANAGER");
        UUID companyId = CompanyContext.currentCompany();
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(() -> engine.myTaskDetail(taskId)).hasMessageContaining("Approval task not found");
        CompanyContext.setCompany(companyId);
        TenantContext.setTenant("another-tenant");
        assertThatThrownBy(() -> engine.myTaskDetail(taskId)).hasMessageContaining("Approval task not found");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void assignedSecondReviewerAppliesApprovalOutsideTheirWarehouse(boolean roleAssignment) {
        UUID taskId = prepareSecondReviewer(roleAssignment);
        engine.approveTask(taskId, "Second review complete");
        assertThatThrownBy(() -> documents.get(transferId)).hasMessageContaining("Transfer document not found");
        authenticate("sender");
        assertThat(documents.get(transferId).getStatus()).isEqualTo("APPROVED");
        assertThat(operations.latest(TransferApproveOperationHandler.MODULE_KEY, transferId).orElseThrow().status())
            .isEqualTo("APPLIED");
    }

    private UUID prepareSecondReviewer(boolean roleAssignment) {
        UUID flowId = configureFlow("reviewer", "second-reviewer");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            if (roleAssignment) {
                var second = steps.findByTenantIdAndApprovalFlowIdAndIsDeletedFalseOrderByStepOrder(
                    TenantContext.currentTenant(), flowId).getLast();
                second.setAssigneeType(ApprovalAssigneeType.ROLE); second.setAssigneeRole("WAREHOUSE_MANAGER");
                second.setAssigneeUsername(null); steps.save(second);
            }
            var warehouse = new Warehouse(); warehouse.setName("Unrelated warehouse"); warehouse = warehouses.save(warehouse);
            var employee = new Employee(); employee.setName("Second reviewer");
            employee.setPersonnelNumber("second-reviewer"); employee.setWarehouse(warehouse); employees.save(employee);
        });
        submissions.submit(transferId, "two-reviewers");
        var operation = operations.latest(TransferApproveOperationHandler.MODULE_KEY, transferId).orElseThrow();
        var assigned = tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), operation.approvalInstanceId());
        authenticate("reviewer");
        engine.approveTask(assigned.getFirst().getId(), "First review complete");
        var second = tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(
            TenantContext.currentTenant(), operation.approvalInstanceId()).stream()
            .filter(task -> task.getStepOrder() == 2).findFirst().orElseThrow();
        assertThat(second.getTaskStatus()).isEqualTo(ApprovalTaskStatus.PENDING);
        authenticate("second-reviewer", "WAREHOUSE_MANAGER");
        return second.getId();
    }

    @Test void missingFlowRollsBackSubmissionAndOperation() {
        assertThatThrownBy(() -> submissions.submit(transferId, "no-flow")).hasMessageContaining("No active approval flow");
        assertUnsubmitted();
    }

    @Test void flowWithoutStepsRollsBackAllCreatedApprovalRecords() {
        configureFlow(false);
        assertThatThrownBy(() -> submissions.submit(transferId, "empty-flow")).hasMessageContaining("no steps");
        assertUnsubmitted();
    }

    @Test void missingIdempotencyKeyCannotChangeTheDocument() {
        configureFlow(true);
        assertThatThrownBy(() -> submissions.submit(transferId, " ")).hasMessageContaining("Idempotency-Key");
        assertUnsubmitted();
    }

    private void assertUnsubmitted() {
        assertThat(documents.get(transferId).getStatus()).isEqualTo("DRAFT");
        assertThat(count("approval_operation_requests")).isZero();
        assertThat(count("approval_instances")).isZero();
        assertThat(count("approval_tasks")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, TenantContext.currentTenant());
    }
    private void configureFlow(boolean withStep) {
        configureFlow(withStep ? new String[]{"reviewer"} : new String[]{});
    }
    private UUID configureFlow(String... reviewers) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var flow = new ApprovalFlow(); flow.setName("Transfer approval");
            flow.setModuleKey(TransferApproveOperationHandler.MODULE_KEY); flow = flows.saveAndFlush(flow);
            for (int index = 0; index < reviewers.length; index++) {
                var step = new ApprovalFlowStep(); step.setName("Review transfer"); step.setApprovalFlowId(flow.getId());
                step.setStepOrder(index + 1); step.setAssigneeType(ApprovalAssigneeType.USER); step.setAssigneeUsername(reviewers[index]);
                step.setApprover(true); steps.save(step);
            }
            return flow.getId();
        });
    }
    private void authenticate(String username) {
        authenticate(username, "ADMIN");
    }
    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
