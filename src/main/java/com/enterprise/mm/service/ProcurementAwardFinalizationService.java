package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderSourceAllocation;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.enums.ProcurementProcedureStatus;
import com.enterprise.mm.repository.PurchaseOrderSourceAllocationRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.service.ProcurementAwardFinalizationOrchestrator.AwardFinalizationCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcurementAwardFinalizationService {
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseOrderSourceAllocationRepository allocationRepository;
    private final RFQRepository rfqRepository;
    private final ProcurementAwardFinalizationOrchestrator awardFinalizationOrchestrator;

    public void recordLineage(UUID rfqItemId, PurchaseOrderItem purchaseOrderItem) {
        if (rfqItemId == null || purchaseOrderItem == null || purchaseOrderItem.getId() == null) {
            return;
        }
        List<RFQPurchaseRequestSource> sources = sourceRepository
            .findByTenantIdAndCompanyIdAndRfqItemIdAndStatusAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfqItemId,
                "ACTIVE"
            );
        for (RFQPurchaseRequestSource source : sources) {
            PurchaseOrderSourceAllocation allocation = new PurchaseOrderSourceAllocation();
            allocation.setCode("POSA-" + shortId());
            allocation.setName("PO source allocation for " + purchaseOrderItem.getName());
            allocation.setStatus("ACTIVE");
            allocation.setPurchaseOrderItemId(purchaseOrderItem.getId());
            allocation.setPurchaseRequestItemId(source.getPurchaseRequestItemId());
            allocation.setRfqPurchaseRequestSourceId(source.getId());
            allocation.setOrderedQuantity(amount(source.getAllocatedQuantity()));
            allocationRepository.save(allocation);
        }
    }

    public void completeCompetitiveAward(
        RFQ rfq,
        SupplierQuotation quotation,
        PurchaseOrder purchaseOrder
    ) {
        rfq.setStatus("AWARDED");
        rfqRepository.save(rfq);
        if (rfq.getProcurementProcedureId() == null) {
            return;
        }
        awardFinalizationOrchestrator.finalizeAward(new AwardFinalizationCommand(
            rfq.getProcurementProcedureId(),
            ProcurementAwardSourceType.RFQ_QUOTATION,
            quotation.getId(),
            quotation.getSupplierId(),
            purchaseOrder.getId(),
            null,
            quotation.getTotalAmount(),
            quotation.getCurrencyCode(),
            quotation.getCode(),
            ProcurementProcedureStatus.COMPLETED,
            null,
            null
        ));
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
