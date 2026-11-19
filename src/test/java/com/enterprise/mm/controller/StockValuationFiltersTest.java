package com.enterprise.mm.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.GlobalExceptionHandler;
import com.enterprise.mm.mapper.StockValuationMapper;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the HTTP parameters, real services, JPA queries and pagination together. */
class StockValuationFiltersTest {
    private static SessionFactory database;
    private EntityManager em;
    private MockMvc mvc;
    private UUID company;
    private UUID warehouse;
    private StockValuationCostJobService jobs;

    @BeforeAll
    static void database() {
        var config = new Configuration()
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:valuation-filters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        for (Class<?> type : List.of(StockValuationRun.class, StockValuationRunItem.class,
                StockValuationCostJob.class, Material.class, MaterialGroup.class, UnitOfMeasure.class,
                MXIK.class, TnvedCode.class, Manufacturer.class)) config.addAnnotatedClass(type);
        database = config.buildSessionFactory();
    }

    @AfterAll
    static void closeDatabase() { if (database != null) database.close(); }

    @BeforeEach
    void setup() {
        TenantContext.setTenant("filter-test");
        company = UUID.randomUUID();
        CompanyContext.setCompany(company);
        warehouse = UUID.randomUUID();
        em = database.createEntityManager();
        em.getTransaction().begin();
        var factory = new JpaRepositoryFactory(em);
        jobs = mock(StockValuationCostJobService.class);
        var service = new StockValuationRunService(factory.getRepository(StockValuationRunRepository.class),
            factory.getRepository(StockValuationRunItemRepository.class), mock(StockValuationCostJobResultRepository.class),
            jobs, mock(WarehouseRepository.class), mock(StockValuationRepository.class));
        var controller = new StockValuationController(mock(StockValuationRecordService.class),
            mock(StockValuationService.class), mock(StockValuationMapper.class), mock(InventoryValuationPolicyService.class),
            mock(StockValuationCostSelectionService.class), jobs, mock(StockValuationHistoryService.class), service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        if (em != null) { em.getTransaction().rollback(); em.close(); }
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void unfilteredHistoryReturnsRunsWithAndWithoutJobs() throws Exception {
        var withoutJob = run("Without job", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        var withJob = run("With job", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        var job = StockValuationCostJob.queued("filter-test", company, null, ValuationMethod.FIFO, "tester");
        em.persist(job);
        withJob.setJobId(job.getId());
        withJob.setStatus("RUNNING");
        em.flush();
        mvc.perform(get("/api/valuation/runs").param("page", "0").param("size", "20")
                .param("sort", "createdAt,desc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[*].id", org.hamcrest.Matchers.containsInAnyOrder(
                withoutJob.getId().toString(), withJob.getId().toString())))
            .andExpect(jsonPath("$.data.content[*].status", org.hamcrest.Matchers.containsInAnyOrder("COMPLETED", "QUEUED")));
        mvc.perform(get("/api/valuation/runs").param("status", "COMPLETED", "QUEUED").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void itemsCombineSearchWarehouseAndStatusBeforePagination() throws Exception {
        var run = run("Run", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        item(run, 1, "Copper", "CU-1", warehouse, false);
        var expected = item(run, 2, "Steel", "ST-2", warehouse, false);
        item(run, 3, "Steel sheet", "ST-3", warehouse, false);
        item(run, 4, "Steel", "ST-4", UUID.randomUUID(), false);
        item(run, 5, "Steel", "ST-5", warehouse, true);
        mvc.perform(get(items(run)).param("search", " sTeEl ").param("warehouseId", warehouse.toString())
                .param("status", "FAILED").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").value(expected.getId().toString()));
        mvc.perform(get(items(run)).param("search", "st-3"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(items(run)).param("search", "steel").param("warehouseId", warehouse.toString())
                .param("status", "FAILED").param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].sequenceNo").value(3));
        mvc.perform(get(items(run)).param("status", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(items(run)).param("status", "FAILED", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(5));
        mvc.perform(get(items(run)).param("search", "absent"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void unfilteredItemsStayCompatibleAndCannotReadOtherRunsOrCompanies() throws Exception {
        var run = run("Run", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        item(run, 1, "Steel", "A", warehouse, false);
        var other = run("Other", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        item(other, 1, "Copper", "B", warehouse, true);
        mvc.perform(get(items(run))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
        CompanyContext.setCompany(UUID.randomUUID());
        mvc.perform(get(items(run))).andExpect(status().isNotFound());
        CompanyContext.setCompany(company);
        TenantContext.setTenant("other-tenant");
        mvc.perform(get(items(run))).andExpect(status().isNotFound());
    }

    @Test
    void searchTreatsWildcardsAsLiteralText() throws Exception {
        var run = run("Run", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        item(run, 1, "Steel 10%", "ST_1", warehouse, false);
        item(run, 2, "Copper", "STX1", warehouse, false);
        mvc.perform(get(items(run)).param("search", "%"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(items(run)).param("search", "ST_"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void historyWarehouseIncludesSingleMultiAndGlobalWithoutDuplicateRows() throws Exception {
        run("Single", StockValuationRunScope.WAREHOUSE, warehouse, ValuationMethod.FIFO);
        var multi = run("Multi", StockValuationRunScope.MULTI_WAREHOUSE, null, ValuationMethod.FIFO);
        multi.getWarehouseIds().addAll(List.of(warehouse, UUID.randomUUID()));
        run("Global", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        run("Unrelated", StockValuationRunScope.WAREHOUSE, UUID.randomUUID(), ValuationMethod.FIFO);
        mvc.perform(get("/api/valuation/runs").param("warehouseId", warehouse.toString()).param("size", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3))
            .andExpect(jsonPath("$.data.totalPages").value(2));
        mvc.perform(get("/api/valuation/runs").param("warehouseId", warehouse.toString())
                .param("scopeType", "MULTI_WAREHOUSE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void historyCombinesMethodNameStatusAndInclusiveCreatedDates() throws Exception {
        run("September steel", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        var matching = run("September steel", StockValuationRunScope.GLOBAL, null, ValuationMethod.MARKET_PRICE);
        var old = run("September steel", StockValuationRunScope.GLOBAL, null, ValuationMethod.MARKET_PRICE);
        var tomorrow = run("September steel", StockValuationRunScope.GLOBAL, null, ValuationMethod.MARKET_PRICE);
        // createdAt is immutable, so set historical fixture date directly in the isolated DB.
        em.flush();
        em.createQuery("update StockValuationRun set createdAt = :date where id = :id")
            .setParameter("date", LocalDateTime.of(2026, 9, 21, 23, 59)).setParameter("id", old.getId()).executeUpdate();
        em.createQuery("update StockValuationRun set createdAt = :date where id = :id")
            .setParameter("date", LocalDateTime.of(2026, 9, 23, 23, 59, 59)).setParameter("id", matching.getId()).executeUpdate();
        em.createQuery("update StockValuationRun set createdAt = :date where id = :id")
            .setParameter("date", LocalDateTime.of(2026, 9, 24, 0, 0)).setParameter("id", tomorrow.getId()).executeUpdate();
        
        mvc.perform(get("/api/valuation/runs").param("search", "STEEL").param("valuationMethod", "MARKET_PRICE")
                .param("status", "COMPLETED").param("dateFrom", "2026-09-23").param("dateTo", "2026-09-23"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void historyFiltersOnCurrentJobStatusAndDoesNotWriteWhileListing() throws Exception {
        var run = run("Finishing", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        var job = StockValuationCostJob.queued("filter-test", company, null, ValuationMethod.FIFO, "tester");
        job.setStatus(StockValuationCostJobStatus.COMPLETED);
        job.setFinishedAt(LocalDateTime.of(2026, 9, 23, 12, 0));
        em.persist(job);
        run.setJobId(job.getId());
        run.setStatus("RUNNING");
        em.flush();
        mvc.perform(get("/api/valuation/runs").param("status", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"));
        mvc.perform(get("/api/valuation/runs").param("status", "RUNNING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(run.getStatus()).isEqualTo("RUNNING");
        verifyNoInteractions(jobs);
    }

    @Test
    void historyPreservesTerminalRunsAndIgnoresJobsOutsideTheirScope() throws Exception {
        var job = StockValuationCostJob.queued("filter-test", company, null, ValuationMethod.FIFO, "tester");
        em.persist(job);
        var terminal = run("Finished", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        terminal.setJobId(job.getId());
        mvc.perform(get("/api/valuation/runs").param("status", "COMPLETED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        var active = run("Running", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        active.setStatus("RUNNING"); active.setJobId(job.getId());
        job.setCompanyId(UUID.randomUUID());
        // companyId is immutable; emulate a mismatched legacy reference in the isolated DB.
        em.flush();
        em.createQuery("update StockValuationCostJob set companyId = :company where id = :id")
            .setParameter("company", job.getCompanyId()).setParameter("id", job.getId()).executeUpdate();
        mvc.perform(get("/api/valuation/runs").param("status", "RUNNING"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].status").value("RUNNING"));
    }

    @Test
    void historyEnforcesTenantCompanyAndDeletedFilters() throws Exception {
        run("Visible", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        run("Deleted", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO).softDelete();
        run("Other company", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO).setCompanyId(UUID.randomUUID());
        run("Other tenant", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO).setTenantId("another");
        mvc.perform(get("/api/valuation/runs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/valuation/runs").param("includeDeleted", "true")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void invalidFiltersReturnBadRequest() throws Exception {
        var run = run("Run", StockValuationRunScope.GLOBAL, null, ValuationMethod.FIFO);
        mvc.perform(get(items(run)).param("status", "RUNNING")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/valuation/runs").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/valuation/runs").param("valuationMethod", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/valuation/runs").param("dateFrom", "2026-09-24").param("dateTo", "2026-09-23"))
            .andExpect(status().isBadRequest());
    }

    private StockValuationRun run(String name, StockValuationRunScope scope, UUID warehouseId, ValuationMethod method) {
        var run = new StockValuationRun();
        run.setName(name); run.setCode("RUN-" + UUID.randomUUID());
        run.setTenantId("filter-test"); run.setCompanyId(company);
        run.setCreatedAt(LocalDateTime.of(2026, 9, 23, 23, 59, 59));
        run.setScopeType(scope); run.setWarehouseId(warehouseId); run.setValuationMethod(method);
        run.setStatus("COMPLETED"); em.persist(run);
        return run;
    }

    private StockValuationRunItem item(StockValuationRun run, int sequence, String name, String code, UUID warehouseId, boolean available) {
        var material = new Material(); material.setName(name); material.setCode(code);
        material.setTenantId("filter-test"); material.setCompanyId(company); material.setCreatedAt(LocalDateTime.now());
        em.persist(material);
        var item = new StockValuationRunItem(); item.setValuationRunId(run.getId()); item.setSequenceNo(sequence);
        item.setMaterialId(material.getId()); item.setMaterialName(name); item.setWarehouseId(warehouseId);
        item.setWarehouseName("Warehouse"); item.setQuantity(BigDecimal.TEN); item.setAvailable(available);
        item.setValuationMethod(ValuationMethod.FIFO); em.persist(item); return item;
    }

    private String items(StockValuationRun run) { return "/api/valuation/runs/" + run.getId() + "/items"; }
}
