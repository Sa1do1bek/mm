package com.enterprise.mm;

import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.COMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityDataCompleteness.DATA_INCOMPLETE;
import static com.enterprise.mm.enums.ManagementOpportunityType.PROCUREMENT_PRICE_VARIANCE;
import static com.enterprise.mm.enums.ManagementOpportunityType.SUPPLIER_DELAY_EXPOSURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.ManagementOpportunityPolicy;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MaterialMarketPrice;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.enums.ManagementOpportunityType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.ExchangeRateRepository;
import com.enterprise.mm.repository.MaterialMarketPriceRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.service.ManagementOpportunityCalculationService;
import com.enterprise.mm.service.ManagementOpportunityPolicyService;
import com.enterprise.mm.service.ManagementOpportunityScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagementOpportunityCalculationServiceTest {
    @Mock PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock MaterialMarketPriceRepository materialMarketPriceRepository;
    @Mock MaterialRepository materialRepository;
    @Mock NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository;
    @Mock NonMovingStockDecisionRepository nonMovingStockDecisionRepository;
    @Mock DeliveryScheduleRepository deliveryScheduleRepository;
    @Mock CurrencyRepository currencyRepository;
    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock ManagementOpportunityPolicyService policyService;

    private ManagementOpportunityCalculationService service;
    private final UUID companyId = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("tenant-mo-calc");
        CompanyContext.setCompany(companyId);
        service = new ManagementOpportunityCalculationService(
            purchaseOrderItemRepository,
            materialMarketPriceRepository,
            materialRepository,
            nonMovingStockCaseLineRepository,
            nonMovingStockDecisionRepository,
            deliveryScheduleRepository,
            currencyRepository,
            exchangeRateRepository,
            policyService,
            new ObjectMapper().findAndRegisterModules()
        );
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void procurementVarianceUsesApprovedEffectiveMarketPrice() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 12);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();

        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setCompanyId(companyId);
        order.setSupplierId(supplierId);
        order.setCurrencyCode("UZS");
        order.setOrderDate(asOfDate.minusDays(1));

        Material material = new Material();
        material.setId(materialId);
        material.setMaterialGroupId(materialGroupId);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setCompanyId(companyId);
        item.setPurchaseOrderId(order.getId());
        item.setPurchaseOrder(order);
        item.setLineType(ProcurementLineType.MATERIAL);
        item.setMaterialId(materialId);
        item.setMaterial(material);
        item.setReceiptWarehouseId(warehouseId);
        item.setUnitPrice(new BigDecimal("120.0000"));
        item.setReceivedQuantity(new BigDecimal("10.0000"));
        item.setRejectedQuantity(new BigDecimal("1.0000"));
        item.setDamagedQuantity(BigDecimal.ZERO);
        item.setQualityStatus("ACCEPTED");

        MaterialMarketPrice baseline = new MaterialMarketPrice();
        baseline.setId(UUID.randomUUID());
        baseline.setMaterialId(materialId);
        baseline.setWarehouseId(warehouseId);
        baseline.setMarketUnitPrice(new BigDecimal("100.0000"));
        baseline.setCurrencyCode("UZS");
        baseline.setEffectiveDate(asOfDate.minusDays(2));
        baseline.setStatus("APPROVED");

        ManagementOpportunityPolicy policy = policy(PROCUREMENT_PRICE_VARIANCE);

        when(purchaseOrderItemRepository.findManagementOpportunityReceiptCandidates("tenant-mo-calc", companyId, asOfDate))
            .thenReturn(List.of(item));
        when(policyService.resolveActivePolicy(eq(PROCUREMENT_PRICE_VARIANCE), any(ManagementOpportunityScope.class), eq(asOfDate)))
            .thenReturn(policy);
        when(materialMarketPriceRepository.findEffectiveApproved("tenant-mo-calc", companyId, materialId, warehouseId, asOfDate))
            .thenReturn(List.of(baseline));

        var candidates = service.calculate(asOfDate, List.of(PROCUREMENT_PRICE_VARIANCE), "UZS");

        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();
        assertThat(candidate.completeness()).isEqualTo(COMPLETE);
        assertThat(candidate.sourceAmount()).isEqualByComparingTo("180.0000");
        assertThat(candidate.normalizedAmount()).isEqualByComparingTo("180.0000");
        assertThat(candidate.quantity()).isEqualByComparingTo("9.0000");
        assertThat(candidate.formulaSnapshot()).contains("approvedMarketUnitPrice");
        assertThat(candidate.evidence()).hasSize(4);
        assertThat(candidate.evidence())
            .anySatisfy(evidence -> {
                assertThat(evidence.type()).isEqualTo("RECEIPT_ACCEPTANCE");
                assertThat(evidence.snapshotJson()).contains("acceptedQuantity");
            });
    }

    @Test
    void procurementVarianceSkipsWhenAcceptedQualityIsMissing() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 12);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setCompanyId(companyId);
        order.setCurrencyCode("UZS");

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(UUID.randomUUID());
        item.setCompanyId(companyId);
        item.setPurchaseOrderId(order.getId());
        item.setPurchaseOrder(order);
        item.setMaterialId(materialId);
        item.setReceiptWarehouseId(warehouseId);
        item.setUnitPrice(new BigDecimal("120.0000"));
        item.setReceivedQuantity(new BigDecimal("10.0000"));
        item.setQualityStatus(null);

        when(purchaseOrderItemRepository.findManagementOpportunityReceiptCandidates("tenant-mo-calc", companyId, asOfDate))
            .thenReturn(List.of(item));
        when(policyService.resolveActivePolicy(eq(PROCUREMENT_PRICE_VARIANCE), any(ManagementOpportunityScope.class), eq(asOfDate)))
            .thenReturn(policy(PROCUREMENT_PRICE_VARIANCE));

        var candidate = service.calculate(asOfDate, List.of(PROCUREMENT_PRICE_VARIANCE), "UZS").getFirst();

        assertThat(candidate.completeness()).isEqualTo(DATA_INCOMPLETE);
        assertThat(candidate.skipReason()).contains("Accepted receipt quantity");
    }

    @Test
    void supplierDelayExposureRequiresImpactReferenceAndShare() {
        LocalDate asOfDate = LocalDate.of(2026, 7, 12);
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder order = new PurchaseOrder();
        order.setId(UUID.randomUUID());
        order.setSupplierId(supplierId);

        DeliverySchedule schedule = new DeliverySchedule();
        schedule.setId(UUID.randomUUID());
        schedule.setCompanyId(companyId);
        schedule.setPurchaseOrderId(order.getId());
        schedule.setPurchaseOrder(order);
        schedule.setScheduleDate(asOfDate.minusDays(4));
        schedule.setPlannedQuantity(new BigDecimal("10.0000"));
        schedule.setReceivedQuantity(BigDecimal.ZERO);
        schedule.setOverdueFlag(true);

        ManagementOpportunityPolicy policy = policy(SUPPLIER_DELAY_EXPOSURE);
        policy.setDailyImpactRate(new BigDecimal("25.0000"));
        policy.setAttributableShare(new BigDecimal("0.500000"));
        policy.setCurrencyCode("UZS");

        when(deliveryScheduleRepository.findManagementOpportunityDelayCandidates("tenant-mo-calc", companyId, asOfDate))
            .thenReturn(List.of(schedule));
        when(policyService.resolveActivePolicy(eq(SUPPLIER_DELAY_EXPOSURE), any(ManagementOpportunityScope.class), eq(asOfDate)))
            .thenReturn(policy);

        var missingReference = service.calculate(asOfDate, List.of(SUPPLIER_DELAY_EXPOSURE), "UZS").getFirst();

        assertThat(missingReference.completeness()).isEqualTo(DATA_INCOMPLETE);
        assertThat(missingReference.skipReason()).contains("impact reference");

        schedule.setBusinessImpactReference("OPS-DELAY-123");

        var candidate = service.calculate(asOfDate, List.of(SUPPLIER_DELAY_EXPOSURE), "UZS").getFirst();

        assertThat(candidate.completeness()).isEqualTo(COMPLETE);
        assertThat(candidate.sourceAmount()).isEqualByComparingTo("50.0000");
        assertThat(candidate.formulaSnapshot()).contains("lateDays");
    }

    private ManagementOpportunityPolicy policy(ManagementOpportunityType type) {
        ManagementOpportunityPolicy policy = new ManagementOpportunityPolicy();
        policy.setId(UUID.randomUUID());
        policy.setCompanyId(companyId);
        policy.setOpportunityType(type);
        policy.setCode("MO-POLICY");
        policy.setName("MO policy");
        policy.setActive(true);
        policy.setEffectiveFrom(LocalDate.now().minusDays(1));
        return policy;
    }
}
