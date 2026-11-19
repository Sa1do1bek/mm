package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractSpecification;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.ContractStatus;
import com.enterprise.mm.enums.DemandPeriodType;
import com.enterprise.mm.enums.DemandPlanningCycleStatus;
import com.enterprise.mm.enums.DemandPlanStatus;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseOrderStatus;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.DemandPlanRepository;
import com.enterprise.mm.repository.DemandPlanningCycleRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.service.ContractPurchaseOrderService;
import com.enterprise.mm.service.RfqAwardService;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierQuotationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RfqAwardContractIntegrationTest {
    static final String TENANT_ID = "default";
    static final UUID COMPANY_ID = CompanyContext.DEFAULT_COMPANY_ID;

    @Autowired RfqAwardService awardService;
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired RFQPurchaseRequestSourceRepository sourceRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierQuotationRepository quotationRepository;
    @Autowired SupplierQuotationItemRepository quotationItemRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired ContractSpecificationRepository specificationRepository;
    @Autowired DemandPlanningCycleRepository demandPlanningCycleRepository;
    @Autowired DemandPlanRepository demandPlanRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired ContractPurchaseOrderService contractPurchaseOrderService;
    @Autowired SupplierQuotationService supplierQuotationService;
    @MockBean SupplierAccreditationService supplierAccreditationService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void awardCreatesIdempotentDraftContractWithSourceLineageAndNoPo() {
        setContext();
        Material material = material("MAT-RFQ-AWARD-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-AWARD-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("RFQ-AWARD-" + suffix());
        rfq.setDescription("Office supplies for the IT department.");
        rfqRepository.saveAndFlush(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);
        source(rfq, rfqItem, pr, prItem);
        Supplier supplier = supplier("SUP-RFQ-AWARD-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        SupplierQuotationItem quotationItem = quotationItem(quotation, rfqItem, material);
        long poCount = purchaseOrderRepository.count();

        var first = awardService.award(quotation.getId(), "best offer");
        var second = awardService.award(quotation.getId(), "retry");

        assertThat(second.contractId()).isEqualTo(first.contractId());
        assertThat(purchaseOrderRepository.count()).isEqualTo(poCount);
        RFQ awardedRfq = rfqRepository.findByIdAndIsDeletedFalse(rfq.getId()).orElseThrow();
        assertThat(awardedRfq.getStatus()).isEqualTo("AWARDED");
        SupplierQuotation selected = quotationRepository.findByIdAndIsDeletedFalse(quotation.getId()).orElseThrow();
        assertThat(selected.isSelected()).isTrue();
        assertThat(selected.getStatus()).isEqualTo("SELECTED");
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(first.contractId()).orElseThrow();
        assertThat(contract.getDescription()).isEqualTo(rfq.getDescription());
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.DRAFT.name());
        assertThat(contract.getStartDate()).isNull();
        assertThat(contract.getEndDate()).isNull();
        assertThat(contract.getSourceRfqId()).isEqualTo(rfq.getId());
        assertThat(contract.getSourceQuotationId()).isEqualTo(quotation.getId());
        assertThat(contract.getLimitAmount()).isEqualByComparingTo("95.0000");
        List<ContractSpecification> specs = specificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TENANT_ID, contract.getId());
        assertThat(specs).singleElement().satisfies(spec -> {
            assertThat(spec.getMaterialId()).isEqualTo(material.getId());
            assertThat(spec.getSourceRfqItemId()).isEqualTo(rfqItem.getId());
            assertThat(spec.getSourceQuotationItemId()).isEqualTo(quotationItem.getId());
            assertThat(spec.getQuantityLimit()).isEqualByComparingTo("10.0000");
            assertThat(spec.getPrice()).isEqualByComparingTo("9.5000");
        });
    }

    @Test
    void standardRfqExposesContractAwardAndCreatesCanonicalContractLineage() {
        setContext();
        Material material = material("MAT-STANDARD-RFQ-AWARD-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-STANDARD-RFQ-AWARD-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("STANDARD-RFQ-AWARD-" + suffix());
        rfq.setAggregatedTender(false);
        rfq.setAggregationMode(null);
        rfq.setPurchaseRequestId(pr.getId());
        rfqRepository.saveAndFlush(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);
        Supplier supplier = supplier("SUP-STANDARD-RFQ-AWARD-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        SupplierQuotationItem quotationItem = quotationItem(quotation, rfqItem, material);

        var comparison = supplierQuotationService.compare(rfq.getId());

        assertThat(comparison).singleElement().satisfies(candidate -> {
            assertThat(candidate.isSelectCreatePoReady()).isTrue();
            assertThat(candidate.isAwardContractReady()).isTrue();
            assertThat(candidate.getAwardContractBlockedReason()).isNull();
        });

        var award = awardService.award(quotation.getId(), "recommended by evaluation protocol");

        Contract contract = contractRepository.findByIdAndIsDeletedFalse(award.contractId()).orElseThrow();
        assertThat(contract.getSourceRfqId()).isEqualTo(rfq.getId());
        assertThat(contract.getSourceQuotationId()).isEqualTo(quotation.getId());
        assertThat(contract.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            TENANT_ID, COMPANY_ID, rfq.getId(), RfqSourceAllocationStatus.ACTIVE.name()))
            .singleElement()
            .satisfies(source -> {
                assertThat(source.getRfqItemId()).isEqualTo(rfqItem.getId());
                assertThat(source.getPurchaseRequestId()).isEqualTo(pr.getId());
                assertThat(source.getPurchaseRequestItemId()).isEqualTo(prItem.getId());
            });
        assertThat(specificationRepository.findByTenantIdAndContractIdAndIsDeletedFalse(TENANT_ID, contract.getId()))
            .singleElement()
            .satisfies(spec -> {
                assertThat(spec.getSourceRfqItemId()).isEqualTo(rfqItem.getId());
                assertThat(spec.getSourceQuotationItemId()).isEqualTo(quotationItem.getId());
            });
    }

    @Test
    void standardRfqWithoutRecoverablePurchaseRequestLineageReturnsCorrectiveBusinessError() {
        setContext();
        Material material = material("MAT-STANDARD-RFQ-NO-LINEAGE-" + suffix(), "EA");
        RFQ rfq = rfq("STANDARD-RFQ-NO-LINEAGE-" + suffix());
        rfq.setAggregatedTender(false);
        rfq.setAggregationMode(null);
        rfq.setPurchaseRequestId(null);
        rfqRepository.saveAndFlush(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);
        Supplier supplier = supplier("SUP-STANDARD-RFQ-NO-LINEAGE-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        quotationItem(quotation, rfqItem, material);

        assertThatThrownBy(() -> awardService.award(quotation.getId(), "recommended by evaluation protocol"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("RFQ has no recoverable purchase request lineage")
            .extracting("messageKey")
            .isEqualTo("rfq.award.lineage_unrecoverable");
        assertThat(contractRepository.findByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(
            TENANT_ID, COMPANY_ID, rfq.getId())).isEmpty();
    }

    @Test
    void awardIsNullSafeWhenSupplierNameIsMissing() {
        setContext();
        Material material = material("MAT-RFQ-NULL-SUPPLIER-NAME-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-NULL-SUPPLIER-NAME-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("RFQ-NULL-SUPPLIER-NAME-" + suffix());
        RFQItem rfqItem = rfqItem(rfq, material);
        source(rfq, rfqItem, pr, prItem);
        Supplier supplier = supplier("SUP-RFQ-NULL-SUPPLIER-NAME-" + suffix());
        supplier.setName(null);
        supplierRepository.saveAndFlush(supplier);
        SupplierQuotation quotation = quotation(rfq, supplier);
        quotationItem(quotation, rfqItem, material);

        var response = awardService.award(quotation.getId(), "recommended by evaluation protocol");

        assertThat(response.contractId()).isNotNull();
    }

    @Test
    void awardEventIsNullSafeForLegacyDocumentCodes() {
        setContext();
        Material material = material("MAT-RFQ-NULL-CODES-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-NULL-CODES-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("RFQ-NULL-CODES-" + suffix());
        rfq.setCode(null);
        rfqRepository.saveAndFlush(rfq);
        RFQItem rfqItem = rfqItem(rfq, material);
        source(rfq, rfqItem, pr, prItem);
        Supplier supplier = supplier("SUP-RFQ-NULL-CODES-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        quotation.setCode(null);
        quotationRepository.saveAndFlush(quotation);
        quotationItem(quotation, rfqItem, material);

        var response = awardService.award(quotation.getId(), "recommended by evaluation protocol");

        assertThat(response.contractId()).isNotNull();
        assertThat(contractRepository.findByIdAndIsDeletedFalse(response.contractId()))
            .get()
            .extracting(Contract::getSourceRfqId, Contract::getSourceQuotationId)
            .containsExactly(rfq.getId(), quotation.getId());
    }

    @Test
    void awardUsesRfqItemUnitWhenSubmittedQuotationItemStoredWithoutUnit() {
        setContext();
        Material material = material("MAT-RFQ-AWARD-UNIT-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-AWARD-UNIT-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("RFQ-AWARD-UNIT-" + suffix());
        RFQItem rfqItem = rfqItem(rfq, material);
        source(rfq, rfqItem, pr, prItem);
        Supplier supplier = supplier("SUP-RFQ-AWARD-UNIT-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        SupplierQuotationItem quotationItem = quotationItem(quotation, rfqItem, material);
        quotationItem.setUnit(null);
        quotationItemRepository.saveAndFlush(quotationItem);

        var response = awardService.award(quotation.getId(), "portal submitted before unit inheritance fix");

        List<ContractSpecification> specs = specificationRepository
            .findByTenantIdAndContractIdAndIsDeletedFalse(TENANT_ID, response.contractId());
        assertThat(specs).singleElement().satisfies(spec -> {
            assertThat(spec.getSourceRfqItemId()).isEqualTo(rfqItem.getId());
            assertThat(spec.getSourceQuotationItemId()).isEqualTo(quotationItem.getId());
            assertThat(spec.getUnit()).isEqualTo("EA");
        });
    }

    @Test
    void activeAwardContractCreatesDraftPurchaseOrderFromSpecifications() {
        setContext();
        Material material = material("MAT-RFQ-AWARD-PO-" + suffix(), "EA");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-AWARD-PO-" + suffix());
        PurchaseRequestItem prItem = item(pr, material);
        RFQ rfq = rfq("RFQ-AWARD-PO-" + suffix());
        RFQItem rfqItem = rfqItem(rfq, material);
        source(rfq, rfqItem, pr, prItem);
        Supplier supplier = supplier("SUP-RFQ-AWARD-PO-" + suffix());
        SupplierQuotation quotation = quotation(rfq, supplier);
        quotationItem(quotation, rfqItem, material);
        var award = awardService.award(quotation.getId(), "best offer");
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(award.contractId()).orElseThrow();
        contract.setStatus(ContractStatus.ACTIVE.name());
        contractRepository.saveAndFlush(contract);

        var first = contractPurchaseOrderService.createFromContract(contract.getId());
        var second = contractPurchaseOrderService.createFromContract(contract.getId());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT.name());
        assertThat(first.getSupplierId()).isEqualTo(supplier.getId());
        assertThat(first.getContractId()).isEqualTo(contract.getId());
        assertThat(first.getRequestType()).isEqualTo(RequestType.GOODS.name());
        assertThat(first.getCurrencyCode()).isEqualTo("UZS");
        assertThat(first.getTotalAmount()).isEqualByComparingTo("95.0000");
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TENANT_ID, first.getId());
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.getMaterialId()).isEqualTo(material.getId());
            assertThat(item.getLineType()).isEqualTo(ProcurementLineType.MATERIAL);
            assertThat(item.getUnit()).isEqualTo("EA");
            assertThat(item.getQuantity()).isEqualByComparingTo("10.0000");
            assertThat(item.getUnitPrice()).isEqualByComparingTo("9.5000");
        });
    }

    private void setContext() {
        TenantContext.setTenant(TENANT_ID);
        CompanyContext.setCompany(COMPANY_ID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "rfq-award",
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
        pr.setStatus(PurchaseRequestStatus.FULLY_SENT_TO_TENDER.name());
        pr.setRequestType(RequestType.GOODS);
        pr.setCurrencyCode("UZS");
        pr.setRequiredDate(LocalDate.now().plusDays(5));
        pr.setTotalAmount(new BigDecimal("100.0000"));
        pr.setDemandPlanId(approvedDemandPlanId("DP-" + code));
        return purchaseRequestRepository.saveAndFlush(pr);
    }

    private UUID approvedDemandPlanId(String code) {
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setCode("DPC-" + code);
        cycle.setName("Approved demand planning cycle " + code);
        cycle.setStatus(DemandPlanningCycleStatus.BUDGET_APPROVED.name());
        cycle.setPlanYear(Year.now().getValue());
        cycle.setPeriodType(DemandPeriodType.YEAR.name());
        cycle.setPeriodStart(LocalDate.now().withDayOfYear(1));
        cycle.setPeriodEnd(LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()));
        cycle.setCollectionStartDate(LocalDate.now().minusDays(10));
        cycle.setCollectionEndDate(LocalDate.now().minusDays(1));
        cycle.setBudgetReviewDeadline(LocalDate.now());
        cycle.setCurrencyCode("UZS");
        cycle = demandPlanningCycleRepository.saveAndFlush(cycle);

        DemandPlan plan = new DemandPlan();
        plan.setCode(code);
        plan.setName("Approved demand plan " + code);
        plan.setStatus(DemandPlanStatus.APPROVED.name());
        plan.setPlanningCycle(cycle);
        plan.setRequestType(RequestType.GOODS);
        plan.setPlanYear(Year.now().getValue());
        plan.setPeriodType(DemandPeriodType.YEAR.name());
        plan.setPeriodStart(LocalDate.now().withDayOfYear(1));
        plan.setPeriodEnd(LocalDate.now().withDayOfYear(LocalDate.now().lengthOfYear()));
        plan.setCurrencyCode("UZS");
        plan.setTotalPlannedAmount(BigDecimal.ZERO);
        plan.setTotalApprovedAmount(BigDecimal.ZERO);
        return demandPlanRepository.saveAndFlush(plan).getId();
    }

    private PurchaseRequestItem item(PurchaseRequest pr, Material material) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        item.setEstimatedPrice(BigDecimal.TEN);
        return purchaseRequestItemRepository.saveAndFlush(item);
    }

    private RFQ rfq(String code) {
        RFQ rfq = new RFQ();
        rfq.setCode(code);
        rfq.setName(code);
        rfq.setStatus("OPEN");
        rfq.setAggregatedTender(true);
        rfq.setAggregationMode(RfqAggregationMode.MATERIAL);
        rfq.setSourcePurchaseRequestCount(1);
        rfq.setSourceItemCount(1);
        rfq.setCurrencyCode("UZS");
        rfq.setTenderProcedureType("SINGLE_SUPPLIER");
        rfq.setSelectionRule("SINGLE_SUPPLIER");
        rfq.setMinQuotationsRequired(1);
        return rfqRepository.saveAndFlush(rfq);
    }

    private RFQItem rfqItem(RFQ rfq, Material material) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setMaterialId(material.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        return rfqItemRepository.saveAndFlush(item);
    }

    private RFQPurchaseRequestSource source(RFQ rfq, RFQItem rfqItem, PurchaseRequest pr, PurchaseRequestItem prItem) {
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("SRC-" + suffix());
        source.setName(source.getCode());
        source.setStatus(RfqSourceAllocationStatus.ACTIVE.name());
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(pr.getId());
        source.setPurchaseRequestItemId(prItem.getId());
        source.setAllocatedQuantity(prItem.getQuantity());
        source.setSourceRequiredDate(pr.getRequiredDate());
        source.setSourceCurrencyCode(pr.getCurrencyCode());
        source.setSourceUnit(prItem.getUnit());
        source.setAllocatedAt(LocalDateTime.now());
        return sourceRepository.saveAndFlush(source);
    }

    private Supplier supplier(String code) {
        Supplier supplier = new Supplier();
        supplier.setCode(code);
        supplier.setName(code);
        supplier.setStatus("ACTIVE");
        supplier.setLegalName(code);
        return supplierRepository.saveAndFlush(supplier);
    }

    private SupplierQuotation quotation(RFQ rfq, Supplier supplier) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("Q-" + suffix());
        quotation.setName(quotation.getCode());
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfq.getId());
        quotation.setSupplierId(supplier.getId());
        quotation.setTotalAmount(new BigDecimal("95.0000"));
        quotation.setCurrencyCode("UZS");
        return quotationRepository.saveAndFlush(quotation);
    }

    private SupplierQuotationItem quotationItem(SupplierQuotation quotation, RFQItem rfqItem, Material material) {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setCode("QI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("SUBMITTED");
        item.setQuotationId(quotation.getId());
        item.setRfqItemId(rfqItem.getId());
        item.setMaterialId(material.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit("EA");
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(new BigDecimal("9.5000"));
        return quotationItemRepository.saveAndFlush(item);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
