package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.ServiceClaim;
import com.enterprise.mm.entity.ServiceReviewHistory;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.ServiceClaimOriginType;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.CaseFilter;
import com.enterprise.mm.repository.ServiceCaseWorkspaceQueryRepository.OriginType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "app.seed.enabled=false")
@ActiveProfiles("test")
@Transactional
class ServiceCaseWorkspaceQueryRepositoryTest {
    private static final String TENANT = "tenant-service-case-query";

    @Autowired private ServiceCaseWorkspaceQueryRepository repository;
    @Autowired private EntityManager entityManager;

    private UUID companyId;
    private Supplier supplier;
    private Supplier otherSupplier;
    private Contract contract;
    private Department department;
    private User owner;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
        Company company = base(new Company(), "COMP-SVC-" + companyId.toString().substring(0, 8), "Service test company", "ACTIVE");
        persist(company);
        companyId = company.getId();
        CompanyContext.setCompany(companyId);
        supplier = persist(base(new Supplier(), "SUP-SVC-1", "Canonical supplier", "ACTIVE"));
        supplier.setLegalName("Canonical Service Supplier");
        otherSupplier = persist(base(new Supplier(), "SUP-SVC-2", "Other supplier", "ACTIVE"));
        otherSupplier.setLegalName("Other Service Supplier");
        contract = base(new Contract(), "CTR-SVC-1", "Service contract", "ACTIVE");
        contract.setContractNumber("CONTRACT-2026-1");
        contract.setSupplierId(supplier.getId());
        contract.setCurrencyCode("UZS");
        contract = persist(contract);
        department = persist(base(new Department(), "DEP-SVC-1", "Maintenance", "ACTIVE"));
        owner = base(new User(), "USR-SVC-1", "Case owner", "ACTIVE");
        owner.setUsername("service.case.owner");
        owner.setFullName("Service Case Owner");
        owner.setPasswordHash("test-only-hash");
        owner.setDepartment(department);
        owner = persist(owner);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void canonicalListContainsRequestRequestlessPoServiceAndPoWorksWithExactCountParity() {
        ServiceRequest request = serviceRequest("SR-CANONICAL", "WAITING_ACCEPTANCE", supplier, owner,
            LocalDate.now().plusDays(3));
        ServiceOrder serviceOrder = requestlessServiceOrder("SO-CANONICAL", supplier, "ACTIVE");
        serviceAct("SA-CANONICAL", serviceOrder, "APPROVED", BigDecimal.valueOf(250));
        PurchaseOrderItem workLine = worksLine("PO-WORK-CANONICAL", "WORK-LINE-CANONICAL", supplier);
        workAct("WA-CANONICAL", workLine, "APPROVED", BigDecimal.valueOf(4), BigDecimal.valueOf(400));
        serviceClaim(ServiceClaimOriginType.PO_SERVICE, null, serviceOrder.getId(), null);
        serviceClaim(ServiceClaimOriginType.PO_WORKS, null, null, workLine.getId());
        entityManager.flush();
        entityManager.clear();

        CaseFilter filter = CaseFilter.all().withQueue("ALL");
        var page = repository.findCases(filter, PageRequest.of(0, 20));

        assertThat(repository.count(filter)).isEqualTo(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::originType)
            .containsExactlyInAnyOrder(OriginType.SERVICE_REQUEST, OriginType.PO_SERVICE, OriginType.PO_WORKS);
        assertThat(page.getContent()).filteredOn(row -> row.originType() == OriginType.SERVICE_REQUEST)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.caseId()).isEqualTo(request.getId());
                assertThat(row.originId()).isEqualTo(request.getId());
                assertThat(row.requestId()).isEqualTo(request.getId());
            });
        assertThat(page.getContent()).filteredOn(row -> row.originType() == OriginType.PO_SERVICE)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.caseId()).isEqualTo(serviceOrder.getId());
                assertThat(row.originId()).isEqualTo(serviceOrder.getId());
                assertThat(row.requestId()).isNull();
                assertThat(row.serviceOrderId()).isEqualTo(serviceOrder.getId());
                assertThat(row.acceptanceMode()).isEqualTo("AMOUNT");
                assertThat(row.openClaimCount()).isEqualTo(1);
                assertThat(row.nextAction()).isEqualTo("RESOLVE_CLAIMS");
            });
        assertThat(page.getContent()).filteredOn(row -> row.originType() == OriginType.PO_WORKS)
            .singleElement()
            .satisfies(row -> {
                assertThat(row.caseId()).isEqualTo(workLine.getId());
                assertThat(row.originId()).isEqualTo(workLine.getId());
                assertThat(row.purchaseOrderItemId()).isEqualTo(workLine.getId());
                assertThat(row.acceptanceMode()).isEqualTo("QUANTITY");
                assertThat(row.openClaimCount()).isEqualTo(1);
                assertThat(row.nextAction()).isEqualTo("RESOLVE_CLAIMS");
            });

        CaseFilter openClaims = CaseFilter.all().withHasOpenClaims(true);
        assertThat(repository.count(openClaims)).isEqualTo(2);
        assertThat(repository.findCases(openClaims, PageRequest.of(0, 20)).getContent())
            .extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::originType)
            .containsExactlyInAnyOrder(OriginType.PO_SERVICE, OriginType.PO_WORKS);
        assertThat(repository.findCase(request.getId(), CaseFilter.all()))
            .get()
            .extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::caseId)
            .isEqualTo(request.getId());
    }

    @Test
    void sharedPredicateAppliesCompletedOverdueOwnerSupplierOriginStatusAndSearchFilters() {
        ServiceRequest overdue = serviceRequest("SR-FILTER-OVERDUE", "IN_PROGRESS", supplier, owner,
            LocalDate.now().minusDays(1));
        serviceRequest("SR-FILTER-DUE-TODAY", "IN_PROGRESS", supplier, owner, LocalDate.now());
        serviceRequest("SR-FILTER-OTHER-SUPPLIER", "IN_PROGRESS", otherSupplier, owner,
            LocalDate.now().minusDays(2));
        serviceRequest("SR-FILTER-COMPLETED", "CLOSED", supplier, owner, LocalDate.now().minusDays(4));
        ServiceOrder paidOrder = requestlessServiceOrder("SO-FILTER-COMPLETED", supplier, "ACTIVE");
        serviceAct("SA-FILTER-PAID", paidOrder, "PAID", BigDecimal.valueOf(500));
        entityManager.flush();

        CaseFilter overdueFilter = CaseFilter.all()
            .withQueue("ALL")
            .withOriginType(OriginType.SERVICE_REQUEST)
            .withOwnerId(owner.getId())
            .withSupplierId(supplier.getId())
            .withStatuses(List.of("IN_PROGRESS"))
            .withOverdue(true)
            .withSearch("  filter-overDUE  ");

        var overduePage = repository.findCases(overdueFilter, PageRequest.of(0, 20));
        assertThat(repository.count(overdueFilter)).isEqualTo(overduePage.getTotalElements()).isEqualTo(1);
        assertThat(overduePage.getContent()).singleElement()
            .satisfies(row -> {
                assertThat(row.caseId()).isEqualTo(overdue.getId());
                assertThat(row.overdue()).isTrue();
                assertThat(row.slaState()).isEqualTo("OVERDUE");
            });

        CaseFilter completed = CaseFilter.all().withQueue("COMPLETED");
        var completedPage = repository.findCases(completed, PageRequest.of(0, 20));
        assertThat(repository.count(completed)).isEqualTo(completedPage.getTotalElements()).isEqualTo(2);
        assertThat(completedPage.getContent()).extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::code)
            .containsExactlyInAnyOrder("SR-FILTER-COMPLETED", "SO-FILTER-COMPLETED");
    }

    @Test
    void databaseSortAndPagingReturnStablePageBeyondFirstFiftyCases() {
        for (int index = 0; index < 61; index++) {
            ServiceRequest request = serviceRequest(
                "SR-PAGE-%03d".formatted(index),
                "IN_PROGRESS",
                supplier,
                owner,
                LocalDate.now().plusDays(index + 1)
            );
            request.setCreatedAt(LocalDateTime.now().minusDays(100).plusMinutes(index));
        }
        entityManager.flush();

        CaseFilter filter = CaseFilter.all()
            .withQueue("ALL")
            .withOriginType(OriginType.SERVICE_REQUEST)
            .withSearch("SR-PAGE-");
        var page = repository.findCases(
            filter,
            PageRequest.of(2, 25, Sort.by(Sort.Direction.ASC, "code"))
        );

        assertThat(repository.count(filter)).isEqualTo(61);
        assertThat(page.getTotalElements()).isEqualTo(61);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(11);
        assertThat(page.getContent()).extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::code)
            .containsExactly(
                "SR-PAGE-050", "SR-PAGE-051", "SR-PAGE-052", "SR-PAGE-053", "SR-PAGE-054",
                "SR-PAGE-055", "SR-PAGE-056", "SR-PAGE-057", "SR-PAGE-058", "SR-PAGE-059",
                "SR-PAGE-060"
            );
    }

    @Test
    void claimOnlyFailedIntegrationEventsPlaceEveryOriginInIntegrationQueue() {
        ServiceRequest request = serviceRequest("SR-CLAIM-INTEGRATION", "IN_PROGRESS", supplier, owner,
            LocalDate.now().plusDays(2));
        ServiceOrder serviceOrder = requestlessServiceOrder("SO-CLAIM-INTEGRATION", supplier, "ACTIVE");
        PurchaseOrderItem workLine = worksLine("PO-CLAIM-INTEGRATION", "WORK-CLAIM-INTEGRATION", supplier);
        ServiceClaim requestClaim = serviceClaim(
            ServiceClaimOriginType.SERVICE_REQUEST, request.getId(), null, null);
        ServiceClaim serviceClaim = serviceClaim(
            ServiceClaimOriginType.PO_SERVICE, null, serviceOrder.getId(), null);
        ServiceClaim workClaim = serviceClaim(
            ServiceClaimOriginType.PO_WORKS, null, null, workLine.getId());
        failedClaimIntegration(requestClaim);
        failedClaimIntegration(serviceClaim);
        failedClaimIntegration(workClaim);
        entityManager.flush();

        CaseFilter integration = CaseFilter.all().withQueue("INTEGRATION");
        var page = repository.findCases(integration, PageRequest.of(0, 20));

        assertThat(repository.count(integration)).isEqualTo(3);
        assertThat(page.getContent()).extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::originType)
            .containsExactlyInAnyOrder(OriginType.SERVICE_REQUEST, OriginType.PO_SERVICE, OriginType.PO_WORKS);
    }

    @Test
    void managementBucketsUseCanonicalDatabaseAggregatesAndExposeMixedCurrencyWithoutSummingIt() {
        ServiceRequest uzs = serviceRequest("SR-MGMT-UZS", "IN_PROGRESS", supplier, owner, LocalDate.now().plusDays(3));
        uzs.setCurrencyCode("UZS");
        uzs.setCategory("MAINTENANCE");
        ServiceRequest usd = serviceRequest("SR-MGMT-USD", "WAITING_ACCEPTANCE", supplier, owner, LocalDate.now().minusDays(8));
        usd.setCurrencyCode("USD");
        usd.setCategory("INSPECTION");
        entityManager.flush();

        var buckets = repository.managementBuckets(CaseFilter.all());

        assertThat(buckets).extracting(ServiceCaseWorkspaceQueryRepository.ManagementBucket::dimension)
            .contains("LIFECYCLE", "BACKLOG", "EXPOSURE", "SUPPLIER", "DEPARTMENT", "CONTRACT", "CATEGORY", "QUALITY_METRIC");
        assertThat(buckets)
            .filteredOn(bucket -> bucket.dimension().equals("EXPOSURE") && bucket.key().equals("OUTSTANDING"))
            .singleElement()
            .satisfies(bucket -> {
                assertThat(bucket.caseCount()).isEqualTo(2);
                assertThat(bucket.currencyCount()).isEqualTo(2);
                assertThat(bucket.currencyCode()).isNull();
                assertThat(bucket.amount()).isEqualByComparingTo("2000");
            });
        CaseFilter departmentFilter = CaseFilter.all().withDepartmentId(department.getId()).withCurrencyCode("USD");
        assertThat(repository.count(departmentFilter)).isEqualTo(1);
        assertThat(repository.findCases(departmentFilter, PageRequest.of(0, 10)).getContent())
            .extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::caseId)
            .containsExactly(usd.getId());
        CaseFilter categoryFilter = CaseFilter.all().withCategory("inspection");
        assertThat(repository.count(categoryFilter)).isEqualTo(1);
        assertThat(repository.findCases(categoryFilter, PageRequest.of(0, 10)).getContent())
            .extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::caseId)
            .containsExactly(usd.getId());
    }

    @Test
    void cycleTimeUsesTheWholeElapsedDurationWhenACompletedCaseSpansMoreThanOneMonth() {
        ServiceRequest request = serviceRequest(
            "SR-MGMT-LONG-CYCLE",
            "CLOSED",
            supplier,
            owner,
            LocalDate.of(2026, 6, 15)
        );
        entityManager.flush();
        entityManager.createNativeQuery("""
                UPDATE service_requests
                   SET created_at = TIMESTAMP '2026-05-01 00:00:00',
                       updated_at = TIMESTAMP '2026-06-15 00:00:00'
                 WHERE id = :requestId
                """)
            .setParameter("requestId", request.getId())
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.managementBuckets(CaseFilter.all()))
            .filteredOn(bucket -> bucket.dimension().equals("QUALITY_METRIC")
                && bucket.key().equals("CYCLE_TIME_DAYS"))
            .singleElement()
            .satisfies(bucket -> {
                assertThat(bucket.caseCount()).isEqualTo(1);
                assertThat(bucket.amount()).isEqualByComparingTo("45");
            });
    }

    @Test
    void poOriginsUseAggregateActStateInsteadOfCompletingFromOnlyTheNewestPaidAct() {
        ServiceOrder serviceOrder = requestlessServiceOrder("SO-MULTI-ACT", supplier, "ACTIVE");
        serviceAct("SA-MULTI-DRAFT", serviceOrder, "DRAFT", BigDecimal.valueOf(250));
        serviceAct("SA-MULTI-PAID", serviceOrder, "PAID", BigDecimal.valueOf(250));

        PurchaseOrderItem workLine = worksLine("PO-WORK-MULTI-ACT", "WORK-MULTI-ACT", supplier);
        workAct("WA-MULTI-DRAFT", workLine, "DRAFT", BigDecimal.valueOf(5), BigDecimal.valueOf(500));
        workAct("WA-MULTI-PAID", workLine, "PAID", BigDecimal.valueOf(5), BigDecimal.valueOf(500));
        entityManager.flush();
        entityManager.clear();

        var rows = repository.findCases(CaseFilter.all(), PageRequest.of(0, 10)).getContent();

        assertThat(rows).filteredOn(row -> row.caseId().equals(serviceOrder.getId()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.status()).isEqualTo("ACT_PENDING");
                assertThat(row.phase()).isEqualTo("ACCEPTANCE");
                assertThat(row.nextAction()).isEqualTo("COMPLETE_ACT_REVIEW");
                assertThat(row.completedAt()).isNull();
            });
        assertThat(rows).filteredOn(row -> row.caseId().equals(workLine.getId()))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.status()).isEqualTo("APPROVAL_PENDING");
                assertThat(row.phase()).isEqualTo("ACCEPTANCE");
                assertThat(row.nextAction()).isEqualTo("COMPLETE_ACT_REVIEW");
                assertThat(row.completedAt()).isNull();
            });
        assertThat(repository.count(CaseFilter.all().withQueue("COMPLETED"))).isZero();
    }

    @Test
    void completedParentPurchaseOrderDoesNotBypassAnOpenWorksClaim() {
        PurchaseOrderItem workLine = worksLine("PO-WORK-PARENT-COMPLETED", "WORK-PARENT-COMPLETED", supplier);
        PurchaseOrder purchaseOrder = entityManager.find(PurchaseOrder.class, workLine.getPurchaseOrderId());
        purchaseOrder.setStatus("COMPLETED");
        workAct("WA-PARENT-COMPLETED", workLine, "PAID", BigDecimal.TEN, BigDecimal.valueOf(1_000));
        serviceClaim(ServiceClaimOriginType.PO_WORKS, null, null, workLine.getId());
        entityManager.flush();
        entityManager.clear();

        var row = repository.findCase(workLine.getId(), CaseFilter.all()).orElseThrow();

        assertThat(row.phase()).isEqualTo("REWORK");
        assertThat(row.nextAction()).isEqualTo("RESOLVE_CLAIMS");
        assertThat(row.completedAt()).isNull();
        assertThat(repository.count(CaseFilter.all().withQueue("COMPLETED"))).isZero();
        assertThat(repository.count(CaseFilter.all().withQueue("REWORK"))).isEqualTo(1);
    }

    @Test
    void paidWorksQuantityWithoutFullFinancialCoverageRemainsInAcceptance() {
        PurchaseOrderItem workLine = worksLine("PO-WORK-UNDERPAID", "WORK-UNDERPAID", supplier);
        workAct("WA-WORK-UNDERPAID", workLine, "PAID", BigDecimal.TEN, BigDecimal.valueOf(900));
        entityManager.flush();
        entityManager.clear();

        var row = repository.findCase(workLine.getId(), CaseFilter.all()).orElseThrow();

        assertThat(row.status()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(row.phase()).isEqualTo("ACCEPTANCE");
        assertThat(row.remainingQuantity()).isZero();
        assertThat(row.remainingAmount()).isEqualByComparingTo("100");
        assertThat(row.nextAction()).isEqualTo("RECONCILE_WORK_VALUE");
        assertThat(row.blockerCount()).isEqualTo(1);
        assertThat(row.completedAt()).isNull();
        assertThat(repository.count(CaseFilter.all().withQueue("COMPLETED"))).isZero();
        assertThat(repository.count(CaseFilter.all().withQueue("ACCEPTANCE"))).isEqualTo(1);
    }

    @Test
    void replacementActClearsCurrentReworkWhileKeepingHistoricalReworkSignal() {
        ServiceOrder serviceOrder = requestlessServiceOrder("SO-REPLACED-ACT", supplier, "ACTIVE");
        serviceAct("SA-REPLACED-REJECTED", serviceOrder, "REJECTED", BigDecimal.valueOf(500));
        serviceAct("SA-REPLACED-PAID", serviceOrder, "PAID", BigDecimal.valueOf(500));
        entityManager.flush();
        entityManager.clear();

        var row = repository.findCase(serviceOrder.getId(), CaseFilter.all()).orElseThrow();

        assertThat(row.phase()).isEqualTo("COMPLETED");
        assertThat(row.blockerCount()).isZero();
        assertThat(row.nextAction()).isEqualTo("NONE");
        assertThat(repository.count(CaseFilter.all().withQueue("REWORK"))).isZero();
        assertThat(repository.count(CaseFilter.all().withQueue("COMPLETED").withHasRework(true))).isEqualTo(1);
    }

    @Test
    void paymentForecastUsesApprovedAndSentUnpaidLiabilityInsteadOfRemainingAcceptanceScope() {
        ServiceOrder serviceOrder = requestlessServiceOrder("SO-PAYABLE", supplier, "ACTIVE");
        serviceAct("SA-PAYABLE", serviceOrder, "APPROVED", BigDecimal.valueOf(500));
        PurchaseOrderItem workLine = worksLine("PO-WORK-PAYABLE", "WORK-PAYABLE", supplier);
        workAct("WA-PAYABLE", workLine, "SENT_TO_PAYMENT", BigDecimal.TEN, BigDecimal.valueOf(1_000));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.managementBuckets(CaseFilter.all()))
            .filteredOn(bucket -> bucket.dimension().equals("PAYMENT"))
            .singleElement()
            .satisfies(bucket -> {
                assertThat(bucket.caseCount()).isEqualTo(2);
                assertThat(bucket.amount()).isEqualByComparingTo("1500");
                assertThat(bucket.currencyCode()).isEqualTo("UZS");
            });
    }

    @Test
    void historicalReworkFilterSurvivesClearedCurrentRejectedBalances() {
        ServiceRequest request = serviceRequest(
            "SR-HISTORICAL-REWORK", "IN_PROGRESS", supplier, owner, LocalDate.now().plusDays(2)
        );
        request.setRejectedAmount(BigDecimal.ZERO);
        request.setRejectedQuantity(BigDecimal.ZERO);
        ServiceReviewHistory history = base(
            new ServiceReviewHistory(), "SRH-HISTORICAL-REWORK", "Historical rejection", "REJECTED"
        );
        history.setServiceRequestId(request.getId());
        history.setReviewType("TECHNICAL");
        history.setDecision("REJECTED");
        history.setActor("reviewer");
        history.setActedAt(LocalDateTime.now().minusDays(1));
        persist(history);
        entityManager.flush();
        entityManager.clear();

        CaseFilter historicalRework = CaseFilter.all().withHasRework(true);
        assertThat(repository.count(historicalRework)).isEqualTo(1);
        assertThat(repository.findCases(historicalRework, PageRequest.of(0, 10)).getContent())
            .extracting(ServiceCaseWorkspaceQueryRepository.CaseRow::caseId)
            .containsExactly(request.getId());
        assertThat(repository.count(CaseFilter.all().withHasRework(false))).isZero();
    }

    private ServiceRequest serviceRequest(
        String code,
        String status,
        Supplier requestSupplier,
        User responsible,
        LocalDate dueDate
    ) {
        ServiceRequest request = base(new ServiceRequest(), code, "Service " + code, status);
        request.setNumber(code);
        request.setTitle("Service " + code);
        request.setSupplierId(requestSupplier.getId());
        request.setContractId(contract.getId());
        request.setDepartmentId(department.getId());
        request.setResponsibleUserId(responsible.getId());
        request.setPlannedEndDate(dueDate);
        request.setEstimatedAmount(BigDecimal.valueOf(1_000));
        request.setApprovedAmount(BigDecimal.valueOf(1_000));
        return persist(request);
    }

    private ServiceOrder requestlessServiceOrder(String code, Supplier orderSupplier, String status) {
        PurchaseOrder po = purchaseOrder("PO-" + code, RequestType.SERVICE, orderSupplier);
        ServiceOrder order = base(new ServiceOrder(), code, "Service order " + code, status);
        order.setSupplierId(orderSupplier.getId());
        order.setContractId(contract.getId());
        order.setPurchaseOrderId(po.getId());
        order.setServiceType("SERVICE");
        order.setTotalAmount(BigDecimal.valueOf(500));
        order.setCurrencyCode("UZS");
        order.setCreatedBy(owner.getUsername());
        return persist(order);
    }

    private ServiceAcceptanceAct serviceAct(String code, ServiceOrder order, String status, BigDecimal amount) {
        ServiceAcceptanceAct act = base(new ServiceAcceptanceAct(), code, "Service act " + code, status);
        act.setServiceOrderId(order.getId());
        act.setPurchaseOrderId(order.getPurchaseOrderId());
        act.setContractId(contract.getId());
        act.setAmount(amount);
        act.setVatAmount(BigDecimal.ZERO);
        act.setVatRate(BigDecimal.ZERO);
        return persist(act);
    }

    private PurchaseOrderItem worksLine(String poCode, String lineCode, Supplier poSupplier) {
        PurchaseOrder po = purchaseOrder(poCode, RequestType.SERVICE, poSupplier);
        PurchaseOrderItem line = base(new PurchaseOrderItem(), lineCode, "Works line " + lineCode, "ACTIVE");
        line.setPurchaseOrderId(po.getId());
        line.setLineType(ProcurementLineType.WORK);
        line.setQuantity(BigDecimal.TEN);
        line.setUnitPrice(BigDecimal.valueOf(100));
        line.setUnit("HOUR");
        line.setServiceDescription("Maintenance works " + lineCode);
        line.setStage("STAGE-1");
        return persist(line);
    }

    private WorkCompletionAct workAct(
        String code,
        PurchaseOrderItem line,
        String status,
        BigDecimal quantity,
        BigDecimal amount
    ) {
        WorkCompletionAct act = base(new WorkCompletionAct(), code, "Work act " + code, status);
        act.setPurchaseOrderId(line.getPurchaseOrderId());
        act.setPurchaseOrderItemId(line.getId());
        act.setContractId(contract.getId());
        act.setCompletedQuantity(quantity);
        act.setAmount(amount);
        act.setMilestone(line.getStage());
        return persist(act);
    }

    private ServiceClaim serviceClaim(
        ServiceClaimOriginType originType,
        UUID serviceRequestId,
        UUID serviceOrderId,
        UUID purchaseOrderItemId
    ) {
        ServiceClaim claim = base(new ServiceClaim(), "SCL-" + UUID.randomUUID().toString().substring(0, 8),
            "Open canonical claim", "SENT");
        claim.setOriginType(originType);
        claim.setServiceRequestId(serviceRequestId);
        claim.setServiceOrderId(serviceOrderId);
        claim.setPurchaseOrderItemId(purchaseOrderItemId);
        claim.setSupplierId(supplier.getId());
        claim.setAssignedTo(owner.getId());
        claim.setClaimType("QUALITY");
        claim.setSeverity("HIGH");
        claim.setPenaltyAmount(BigDecimal.ZERO);
        claim.setCurrencyCode("UZS");
        return persist(claim);
    }

    private IntegrationEvent failedClaimIntegration(ServiceClaim claim) {
        IntegrationEvent event = base(
            new IntegrationEvent(), "INT-" + UUID.randomUUID().toString().substring(0, 8),
            "Failed claim handoff", "FAILED"
        );
        event.setEventType("SERVICE_CLAIM_HANDOFF");
        event.setAggregateType("ServiceClaim");
        event.setAggregateId(claim.getId());
        event.setIdempotencyKey("claim-integration-" + claim.getId());
        event.setPayload("{}");
        event.setRetryCount(1);
        event.setLastError("transport unavailable");
        return persist(event);
    }

    private PurchaseOrder purchaseOrder(String code, RequestType type, Supplier poSupplier) {
        PurchaseOrder po = base(new PurchaseOrder(), code, "Purchase order " + code, "SENT");
        po.setSupplierId(poSupplier.getId());
        po.setContractId(contract.getId());
        po.setRequestType(type);
        po.setTotalAmount(BigDecimal.valueOf(1_000));
        po.setCurrencyCode("UZS");
        po.setOrderDate(LocalDate.now().minusDays(10));
        po.setExpectedDeliveryDate(LocalDate.now().plusDays(5));
        po.setCreatedBy(owner.getUsername());
        return persist(po);
    }

    private <T extends BusinessEntity> T base(T entity, String code, String name, String status) {
        entity.setTenantId(TENANT);
        entity.setCompanyId(companyId);
        entity.setCode(code);
        entity.setName(name);
        entity.setStatus(status);
        return entity;
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
}
