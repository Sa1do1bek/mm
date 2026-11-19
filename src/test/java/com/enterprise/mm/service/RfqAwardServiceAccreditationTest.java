package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ContractSpecificationRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RfqAwardServiceAccreditationTest {
    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private SupplierQuotationItemRepository quotationItemRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private RFQPurchaseRequestSourceRepository sourceRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private ContractSpecificationRepository contractSpecificationRepository;
    @Mock private QuotationSelectionValidationService selectionValidationService;
    @Mock private RfqAggregationUnitResolver unitResolver;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @Mock private MaterialRepository materialRepository;
    @Mock private SupplierAccreditationService supplierAccreditationService;
    @InjectMocks private RfqAwardService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void mixedAwardRequiresMaterialServiceAndWorksAccreditationBeforeMutation() {
        UUID quotationId = UUID.randomUUID();
        UUID rfqId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID materialGroupId = UUID.randomUUID();

        SupplierQuotation quotation = quotation(quotationId, rfqId, supplierId);
        RFQ rfq = rfq(rfqId);
        Supplier supplier = supplier(supplierId);
        RFQItem materialLine = rfqItem(rfqId, ProcurementLineType.MATERIAL, materialId, "EA");
        RFQItem serviceLine = rfqItem(rfqId, ProcurementLineType.SERVICE, null, "HOUR");
        RFQItem workLine = rfqItem(rfqId, ProcurementLineType.WORK, null, "JOB");
        List<RFQItem> rfqItems = List.of(materialLine, serviceLine, workLine);
        List<SupplierQuotationItem> quoteItems = List.of(
            quoteItem(quotationId, materialLine, ProcurementLineType.MATERIAL, materialId),
            quoteItem(quotationId, serviceLine, ProcurementLineType.SERVICE, null),
            quoteItem(quotationId, workLine, ProcurementLineType.WORK, null));
        Material material = new Material();
        material.setId(materialId);
        material.setTenantId("default");
        material.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        material.setStatus("ACTIVE");
        material.setMaterialGroupId(materialGroupId);

        when(quotationRepository.findByTenantIdAndIdAndIsDeletedFalse("default", quotationId))
            .thenReturn(Optional.of(quotation));
        when(rfqRepository.findByIdForUpdate(rfqId)).thenReturn(Optional.of(rfq));
        when(contractRepository.findByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId)).thenReturn(Optional.empty());
        when(supplierRepository.findByIdAndIsDeletedFalse(supplierId)).thenReturn(Optional.of(supplier));
        when(sourceRepository.findByTenantIdAndCompanyIdAndRfqIdAndStatusAndIsDeletedFalse(
            "default", CompanyContext.DEFAULT_COMPANY_ID, rfqId, "ACTIVE"))
            .thenReturn(List.of(new RFQPurchaseRequestSource()));
        when(quotationRepository.findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc("default", rfqId))
            .thenReturn(List.of(quotation));
        when(rfqItemRepository.findByTenantIdAndRfqIdOrderById("default", rfqId)).thenReturn(rfqItems);
        when(quotationItemRepository.findByTenantIdAndQuotation_IdAndIsDeletedFalse("default", quotationId))
            .thenReturn(quoteItems);
        when(unitResolver.requiredKey(anyString(), any(UUID.class)))
            .thenAnswer(invocation -> invocation.<String>getArgument(0).toUpperCase());
        when(unitResolver.normalizeOptional(anyString()))
            .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(materialRepository.findByTenantIdAndIdAndIsDeletedFalse("default", materialId))
            .thenReturn(Optional.of(material));
        AccreditationDemand demand = new AccreditationDemand(
            Set.of(materialGroupId), true, true, true, true);
        doThrow(new BusinessException("Supplier works accreditation is required for supplier quotation award"))
            .when(supplierAccreditationService)
            .assertSupplierEligibleForDemand(supplierId, demand, "supplier quotation award");
        assertThatThrownBy(() -> service.award(quotationId, "best offer"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier works accreditation is required for supplier quotation award");

        verify(quotationRepository, never()).save(any());
        verify(contractRepository, never()).save(any());
        verify(contractSpecificationRepository, never()).save(any());
        verify(rfqRepository, never()).save(any());
    }

    private SupplierQuotation quotation(UUID id, UUID rfqId, UUID supplierId) {
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setId(id);
        quotation.setTenantId("default");
        quotation.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        quotation.setCode("Q-AWARD");
        quotation.setStatus("SUBMITTED");
        quotation.setRfqId(rfqId);
        quotation.setSupplierId(supplierId);
        quotation.setTotalAmount(BigDecimal.valueOf(100));
        quotation.setCurrencyCode("UZS");
        return quotation;
    }

    private RFQ rfq(UUID id) {
        RFQ rfq = new RFQ();
        rfq.setId(id);
        rfq.setTenantId("default");
        rfq.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        rfq.setCode("RFQ-AWARD");
        rfq.setStatus("OPEN");
        rfq.setAggregatedTender(true);
        return rfq;
    }

    private Supplier supplier(UUID id) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId("default");
        supplier.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private RFQItem rfqItem(UUID rfqId, ProcurementLineType lineType, UUID materialId, String unit) {
        RFQItem item = new RFQItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setRfqId(rfqId);
        item.setLineType(lineType);
        item.setMaterialId(materialId);
        item.setUnit(unit);
        item.setQuantity(BigDecimal.ONE);
        return item;
    }

    private SupplierQuotationItem quoteItem(
        UUID quotationId,
        RFQItem rfqItem,
        ProcurementLineType lineType,
        UUID materialId
    ) {
        SupplierQuotationItem item = new SupplierQuotationItem();
        item.setId(UUID.randomUUID());
        item.setTenantId("default");
        item.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        item.setQuotationId(quotationId);
        item.setRfqItemId(rfqItem.getId());
        item.setLineType(lineType);
        item.setMaterialId(materialId);
        item.setUnit(rfqItem.getUnit());
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.TEN);
        return item;
    }
}
