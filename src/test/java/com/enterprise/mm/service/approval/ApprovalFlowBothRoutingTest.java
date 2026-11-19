package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalFlow;
import com.enterprise.mm.entity.ApprovalInstance;
import com.enterprise.mm.enums.ApprovalRequestType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.repository.*;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Uses the real JPA mapping and repository queries with an isolated in-memory DB. */
class ApprovalFlowBothRoutingTest {
    private static SessionFactory database;
    private EntityManager em;
    private ApprovalFlowRepository flows;
    private ApprovalModuleAdapter adapter;
    private ApprovalEngineService engine;
    private UUID department;

    @BeforeAll
    static void database() {
        database = new Configuration().addAnnotatedClass(ApprovalFlow.class)
            .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:approval-both;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")
            .buildSessionFactory();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) database.close();
    }

    @BeforeEach
    void setup() {
        TenantContext.setTenant("both-routing");
        CompanyContext.setCompany(UUID.randomUUID());
        department = UUID.randomUUID();
        em = database.createEntityManager();
        em.getTransaction().begin();
        flows = new JpaRepositoryFactory(em).getRepository(ApprovalFlowRepository.class);
        adapter = mock(ApprovalModuleAdapter.class);
        when(adapter.usesRequestType()).thenReturn(true);
        when(adapter.departmentId(any())).thenReturn(department);
        var registry = mock(ApprovalModuleRegistry.class);
        when(registry.adapter("PURCHASE_REQUEST")).thenReturn(adapter);
        var instances = mock(ApprovalInstanceRepository.class);
        when(instances.save(any())).thenAnswer(call -> {
            ApprovalInstance instance = call.getArgument(0);
            instance.setId(UUID.randomUUID());
            return instance;
        });
        engine = new ApprovalEngineService(flows, mock(ApprovalFlowStepRepository.class), instances,
            mock(ApprovalTaskRepository.class), mock(ApprovalHistoryRepository.class), registry);
    }

    @AfterEach
    void cleanup() {
        if (em != null) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void bothRoutesGoodsAndServicesAtCompanyAndDepartmentScope() {
        var companyBoth = flow(null, ApprovalRequestType.BOTH);
        assertThat(selected(RequestType.GOODS)).isEqualTo(companyBoth.getId());
        assertThat(selected(RequestType.SERVICE)).isEqualTo(companyBoth.getId());
        var departmentBoth = flow(department, ApprovalRequestType.BOTH);
        assertThat(selected(RequestType.GOODS)).isEqualTo(departmentBoth.getId());
        assertThat(selected(RequestType.SERVICE)).isEqualTo(departmentBoth.getId());
    }

    @Test
    void exactTypeWinsWithinSameScopeEvenWhenBothIsNewer() {
        var companyGoods = flow(null, ApprovalRequestType.GOODS);
        flow(null, ApprovalRequestType.BOTH);
        assertThat(selected(RequestType.GOODS)).isEqualTo(companyGoods.getId());
        var departmentService = flow(department, ApprovalRequestType.SERVICE);
        var departmentBoth = flow(department, ApprovalRequestType.BOTH);
        assertThat(selected(RequestType.SERVICE)).isEqualTo(departmentService.getId());
        assertThat(selected(RequestType.GOODS)).isEqualTo(departmentBoth.getId());
    }

    @Test
    void legacyNullFallbackAndDepartmentPriorityRemainUnchanged() {
        var companyGeneric = flow(null, null);
        assertThat(selected(RequestType.GOODS)).isEqualTo(companyGeneric.getId());
        assertThat(selected(RequestType.SERVICE)).isEqualTo(companyGeneric.getId());
        flow(null, ApprovalRequestType.GOODS);
        var departmentGeneric = flow(department, null);
        assertThat(selected(RequestType.GOODS)).isEqualTo(departmentGeneric.getId());
        assertThat(selected(RequestType.SERVICE)).isEqualTo(departmentGeneric.getId());
    }

    @Test
    void ignoresOtherCompaniesTenantsDepartmentsModulesAndInactiveOrDeletedRules() {
        var expected = flow(null, null);
        flow(null, ApprovalRequestType.BOTH).setCompanyId(UUID.randomUUID());
        flow(null, ApprovalRequestType.BOTH).setTenantId("another-tenant");
        flow(UUID.randomUUID(), ApprovalRequestType.BOTH);
        flow(null, ApprovalRequestType.BOTH).setModuleKey("OTHER_MODULE");
        flow(null, ApprovalRequestType.BOTH).setActive(false);
        flow(null, ApprovalRequestType.BOTH).softDelete();
        assertThat(selected(RequestType.GOODS)).isEqualTo(expected.getId());
        assertThat(selected(RequestType.SERVICE)).isEqualTo(expected.getId());
    }

    @Test
    void moduleWithoutRequestTypesStillSelectsOnlyLegacyGenericRules() {
        when(adapter.usesRequestType()).thenReturn(false);
        var generic = flow(null, null);
        flow(department, ApprovalRequestType.BOTH);
        assertThat(selected(null)).isEqualTo(generic.getId());
    }

    @Test
    void bothRoundTripsThroughDatabaseAndSupportsListFilter() {
        var both = flow(null, ApprovalRequestType.BOTH);
        flow(null, ApprovalRequestType.GOODS);
        em.flush();
        em.clear();
        assertThat(flows.findById(both.getId()).orElseThrow().getRequestType()).isEqualTo(ApprovalRequestType.BOTH);
        var result = flows.search(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            "PURCHASE_REQUEST", null, List.of(ApprovalRequestType.BOTH), false, null, false,
            null, null, false, "", PageRequest.of(0, 20));
        assertThat(result.getContent()).extracting(ApprovalFlow::getId).containsExactly(both.getId());
    }

    private UUID selected(RequestType type) {
        when(adapter.requestType(any())).thenReturn(type);
        em.flush();
        return engine.linkItem("PURCHASE_REQUEST", UUID.randomUUID(), CompanyContext.currentCompany(), "Purchase request")
            .approvalFlowId();
    }

    private ApprovalFlow flow(UUID departmentId, ApprovalRequestType type) {
        var flow = new ApprovalFlow();
        flow.setName("Approval rule");
        flow.setCreatedAt(LocalDateTime.now());
        flow.setModuleKey("PURCHASE_REQUEST");
        flow.setDepartmentId(departmentId);
        flow.setRequestType(type);
        em.persist(flow);
        return flow;
    }
}
