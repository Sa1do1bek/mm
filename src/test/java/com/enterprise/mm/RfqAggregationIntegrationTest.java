package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.PurchaseRequestStatus;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.payload.RfqAggregationDtos.AggregationPreviewFilter;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.service.RfqAggregationQueryService;
import com.enterprise.mm.service.RfqAggregationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "rfq-aggregation-tester", roles = {"ADMIN", "MM_MANAGER"})
class RfqAggregationIntegrationTest {
    @Autowired RfqAggregationQueryService queryService;
    @Autowired RfqAggregationService aggregationService;
    @Autowired MaterialRepository materialRepository;
    @Autowired MaterialGroupRepository materialGroupRepository;
    @Autowired UnitOfMeasureRepository unitOfMeasureRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired PurchaseRequestItemRepository purchaseRequestItemRepository;
    @Autowired RFQRepository rfqRepository;
    @Autowired RFQItemRepository rfqItemRepository;
    @Autowired RFQPurchaseRequestSourceRepository sourceRepository;

    @Test
    void materialModeGroupsSameMaterialAndUnitAcrossApprovedPrs() {
        Material material = material("MAT-RFQ-GRP-" + suffix(), "kg");
        PurchaseRequest first = purchaseRequest("PR-RFQ-GRP-A-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequest second = purchaseRequest("PR-RFQ-GRP-B-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequestItem firstItem = item(first, material, " kg ", 2, 10);
        item(second, material, "kg", 3, 20);

        var page = queryService.preview(new AggregationPreviewFilter(
            RfqAggregationMode.MATERIAL, material.getId(), null, null, null, null, firstItem.getCode()), PageRequest.of(0, 10));

        assertThat(page.getContent()).singleElement().satisfies(group -> {
            assertThat(group.groupBy()).isEqualTo(RfqAggregationMode.MATERIAL);
            assertThat(group.currencyCode()).isEqualTo("UZS");
            assertThat(group.totalQuantity()).isEqualByComparingTo("2.0000");
            assertThat(group.purchaseRequestCount()).isEqualTo(1);
            assertThat(group.itemCount()).isEqualTo(1);
            assertThat(group.lines()).singleElement().satisfies(line -> {
                assertThat(line.materialId()).isEqualTo(material.getId());
                assertThat(line.unit()).isEqualTo("kg");
                assertThat(line.normalizedUnitKey()).isEqualTo("KG");
            });
        });

        var all = queryService.preview(new AggregationPreviewFilter(
            RfqAggregationMode.MATERIAL, material.getId(), null, null, null, null, "PR-RFQ-GRP"), PageRequest.of(0, 10));
        assertThat(all.getContent()).singleElement().satisfies(group -> {
            assertThat(group.totalQuantity()).isEqualByComparingTo("5.0000");
            assertThat(group.estimatedAmount()).isEqualByComparingTo("80.0000");
            assertThat(group.purchaseRequestCount()).isEqualTo(2);
            assertThat(group.itemCount()).isEqualTo(2);
            assertThat(group.sources()).hasSize(2);
        });
    }

    @Test
    void previewExcludesServiceInactiveZeroDeletedAndAllocatedLines() {
        Material active = material("MAT-RFQ-ELIG-A-" + suffix(), "pcs");
        Material inactive = material("MAT-RFQ-ELIG-I-" + suffix(), "pcs");
        inactive.setStatus("INACTIVE");
        inactive = materialRepository.save(inactive);
        PurchaseRequest approved = purchaseRequest("PR-RFQ-ELIG-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequestItem eligible = item(approved, active, "pcs", 4, 5);
        PurchaseRequestItem allocated = item(approved, active, "pcs", 1, 5);
        item(approved, active, "pcs", 0, 5);
        item(approved, inactive, "pcs", 3, 5);
        PurchaseRequest servicePr = purchaseRequest("PR-RFQ-ELIG-SVC-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.SERVICE, "UZS");
        item(servicePr, active, "pcs", 9, 5);
        markAllocated(allocated);

        var page = queryService.preview(new AggregationPreviewFilter(
            RfqAggregationMode.MATERIAL, active.getId(), null, null, null, null, approved.getCode()), PageRequest.of(0, 10));

        assertThat(page.getContent()).singleElement().satisfies(group -> {
            assertThat(group.itemCount()).isEqualTo(1);
            assertThat(group.totalQuantity()).isEqualByComparingTo("4.0000");
            assertThat(group.sources()).singleElement()
                .extracting(source -> source.purchaseRequestItemId())
                .isEqualTo(eligible.getId());
        });
    }

    @Test
    void sourcesReturnAllocationSnapshotsAndLegacyUnavailableFlag() {
        Material material = material("MAT-RFQ-SRC-" + suffix(), "kg");
        PurchaseRequest pr = purchaseRequest("PR-RFQ-SRC-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequestItem prItem = item(pr, material, "kg", 7, 11);
        RFQ rfq = rfq("RFQ-SRC-" + suffix(), true, null);
        RFQItem rfqItem = rfqItem(rfq, material, "kg", 7);
        source(rfq, rfqItem, pr, prItem, RfqSourceAllocationStatus.ACTIVE.name());

        var response = queryService.sources(rfq.getId());

        assertThat(response.legacySourceUnavailable()).isFalse();
        assertThat(response.sourcePurchaseRequestCount()).isEqualTo(1);
        assertThat(response.sourceItemCount()).isEqualTo(1);
        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.rfqItemId()).isEqualTo(rfqItem.getId());
            assertThat(line.sources()).singleElement()
                .extracting(source -> source.purchaseRequestItemId())
                .isEqualTo(prItem.getId());
        });

        RFQ legacy = rfq("RFQ-LEGACY-" + suffix(), false, pr.getId());
        var legacyResponse = queryService.sources(legacy.getId());
        assertThat(legacyResponse.legacySourceUnavailable()).isTrue();
        assertThat(legacyResponse.lines()).isEmpty();
    }

    @Test
    void campaignRfqAllocationAggregatesSelectedApprovedPrLinesIntoExistingRfq() {
        Material material = material("MAT-RFQ-CAMPAIGN-AGG-" + suffix(), "kg");
        PurchaseRequest first = purchaseRequest("PR-RFQ-CAMPAIGN-AGG-A-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequest second = purchaseRequest("PR-RFQ-CAMPAIGN-AGG-B-" + suffix(), PurchaseRequestStatus.APPROVED.name(), RequestType.GOODS, "UZS");
        PurchaseRequestItem firstItem = item(first, material, "kg", 2, 10);
        PurchaseRequestItem secondItem = item(second, material, "kg", 3, 20);
        RFQ rfq = rfq("RFQ-CAMPAIGN-AGG-" + suffix(), false, first.getId());
        String groupKey = "MATERIAL|" + CompanyContext.DEFAULT_COMPANY_ID + "|UZS|" + material.getId() + "|TMB|KG";

        var response = aggregationService.allocateToCampaignRfq(
            rfq,
            RfqAggregationMode.MATERIAL,
            groupKey,
            java.util.List.of(firstItem.getId(), secondItem.getId()));

        RFQ savedRfq = rfqRepository.findByIdAndIsDeletedFalse(rfq.getId()).orElseThrow();
        assertThat(savedRfq.isAggregatedTender()).isTrue();
        assertThat(savedRfq.getAggregationMode()).isEqualTo(RfqAggregationMode.MATERIAL);
        assertThat(savedRfq.getPurchaseRequestId()).isNull();
        assertThat(savedRfq.getSourcePurchaseRequestCount()).isEqualTo(2);
        assertThat(savedRfq.getSourceItemCount()).isEqualTo(2);
        assertThat(response.rfqItemCount()).isEqualTo(1);
        assertThat(response.sourcePurchaseRequestCount()).isEqualTo(2);
        assertThat(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse("default", rfq.getId()))
            .singleElement()
            .satisfies(line -> {
                assertThat(line.getMaterialId()).isEqualTo(material.getId());
                assertThat(line.getQuantity()).isEqualByComparingTo("5.0000");
            });
        assertThat(sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfq.getId()))
            .hasSize(2)
            .extracting(RFQPurchaseRequestSource::getPurchaseRequestItemId)
            .containsExactlyInAnyOrder(firstItem.getId(), secondItem.getId());
    }

    private Material material(String code, String uomCode) {
        UnitOfMeasure uom = new UnitOfMeasure();
        uom.setCode(uomCode);
        uom.setName(code + "-uom");
        uom.setStatus("ACTIVE");
        uom = unitOfMeasureRepository.save(uom);
        MaterialGroup group = new MaterialGroup();
        group.setCode(code + "-grp");
        group.setName(code + "-grp");
        group.setStatus("ACTIVE");
        group = materialGroupRepository.save(group);
        Material material = new Material();
        material.setCode(code);
        material.setName(code);
        material.setStatus("ACTIVE");
        material.setUnitOfMeasureId(uom.getId());
        material.setMaterialGroupId(group.getId());
        return materialRepository.save(material);
    }

    private PurchaseRequest purchaseRequest(String code, String status, RequestType requestType, String currency) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setCode(code);
        pr.setName(code);
        pr.setStatus(status);
        pr.setRequestType(requestType);
        pr.setCurrencyCode(currency);
        pr.setRequiredDate(LocalDate.now().plusDays(5));
        pr.setTotalAmount(BigDecimal.ZERO);
        return purchaseRequestRepository.save(pr);
    }

    private PurchaseRequestItem item(PurchaseRequest pr, Material material, String unit, int quantity, int price) {
        PurchaseRequestItem item = new PurchaseRequestItem();
        item.setCode("PRI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("OPEN");
        item.setPurchaseRequestId(pr.getId());
        item.setMaterialId(material.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit(unit);
        item.setQuantity(BigDecimal.valueOf(quantity));
        item.setEstimatedPrice(BigDecimal.valueOf(price));
        return purchaseRequestItemRepository.save(item);
    }

    private RFQ rfq(String code, boolean aggregated, UUID purchaseRequestId) {
        RFQ rfq = new RFQ();
        rfq.setCode(code);
        rfq.setName(code);
        rfq.setStatus("ACTIVE");
        rfq.setAggregatedTender(aggregated);
        rfq.setPurchaseRequestId(purchaseRequestId);
        rfq.setCurrencyCode("UZS");
        return rfqRepository.save(rfq);
    }

    private RFQItem rfqItem(RFQ rfq, Material material, String unit, int quantity) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + suffix());
        item.setName(item.getCode());
        item.setStatus("ACTIVE");
        item.setRfqId(rfq.getId());
        item.setMaterialId(material.getId());
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setUnit(unit);
        item.setQuantity(BigDecimal.valueOf(quantity));
        return rfqItemRepository.save(item);
    }

    private void markAllocated(PurchaseRequestItem item) {
        Material material = materialRepository.findById(item.getMaterialId()).orElseThrow();
        PurchaseRequest pr = purchaseRequestRepository.findById(item.getPurchaseRequestId()).orElseThrow();
        RFQ rfq = rfq("RFQ-ALLOC-" + suffix(), true, pr.getId());
        RFQItem rfqItem = rfqItem(rfq, material, item.getUnit(), item.getQuantity().intValue());
        source(rfq, rfqItem, pr, item, RfqSourceAllocationStatus.ACTIVE.name());
    }

    private RFQPurchaseRequestSource source(RFQ rfq, RFQItem rfqItem, PurchaseRequest pr, PurchaseRequestItem prItem, String status) {
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("SRC-" + suffix());
        source.setName(source.getCode());
        source.setStatus(status);
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(pr.getId());
        source.setPurchaseRequestItemId(prItem.getId());
        source.setAllocatedQuantity(prItem.getQuantity());
        source.setSourceRequiredDate(pr.getRequiredDate());
        source.setSourceDepartmentId(pr.getDepartmentId());
        source.setSourceBudgetItemId(prItem.getBudgetItemId());
        source.setSourceCurrencyCode(pr.getCurrencyCode());
        source.setSourceUnit(prItem.getUnit());
        source.setAllocatedAt(LocalDateTime.now());
        return sourceRepository.save(source);
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
