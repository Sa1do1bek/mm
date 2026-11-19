package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.warehouse.production.ProductionOperationDtos.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Committed native stock, migrations, exact costing, genealogy and retry/concurrency behavior. */
@SpringBootTest(properties={"app.seed.enabled=false", "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
    "app.firebase.enabled=false", "mm.integrations.cbu.enabled=false", "mm.pilot.enabled-capabilities=SERIALIZED_EXECUTION", "app.warehouse.transfers.shipment-flow-enabled=true"})
@ActiveProfiles("test")
@EntityScan(basePackages={"com.enterprise.mm.entity","com.enterprise.mm.procurement.command.entity",
    "com.enterprise.mm.supply.workspace.entity","com.enterprise.mm.common","com.enterprise.mm.warehouse"})
@Testcontainers
class ProductionOperationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired ProductionOperationService production;
    @Autowired ProductionHistoryService history;
    @Autowired StockMovementService stock;
    @Autowired CompanyRepository companies;
    @Autowired WarehouseRepository warehouses;
    @Autowired MaterialRepository materials;
    @Autowired MaterialGroupRepository groups;
    @Autowired UnitOfMeasureRepository units;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferDocumentService transfers;
    @Autowired QualityInspectionService quality;
    @Autowired LaboratoryTestService laboratory;
    @Autowired com.enterprise.mm.quality.workspace.service.IncomingQualityWorkspaceService qualityWorkspace;
    @Autowired MaterialUnitRepository physicalUnits;
    UUID company, warehouse, group;
    UnitOfMeasure kilograms, pieces;
    Material raw, bag, packaged;

    @BeforeEach void setup() {
        authenticate();
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        Company c = new Company(); c.setCode("PROD-C-"+UUID.randomUUID().toString().substring(0,8)); c.setName("Production test"); c.setStatus("ACTIVE");
        company = companies.saveAndFlush(c).getId(); CompanyContext.setCompany(company);
        Warehouse w = new Warehouse(); w.setName("Bag workshop"); w.setStatus("ACTIVE"); warehouse=warehouses.saveAndFlush(w).getId();
        MaterialGroup g = new MaterialGroup(); g.setName("Production materials"); g.setStatus("ACTIVE"); group=groups.saveAndFlush(g).getId();
        kilograms=unit("KG",7); pieces=unit("EA",0);
        raw=material("Raw film",kilograms); bag=material("Empty bag",pieces); packaged=material("Packaged product",pieces);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); CompanyContext.clear(); }

    @Test void twoStagesPreserveUnitsExactValuesAndComponentHistory() {
        var receipt=receive(raw,"10","1");
        Operation first=production.create(draft("BAGS",raw,position(receipt.getStockBatchId()),"1",bag,"3","0"),"create-bags");
        first=production.post(first.id(),first.version(),"post-bags");
        assertThat(first.inputCost()).isEqualByComparingTo("1");
        assertThat(first.outputCost()).isEqualByComparingTo("1");
        Line output=first.lines().getLast();
        assertThat(first.lines().getFirst().unitCode()).contains("KG");
        assertThat(output.unitCode()).contains("EA");
        assertValue("stock_balances","stock_batch_id",output.stockBatchId(),"total_value","1");
        assertValue("inventory_cost_layers","source_movement_id",output.movementId(),"total_cost","1");
        assertValue("stock_movements","id",output.movementId(),"total_cost","1");
        Operation second=production.create(draft("PACK",bag,position(output.stockBatchId()),"3",packaged,"3","0.5"),"create-pack");
        second=production.post(second.id(),second.version(),"post-pack");
        assertThat(second.outputCost()).isEqualByComparingTo("1.5");
        History lineage=history.material(packaged.getId(),warehouse,second.lines().getLast().stockBatchId());
        assertThat(lineage.truncated()).isFalse();
        assertThat(lineage.nodes()).extracting(HistoryNode::materialId).contains(raw.getId(),bag.getId(),packaged.getId());
        assertThat(lineage.nodes()).extracting(HistoryNode::movementId).contains(receipt.getId(),output.movementId());
        Operation finalFirst=first;
        assertThatThrownBy(()->production.reverse(finalFirst.id(),new ReversalCommand(finalFirst.version(),"Already used"),"reverse-used"))
            .hasMessageContaining("negative");
        assertThat(production.get(first.id()).status()).isEqualTo("POSTED");
        second=production.reverse(second.id(),new ReversalCommand(second.version(),"Undo packaging"),"reverse-pack");
        assertThat(second.status()).isEqualTo("REVERSED");
        first=production.reverse(first.id(),new ReversalCommand(first.version(),"Undo bags"),"reverse-bags");
        assertThat(first.status()).isEqualTo("REVERSED");
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","10");
    }

    @Test void duplicateRequestsNeverCreateOrConsumeStockTwiceAndConflictingKeysFail() {
        var receipt=receive(raw,"10","2");
        Draft draft=draft("ONCE",raw,position(receipt.getStockBatchId()),"2",bag,"5","0");
        Operation first=production.create(draft,"same-create");
        assertThat(production.create(draft,"same-create").id()).isEqualTo(first.id());
        Operation posted=production.post(first.id(),first.version(),"same-post");
        assertThat(production.post(first.id(),first.version(),"same-post").id()).isEqualTo(posted.id());
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where reference_type='PRODUCTION_OPERATION' and reference_id=?",Integer.class,first.id())).isEqualTo(2);
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","8");
        assertThatThrownBy(()->production.post(first.id(),999,"same-post")).hasMessageContaining("different request");
        assertThatThrownBy(()->production.post(first.id(),0,"other-key")).hasMessageContaining("changed");
    }

    @Test void failureOnOutputRollsBackInputStockMovementsCostAndDocumentLines() {
        var receipt=receive(raw,"10","2");
        Operation draft=production.create(draft("ROLLBACK",raw,position(receipt.getStockBatchId()),"2",bag,"3","0"),"create-rollback");
        // A database failure occurs after the input has already been consumed in the same transaction.
        jdbc.execute("alter table warehouse_production_operation_lines add constraint production_test_failure check(operation_id<>'"+draft.id()+"' or line_type='INPUT')");
        try {
            assertThatThrownBy(()->production.post(draft.id(),draft.version(),"post-rollback")).isInstanceOf(RuntimeException.class);
            assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","10");
            assertValue("inventory_cost_layers","source_movement_id",receipt.getId(),"total_cost","20");
            assertThat(production.get(draft.id()).status()).isEqualTo("DRAFT");
            assertThat(production.get(draft.id()).lines()).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from stock_movements where reference_id=?",Integer.class,draft.id())).isZero();
        } finally { jdbc.execute("alter table warehouse_production_operation_lines drop constraint production_test_failure"); }
        assertThat(production.post(draft.id(),draft.version(),"post-rollback").status()).isEqualTo("POSTED");
    }

    @Test void insufficientAndForeignStockCannotBeConsumed() {
        var receipt=receive(raw,"1","10");
        UUID position=position(receipt.getStockBatchId());
        Operation draft=production.create(draft("SHORT",raw,position,"2",bag,"1","0"),"short");
        assertThatThrownBy(()->production.post(draft.id(),0,"post-short")).hasMessageContaining("unavailable");
        UUID originalCompany=company;
        CompanyContext.setCompany(UUID.randomUUID());
        assertThatThrownBy(()->production.get(draft.id())).hasMessageContaining("not found");
        CompanyContext.setCompany(originalCompany);
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","1");
    }

    @Test void assemblyConsumesSeveralComponentsAndPreservesBreakdown() {
        Material keyboard=material("Keyboard",pieces), mouse=material("Mouse",pieces), computer=material("Computer set",pieces);
        var a=receive(keyboard,"2","100");
        var b=receive(mouse,"2","50");
        Draft draft=new Draft("COMPUTER",OperationType.KITTING,warehouse,LocalDate.now(),null,"Computer kit",BigDecimal.ZERO,null,
            List.of(new Input(keyboard.getId(),position(a.getStockBatchId()),BigDecimal.ONE),new Input(mouse.getId(),position(b.getStockBatchId()),BigDecimal.ONE)),
            List.of(new Output(computer.getId(),BigDecimal.ONE,BigDecimal.ONE,"COMPUTER-001","OUTPUT")));
        Operation operation=production.create(draft,"computer-create"); operation=production.post(operation.id(),0,"computer-post");
        assertThat(operation.outputCost()).isEqualByComparingTo("150");
        assertThat(history.material(computer.getId(),warehouse,operation.lines().getLast().stockBatchId()).nodes())
            .extracting(HistoryNode::materialId).contains(keyboard.getId(),mouse.getId(),computer.getId());
    }

    @Test void concurrentPostsProduceOneSetOfStockEffects() throws Exception {
        var receipt=receive(raw,"10","1");
        Operation operation=production.create(draft("CONCURRENT",raw,position(receipt.getStockBatchId()),"1",bag,"3","0"),"concurrent-create");
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            Callable<Operation> post=()->{ authenticate(); CompanyContext.setCompany(company); start.await();
                try { return production.post(operation.id(),0,"concurrent-post"); } finally { clear(); } };
            Future<Operation> a=pool.submit(post), b=pool.submit(post); start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).id()).isEqualTo(b.get(30,TimeUnit.SECONDS).id());
        }
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","9");
    }

    @Test void genericMovementEndpointsCannotModifyLinkedProduction() {
        StockMovement movement=new StockMovement(); movement.setReferenceType("PRODUCTION_OPERATION");
        assertThatThrownBy(()->stock.createAndPost(movement)).hasMessageContaining("linked document");
    }

    @Test void readOnlyUsersCannotSeeProductionCostsAndWarehouseRestrictionsAreEnforced() {
        var receipt=receive(raw,"10","2");
        Operation operation=production.create(draft("READ",raw,position(receipt.getStockBatchId()),"1",bag,"3","5"),"read-create");
        operation=production.post(operation.id(),0,"read-post");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("viewer","n/a",List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        Operation masked=production.get(operation.id());
        assertThat(masked.canViewCost()).isFalse(); assertThat(masked.inputCost()).isNull(); assertThat(masked.outputCost()).isNull();
        assertThat(masked.additionalCost()).isNull(); assertThat(masked.draft().additionalCost()).isNull(); assertThat(masked.draft().additionalCostReason()).isNull();
        assertThat(masked.lines()).allMatch(line->line.allocatedCost()==null); assertThat(masked.allowedActions()).isEmpty();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("unassigned-operator","n/a",List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_OPERATOR"))));
        UUID id=operation.id();
        assertThatThrownBy(()->production.get(id)).hasMessageContaining("assigned warehouse");
        assertThatThrownBy(()->history.material(bag.getId(),warehouse,null)).hasMessageContaining("assigned warehouse");
    }

    @Test void staleDraftEditsAndCancellationDoNotChangeStock() {
        var receipt=receive(raw,"10","2");
        Draft draft=draft("EDIT",raw,position(receipt.getStockBatchId()),"1",bag,"3","0");
        Operation operation=production.create(draft,"edit-create");
        production.update(operation.id(),0,draft);
        assertThatThrownBy(()->production.update(operation.id(),0,draft)).hasMessageContaining("changed");
        Operation cancelled=production.cancel(operation.id(),1);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->production.post(operation.id(),2,"cancelled-post")).hasMessageContaining("DRAFT");
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","10");
    }

    @Test void expiredInputAndMissingSerialOutputsCannotBypassStockControls() {
        var receipt=receive(raw,"10","2");
        Operation operation=production.create(draft("EXPIRED",raw,position(receipt.getStockBatchId()),"1",bag,"3","0"),"expired-create");
        jdbc.update("update stock_batches set expires_on=? where id=?",LocalDate.now().minusDays(1),receipt.getStockBatchId());
        assertThatThrownBy(()->production.post(operation.id(),0,"expired-post")).isInstanceOf(RuntimeException.class);
        assertValue("stock_balances","stock_batch_id",receipt.getStockBatchId(),"quantity","10");
        bag.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED); materials.saveAndFlush(bag);
        assertThatThrownBy(()->production.create(draft("SERIAL",raw,position(receipt.getStockBatchId()),"1",bag,"1","0"),"serial-create"))
            .hasMessageContaining("one serial number");
    }

    @Test void serializedAssemblyRegistersIdentitiesConsumesExactComponentsAndReversesOnce() {
        Material component = serialized("Monoblock"), computer = serialized("Computer set");
        Operation source = produce(component, List.of("MON-1", "MON-2"));
        Line sourceLine = source.lines().getLast();
        UUID componentId = sourceLine.units().getFirst().id();
        Draft draft = new Draft("SERIAL-ASSEMBLY", OperationType.ASSEMBLY, warehouse, LocalDate.now(), null, null, new BigDecimal("0.3333"), "Assembly",
            List.of(new Input(component.getId(), position(sourceLine.stockBatchId()), BigDecimal.ONE, List.of(componentId))),
            List.of(new Output(computer.getId(), BigDecimal.ONE, BigDecimal.ONE, "SET-BATCH", "OUTPUT", List.of("SET-1"))));
        Operation assembly = production.create(draft, "serial-assembly-create");
        assembly = production.post(assembly.id(), 0, "serial-assembly-post");
        Line output = assembly.lines().getLast();
        assertThat(output.units()).extracting(Unit::serialNumber).containsExactly("SET-1");
        assertThat(physicalUnits.findById(componentId).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.CONSUMED);
        assertThat(assembly.outputCost()).isEqualByComparingTo("1.3333");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "total_value", "1.3333");
        assertThat(production.post(assembly.id(), 0, "serial-assembly-post").lines().getLast().units()).hasSize(1);
        assertThatThrownBy(() -> production.reverse(source.id(), new ReversalCommand(1L, "Component consumed"), "source-reverse"))
            .hasMessageContaining("downstream activity");
        Operation reversed = production.reverse(assembly.id(), new ReversalCommand(1L, "Undo assembly"), "serial-reverse");
        assertThat(production.reverse(assembly.id(), new ReversalCommand(1L, "Undo assembly"), "serial-reverse").status()).isEqualTo("REVERSED");
        assertThat(reversed.lines().getLast().units()).extracting(Unit::status).containsExactly("DISPOSED");
        assertThat(physicalUnits.findById(componentId).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
        assertValue("stock_balances", "stock_batch_id", sourceLine.stockBatchId(), "quantity", "2");
        assertValue("stock_balances", "stock_batch_id", sourceLine.stockBatchId(), "total_value", "2");
        assertThat(jdbc.queryForObject("select count(*) from material_unit_events where source_type='PRODUCTION_REVERSAL' and source_id=?", Integer.class, assembly.id())).isEqualTo(2);
        production.reverse(source.id(), new ReversalCommand(1L, "Undo component manufacture"), "source-reverse-after");
    }

    @Test void serializedSelectionRejectsDuplicateComponentsWrongBatchesAndDuplicateOutputSerials() {
        Material component = serialized("Component"), computer = serialized("Computer");
        Operation a = produce(component, List.of("CMP-A")), b = produce(component, List.of("CMP-B"));
        Line line = a.lines().getLast(); UUID id = line.units().getFirst().id();
        Output output = new Output(computer.getId(), BigDecimal.ONE, BigDecimal.ONE, null, "OUTPUT", List.of("SET-1"));
        Input input = new Input(component.getId(), position(line.stockBatchId()), BigDecimal.ONE, List.of(id));
        Draft repeated = new Draft("DUP-UNIT", OperationType.ASSEMBLY, warehouse, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(input, input), List.of(output));
        assertThatThrownBy(() -> production.create(repeated, "dup-unit")).hasMessageContaining("only once");
        Draft wrong = new Draft("WRONG-BATCH", OperationType.ASSEMBLY, warehouse, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(new Input(component.getId(), position(b.lines().getLast().stockBatchId()), BigDecimal.ONE, List.of(id))), List.of(output));
        Operation draft = production.create(wrong, "wrong-batch");
        assertThatThrownBy(() -> production.post(draft.id(), 0, "wrong-batch-post")).hasMessageContaining("another stock position");
        assertThat(production.get(draft.id()).lines()).isEmpty();
        assertThat(physicalUnits.findById(id).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
        Draft duplicateSerials = new Draft("DUP-SERIAL", OperationType.ASSEMBLY, warehouse, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(input), List.of(output, new Output(computer.getId(), BigDecimal.ONE, BigDecimal.ONE, null, "OUTPUT", List.of(" set-1 "))));
        assertThatThrownBy(() -> production.create(duplicateSerials, "dup-serial")).hasMessageContaining("repeated");
    }

    @Test void lateSerialFailureRollsBackStockUnitEventsAndCostLayers() {
        Material component = serialized("Component"), computer = serialized("Set");
        computer.setIncomingInspectionRequired(true); materials.saveAndFlush(computer);
        Operation source = produce(component, List.of("ROLL-CMP")); Line line = source.lines().getLast();
        Draft draft = new Draft("ROLL-SERIAL", OperationType.ASSEMBLY, warehouse, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(new Input(component.getId(), position(line.stockBatchId()), BigDecimal.ONE, List.of(line.units().getFirst().id()))),
            List.of(new Output(computer.getId(), BigDecimal.ONE, BigDecimal.ONE, null, "OUTPUT", List.of("ROLL-SET"))));
        Operation operation = production.create(draft, "roll-serial-create");
        jdbc.execute("alter table warehouse_production_operation_lines add constraint production_test_serial_failure check(operation_id<>'" + operation.id() + "' or line_type='INPUT')");
        try {
            assertThatThrownBy(() -> production.post(operation.id(), 0, "roll-serial-post")).isInstanceOf(RuntimeException.class);
            assertThat(physicalUnits.findById(line.units().getFirst().id()).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK);
            assertThat(jdbc.queryForObject("select count(*) from material_unit_events where source_id=?", Integer.class, operation.id())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from material_units where material_id=?", Integer.class, computer.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from quality_inspections where company_id=?", Integer.class, company)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from quality_quarantine_lots where company_id=?", Integer.class, company)).isZero();
            assertValue("stock_balances", "stock_batch_id", line.stockBatchId(), "quantity", "1");
            assertValue("stock_balances", "stock_batch_id", line.stockBatchId(), "total_value", "2");
            assertThat(production.get(operation.id()).status()).isEqualTo("DRAFT");
        } finally { jdbc.execute("alter table warehouse_production_operation_lines drop constraint production_test_serial_failure"); }
        assertThat(production.post(operation.id(), 0, "roll-serial-post").status()).isEqualTo("POSTED");
    }

    @Test void productionQualityUsesNativeSamplingLaboratoryAndMixedQuantityRelease() {
        bag.setIncomingInspectionRequired(true); materials.saveAndFlush(bag);
        var receipt = receive(raw, "10", "2");
        Operation operation = production.create(draft("QC-BAGS", raw, position(receipt.getStockBatchId()), "1", bag, "10", "0"), "qc-create");
        operation = production.post(operation.id(), 0, "qc-post");
        Line output = operation.lines().getLast(); UUID inspection = output.qualityInspectionId();
        assertThat(inspection).isNotNull(); assertThat(output.qualityStatus()).isEqualTo("IN_QUARANTINE");
        var queue = qualityWorkspace.list(new com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueFilter(
            com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.QueueKey.OPEN, null, warehouse, null), org.springframework.data.domain.PageRequest.of(0, 25));
        assertThat(queue.items()).extracting(com.enterprise.mm.quality.workspace.payload.IncomingQualityWorkspaceDtos.InspectionListItem::id).contains(inspection);
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "available_quantity", "0");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "quarantine_quantity", "10");
        Operation pending = operation;
        assertThatThrownBy(() -> production.reverse(pending.id(), new ReversalCommand(1L, "Pending inspection"), "qc-reverse-pending")).hasMessageContaining("fully released");
        assertThatThrownBy(() -> quality.release(inspection, new QualityControlDtos.QualityDecisionRequest(BigDecimal.TEN, BigDecimal.ZERO, "Skip laboratory"))).hasMessageContaining("not allowed");
        completeLab(inspection, true);
        quality.release(inspection, new QualityControlDtos.QualityDecisionRequest(new BigDecimal("7"), new BigDecimal("3"), "Three defective bags"));
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "available_quantity", "7");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "quarantine_quantity", "3");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "total_value", "2");
        assertThatThrownBy(() -> production.reverse(pending.id(), new ReversalCommand(1L, "Rejected balance"), "qc-reverse-partial")).hasMessageContaining("fully released");
        var next = production.create(draft("QC-DOWNSTREAM", bag, position(output.stockBatchId()), "8", packaged, "8", "0"), "qc-next");
        assertThatThrownBy(() -> production.post(next.id(), 0, "qc-next-post")).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> quality.softDelete(inspection)).hasMessageContaining("cannot be edited or deleted");
        assertThatThrownBy(() -> quality.update(inspection, new QualityInspectionUpdateRequest())).hasMessageContaining("cannot be edited or deleted");
        var duplicate = new QualityInspectionCreateRequest(); duplicate.setStockMovementId(output.movementId()); duplicate.setInspectionType("INCOMING");
        assertThatThrownBy(() -> quality.create(duplicate)).hasMessageContaining("one inspection");
        assertThat(qualityWorkspace.detail(inspection)).isNotNull();
    }

    @Test void serializedQualityHoldsEveryPhysicalUnitAndOnlyFullDecisionReleasesThem() {
        Material computer = serialized("Inspected computer"); computer.setIncomingInspectionRequired(true); materials.saveAndFlush(computer);
        Operation operation = produce(computer, List.of("QC-SET-1", "QC-SET-2"));
        Line output = operation.lines().getLast(); UUID inspection = output.qualityInspectionId();
        assertThat(output.units()).extracting(Unit::status).containsOnly("QUARANTINE");
        completeLab(inspection, true);
        assertThatThrownBy(() -> quality.release(inspection, new QualityControlDtos.QualityDecisionRequest(BigDecimal.ONE, BigDecimal.ONE, "Separate units")))
            .hasMessageContaining("full-lot decision");
        assertThat(quality.get(inspection).getStatus()).isEqualTo("PASSED");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "available_quantity", "0");
        assertThat(production.get(operation.id()).lines().getLast().units()).extracting(Unit::status).containsOnly("QUARANTINE");
        quality.release(inspection, new QualityControlDtos.QualityDecisionRequest(new BigDecimal("2"), BigDecimal.ZERO, "All units accepted"));
        assertThat(production.get(operation.id()).lines().getLast().units()).extracting(Unit::status).containsOnly("IN_STOCK");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "available_quantity", "2");
        Operation reversed = production.reverse(operation.id(), new ReversalCommand(1L, "Undo inspected manufacture"), "qc-serial-reverse");
        assertThat(reversed.lines().getLast().units()).extracting(Unit::status).containsOnly("DISPOSED");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "quantity", "0");
    }

    @Test void rejectedSerializedProductionStaysQuarantinedAndCannotBeConsumedOrReversed() {
        Material computer = serialized("Rejected computer"); computer.setIncomingInspectionRequired(true); materials.saveAndFlush(computer);
        Operation operation = produce(computer, List.of("BAD-SET")); Line output = operation.lines().getLast();
        completeLab(output.qualityInspectionId(), false);
        quality.reject(output.qualityInspectionId(), new QualityControlDtos.QualityDecisionRequest(BigDecimal.ZERO, BigDecimal.ONE, "Defective"));
        assertThat(production.get(operation.id()).lines().getLast().units()).extracting(Unit::status).containsExactly("QUARANTINE");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "available_quantity", "0");
        assertValue("stock_balances", "stock_batch_id", output.stockBatchId(), "quarantine_quantity", "1");
        assertThatThrownBy(() -> production.reverse(operation.id(), new ReversalCommand(1L, "Rejected stock"), "reject-reverse")).hasMessageContaining("fully released");
    }

    @Test void producedSerialIdentitySurvivesNativeDispatchAndReceiptInAnotherWorkshop() {
        Material computer = serialized("Computer transfer");
        Operation operation = produce(computer, List.of("TRANSFER-SET")); Line output = operation.lines().getLast();
        UUID unitId = output.units().getFirst().id();
        Warehouse destination = new Warehouse(); destination.setName("Computer workshop"); destination.setStatus("ACTIVE");
        UUID destinationId = warehouses.saveAndFlush(destination).getId();
        TransferCreateRequest create = new TransferCreateRequest(); create.setSourceWarehouseId(warehouse); create.setDestinationWarehouseId(destinationId);
        TransferCreateRequest.Line planned = new TransferCreateRequest.Line(); planned.setMaterialId(computer.getId()); planned.setPlannedQuantity(BigDecimal.ONE);
        planned.setStockBatchId(output.stockBatchId()); create.setLines(List.of(planned));
        var transfer = transfers.create(create);
        // Prepared approval fixture: this scenario certifies physical units and stock genealogy only.
        jdbc.update("update transfer_documents set status='APPROVED' where id=?", transfer.getId()); transfer = transfers.get(transfer.getId());
        TransferDispatchRequest dispatch = new TransferDispatchRequest(); dispatch.setExpectedVersion(transfer.getVersion()); dispatch.setDispatchedDate(LocalDate.now());
        TransferDispatchRequest.Line dispatched = new TransferDispatchRequest.Line(); dispatched.setTransferLineId(transfer.getLines().getFirst().getId());
        dispatched.setDispatchedQuantity(BigDecimal.ONE); dispatched.setStockBatchId(output.stockBatchId()); dispatched.setMaterialUnitIds(List.of(unitId)); dispatch.setLines(List.of(dispatched));
        transfer = transfers.dispatch(transfer.getId(), dispatch, "serial-transfer-dispatch");
        assertThat(physicalUnits.findById(unitId).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
        assertThatThrownBy(() -> production.reverse(operation.id(), new ReversalCommand(1L, "In transit"), "serial-transfer-reverse")).hasMessageContaining("downstream activity");
        TransferReceiveRequest receive = new TransferReceiveRequest(); receive.setExpectedVersion(transfer.getVersion()); receive.setReceivedDate(LocalDate.now());
        TransferReceiveRequest.Line received = new TransferReceiveRequest.Line(); received.setShipmentAllocationId(transfer.getShipments().getFirst().allocations().getFirst().id());
        received.setReceivedQuantity(BigDecimal.ONE); received.setDamagedQuantity(BigDecimal.ZERO); received.setMaterialUnitIds(List.of(unitId)); receive.setLines(List.of(received));
        received.setReceivedQuantity(BigDecimal.ZERO); received.setDamagedQuantity(BigDecimal.ONE); received.setConditionEvidence("Damaged serial");
        UUID transferId = transfer.getId();
        assertThatThrownBy(() -> transfers.receive(transferId, receive, "serial-transfer-damaged")).hasMessageContaining("Select exactly one physical unit");
        assertThat(physicalUnits.findById(unitId).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_TRANSIT);
        received.setReceivedQuantity(BigDecimal.ONE); received.setDamagedQuantity(BigDecimal.ZERO);
        transfers.receive(transfer.getId(), receive, "serial-transfer-receive");
        MaterialUnit moved = physicalUnits.findById(unitId).orElseThrow();
        assertThat(moved.getUnitStatus()).isEqualTo(MaterialUnitStatus.IN_STOCK); assertThat(moved.getWarehouseId()).isEqualTo(destinationId);
        assertThat(moved.getProductionStockMovementId()).isEqualTo(output.movementId());
        assertThat(moved.getStockBatchId()).isNotEqualTo(output.stockBatchId());
        History transferredHistory = history.material(computer.getId(), destinationId, moved.getStockBatchId());
        assertThat(transferredHistory.nodes()).extracting(HistoryNode::movementId).contains(output.movementId());
        String transferCode = transfer.getCode();
        assertThat(transferredHistory.nodes()).anyMatch(node -> transferId.equals(node.referenceId()) && transferCode.equals(node.documentNumber()));
        assertValue("stock_balances", "stock_batch_id", moved.getStockBatchId(), "quantity", "1");
        assertValue("stock_balances", "stock_batch_id", moved.getStockBatchId(), "total_value", "2");
        Draft next = new Draft("TRANSFER-SERIAL-NEXT", OperationType.PROCESSING, destinationId, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(new Input(computer.getId(), position(moved.getStockBatchId()), BigDecimal.ONE, List.of(unitId))),
            List.of(new Output(packaged.getId(), BigDecimal.ONE, BigDecimal.ONE, null, "OUTPUT")));
        Operation downstream = production.create(next, "transfer-serial-next"); downstream = production.post(downstream.id(), 0, "transfer-serial-next-post");
        assertThat(downstream.outputCost()).isEqualByComparingTo("2");
        assertThat(physicalUnits.findById(unitId).orElseThrow().getUnitStatus()).isEqualTo(MaterialUnitStatus.CONSUMED);
        assertThat(history.material(packaged.getId(), destinationId, downstream.lines().getLast().stockBatchId()).nodes()).extracting(HistoryNode::movementId).contains(output.movementId());
    }

    private Material serialized(String name) {
        Material material = material(name, pieces); material.setSerialTrackingPolicy(SerialTrackingPolicy.REQUIRED);
        return materials.saveAndFlush(material);
    }
    private Operation produce(Material output, List<String> serials) {
        var receipt = receive(raw, "10", "2");
        Draft draft = new Draft("PRODUCE-" + output.getId() + "-" + serials.getFirst(), OperationType.MANUFACTURING, warehouse, LocalDate.now(), null, null, BigDecimal.ZERO, null,
            List.of(new Input(raw.getId(), position(receipt.getStockBatchId()), BigDecimal.ONE)),
            List.of(new Output(output.getId(), new BigDecimal(serials.size()), BigDecimal.ONE, null, "OUTPUT", serials)));
        Operation operation = production.create(draft, "create-" + draft.operationNumber());
        return production.post(operation.id(), 0, "post-" + draft.operationNumber());
    }
    private void completeLab(UUID inspection, boolean passed) {
        quality.startSampling(inspection);
        LaboratoryTestCreateRequest request = new LaboratoryTestCreateRequest(); request.setName("Production output test");
        request.setQualityInspectionId(inspection); request.setTestType("PRODUCTION_OUTPUT");
        var test = laboratory.create(request); laboratory.start(test.getId());
        laboratory.recordResult(test.getId(), new QualityControlDtos.LaboratoryResultRequest(passed, "Checked", "Production verification", null, "Test completed"));
        assertThat(quality.get(inspection).getStatus()).isEqualTo(passed ? "PASSED" : "FAILED");
    }

    @Test void productionOriginSurvivesDispatchReceiptAndProcessingInAnotherWorkshop() {
        var receipt=receive(raw,"10","2");
        Operation first=production.create(draft("STAGE-A",raw,position(receipt.getStockBatchId()),"1",bag,"3","0"),"stage-a");
        first=production.post(first.id(),0,"stage-a-post");
        UUID outputBatch=first.lines().getLast().stockBatchId();
        Warehouse destination=new Warehouse(); destination.setName("Packing workshop"); destination.setStatus("ACTIVE");
        UUID destinationId=warehouses.saveAndFlush(destination).getId();
        TransferCreateRequest create=new TransferCreateRequest(); create.setSourceWarehouseId(warehouse); create.setDestinationWarehouseId(destinationId);
        TransferCreateRequest.Line planned=new TransferCreateRequest.Line(); planned.setMaterialId(bag.getId()); planned.setPlannedQuantity(new BigDecimal("3")); planned.setStockBatchId(outputBatch); create.setLines(List.of(planned));
        var transfer=transfers.create(create);
        // Approval is a prepared fixture; this scenario verifies physical transfer and production lineage.
        jdbc.update("update transfer_documents set status='APPROVED' where id=?",transfer.getId());
        transfer=transfers.get(transfer.getId());
        TransferDispatchRequest dispatch=new TransferDispatchRequest(); dispatch.setExpectedVersion(transfer.getVersion()); dispatch.setDispatchedDate(LocalDate.now());
        TransferDispatchRequest.Line dispatchLine=new TransferDispatchRequest.Line(); dispatchLine.setTransferLineId(transfer.getLines().getFirst().getId()); dispatchLine.setDispatchedQuantity(new BigDecimal("3")); dispatchLine.setStockBatchId(outputBatch); dispatch.setLines(List.of(dispatchLine));
        transfer=transfers.dispatch(transfer.getId(),dispatch,"bags-dispatch");
        UUID allocation=transfer.getShipments().getFirst().allocations().getFirst().id();
        TransferReceiveRequest receive=new TransferReceiveRequest(); receive.setExpectedVersion(transfer.getVersion()); receive.setReceivedDate(LocalDate.now());
        TransferReceiveRequest.Line receiveLine=new TransferReceiveRequest.Line(); receiveLine.setShipmentAllocationId(allocation); receiveLine.setReceivedQuantity(new BigDecimal("3")); receiveLine.setDamagedQuantity(BigDecimal.ZERO); receive.setLines(List.of(receiveLine));
        transfers.receive(transfer.getId(),receive,"bags-receive");
        UUID receivedBatch=jdbc.queryForObject("select stock_batch_id from stock_balances where warehouse_id=? and material_id=? and quantity>0",UUID.class,destinationId,bag.getId());
        Draft next=new Draft("STAGE-B",OperationType.PACKAGING,destinationId,LocalDate.now(),null,"Packing",BigDecimal.ZERO,null,
            List.of(new Input(bag.getId(),position(receivedBatch),new BigDecimal("3"))),List.of(new Output(packaged.getId(),new BigDecimal("3"),BigDecimal.ONE,null,"OUTPUT")));
        Operation second=production.create(next,"stage-b"); second=production.post(second.id(),0,"stage-b-post");
        History trace=history.material(packaged.getId(),destinationId,second.lines().getLast().stockBatchId());
        assertThat(trace.truncated()).isFalse();
        assertThat(trace.nodes()).extracting(HistoryNode::warehouseId).contains(warehouse,destinationId);
        assertThat(trace.nodes()).extracting(HistoryNode::movementId).contains(receipt.getId(),first.lines().getLast().movementId());
        assertThat(second.outputCost()).isEqualByComparingTo("2");
    }

    private Draft draft(String number,Material input,UUID position,String inputQty,Material output,String outputQty,String extra) {
        return new Draft(number,OperationType.MANUFACTURING,warehouse,LocalDate.now(),null,"Production fixture",new BigDecimal(extra),"Workshop cost",
            List.of(new Input(input.getId(),position,new BigDecimal(inputQty))),
            List.of(new Output(output.getId(),new BigDecimal(outputQty),BigDecimal.ONE,null,"OUTPUT")));
    }
    private UnitOfMeasure unit(String code,int scale) {
        UnitOfMeasure u=new UnitOfMeasure(); u.setCode(code+"-"+UUID.randomUUID().toString().substring(0,8)); u.setName(code); u.setStatus("ACTIVE");
        u.setMeasurementNameShort(code); u.setMeasurementNameLong(code); u.setUnitType(MeasurementUnitType.QUANTITY); u.setStockQuantityScale(scale);
        return units.saveAndFlush(u);
    }
    private Material material(String name,UnitOfMeasure unit) {
        Material m=new Material(); m.setCode("MAT-"+UUID.randomUUID().toString().substring(0,8)); m.setName(name); m.setStatus("ACTIVE");
        m.setMaterialType(MaterialType.TMB); m.setMaterialGroupId(group); m.setUnitOfMeasureId(unit.getId()); m.setValuationMethod(ValuationMethod.WEIGHTED_AVERAGE);
        return materials.saveAndFlush(m);
    }
    private StockMovementResponse receive(Material material,String quantity,String price) {
        StockMovement movement=new StockMovement(); movement.setCompanyId(company); movement.setMaterialId(material.getId()); movement.setWarehouseId(warehouse);
        movement.setMovementType(MovementType.RECEIPT); movement.setQuantity(new BigDecimal(quantity)); movement.setUnitCost(new BigDecimal(price));
        movement.setMovementDate(LocalDate.now()); movement.setReferenceType("ProductionTest"); movement.setReferenceId(UUID.randomUUID());
        return stock.createAndPost(movement);
    }
    private UUID position(UUID batch) { return jdbc.queryForObject("select id from stock_balances where stock_batch_id=?",UUID.class,batch); }
    private void assertValue(String table,String key,UUID id,String column,String expected) {
        assertThat(jdbc.queryForObject("select "+column+" from "+table+" where "+key+"=?",BigDecimal.class,id)).isEqualByComparingTo(expected);
    }
    private void authenticate() {
        TenantContext.setTenant("default");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("production.test","n/a",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
