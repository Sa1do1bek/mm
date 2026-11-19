package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqRequest;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqResponse;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.service.RfqAggregationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class RfqAggregationConcurrencyTest {
    static final String TENANT_ID = "default";
    static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired RfqAggregationService aggregationService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQPurchaseRequestSourceRepository sourceRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void concurrentDirectCreationIsRejectedWithoutAllocations() throws Exception {
        setContext();
        Material material = material("MAT-RFQ-CONC-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-CONC-" + suffix());
        PurchaseRequestItem item = item(pr, material);
        String groupKey = "MATERIAL|" + COMPANY_ID + "|UZS|TMB|" + material.getId() + "|EA";
        CreateAggregatedRfqRequest request = new CreateAggregatedRfqRequest(
            RfqAggregationMode.MATERIAL,
            groupKey,
            List.of(item.getId()),
            LocalDate.now().plusDays(7),
            "REQUEST_FOR_QUOTATION",
            "LOWEST_PRICE",
            1);
        long beforeRfqCount = rfqRepository.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<CreateAggregatedRfqResponse> successes = new CopyOnWriteArrayList<>();
        List<Throwable> conflicts = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> raceCreate(request, ready, start, successes, conflicts));
            Future<?> second = executor.submit(() -> raceCreate(request, ready, start, successes, conflicts));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes).isEmpty();
        assertThat(conflicts)
            .hasSize(2)
            .allSatisfy(conflict -> {
                assertThat(conflict).isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) conflict).getMessageKey())
                    .isEqualTo("procurement.pmd.required");
            });
        assertThat(sourceRepository.findByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
            TENANT_ID, COMPANY_ID, item.getId(), RfqSourceAllocationStatus.ACTIVE.name()))
            .isEmpty();
        assertThat(rfqRepository.count()).isEqualTo(beforeRfqCount);
    }

    private void raceCreate(
        CreateAggregatedRfqRequest request,
        CountDownLatch ready,
        CountDownLatch start,
        List<CreateAggregatedRfqResponse> successes,
        List<Throwable> conflicts
    ) {
        setContext();
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            successes.add(transactionTemplate.execute(status -> aggregationService.create(request)));
        } catch (Throwable ex) {
            conflicts.add(ex);
        } finally {
            clearContext();
        }
    }

    private void setContext() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "rfq-concurrency",
            "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private Material material(String code, String uomCode) {
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setCode(uomCode);
        uom.setName(code + "-uom");
        uom.setStatus("ACTIVE");
        uom = unitOfMeasureRepository.saveAndFlush(uom);
        MaterialGroup group = new MaterialGroup();
        group.setCode(code + "-grp");
        group.setName(code + "-grp");
        group.setStatus("ACTIVE");
        group = materialGroupRepository.saveAndFlush(group);
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(uom.getId());
        material.setMaterialGroupId(group.getId());
        return materialRepository.saveAndFlush(material);
    }

    private PurchaseRequest purchaseRequest(String code) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode(code);
        pr.setName(code);
        pr.setStatus(PurchaseRequestStatus.APPROVED.name());
        pr.setRequestType(RequestType.GOODS);
        pr.setCurrencyCode("UZS");
        pr.setRequiredDate(LocalDate.now().plusDays(5));
        pr.setTotalAmount(BigDecimal.ZERO);
        return purchaseRequestRepository.saveAndFlush(pr);
    }

    private PurchaseRequestItem item(PurchaseRequest pr, Material material) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setMaterialTypeSnapshot(material.getMaterialType());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.ONE);
        return purchaseRequestItemRepository.saveAndFlush(item);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
