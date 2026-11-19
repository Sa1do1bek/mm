package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.entity.PurchaseRequestItem;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.RFQPurchaseRequestSource;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RfqSourceAllocationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.PurchaseRequestItemRepository;
import com.enterprise.mm.repository.PurchaseRequestRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQPurchaseRequestSourceRepository;
import com.enterprise.mm.repository.RFQRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcurementCampaignRfqSourceService {
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final PurchaseRequestItemRepository purchaseRequestItemRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RFQPurchaseRequestSourceRepository sourceRepository;
    private final PurchaseRequestTenderStatusService tenderStatusService;
    private final ProcurementMaterialTypeSnapshotService materialTypeSnapshotService;

    public void copyPurchaseRequestLines(RFQ rfq) {
        if (rfq.getPurchaseRequestId() == null) {
            return;
        }
        PurchaseRequest purchaseRequest = purchaseRequestRepository
            .findByIdAndIsDeletedFalse(rfq.getPurchaseRequestId())
            .filter(this::scoped)
            .orElseThrow(() -> new BusinessException(
                "Purchase request not found: " + rfq.getPurchaseRequestId()));
        List<PurchaseRequestItem> requestItems = purchaseRequestItemRepository
            .findByTenantIdAndPurchaseRequest_IdAndIsDeletedFalse(
                TenantContext.currentTenant(), purchaseRequest.getId())
            .stream()
            .filter(this::scoped)
            .toList();
        if (requestItems.isEmpty()) {
            throw new BusinessException("Purchase request has no lines");
        }
        for (PurchaseRequestItem requestItem : requestItems) {
            if (sourceRepository.existsByTenantIdAndCompanyIdAndPurchaseRequestItemIdAndStatusAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                requestItem.getId(),
                RfqSourceAllocationStatus.ACTIVE.name()
            )) {
                throw new BusinessException(
                    "PR item is already allocated to an RFQ",
                    "rfq.aggregation.already_allocated",
                    requestItem.getId()
                );
            }
            if (requestItem.getMaterialTypeSnapshot() == null) {
                materialTypeSnapshotService.applySnapshot(requestItem, purchaseRequest.getRequestType());
            }
            RFQItem rfqItem = copyLine(rfq, requestItem);
            copySource(rfq, rfqItem, purchaseRequest, requestItem);
        }
        rfq.setSourcePurchaseRequestCount(1);
        rfq.setSourceItemCount(requestItems.size());
        rfqRepository.save(rfq);
        tenderStatusService.recalculateLocked(List.of(purchaseRequest.getId()));
    }

    private RFQItem copyLine(RFQ rfq, PurchaseRequestItem source) {
        RFQItem item = new RFQItem();
        item.setCode("RFQI-" + shortId());
        item.setName(source.getName());
        item.setStatus("OPEN");
        item.setCompanyId(CompanyContext.currentCompany());
        item.setRfqId(rfq.getId());
        item.setLineType(source.getLineType() == null ? ProcurementLineType.MATERIAL : source.getLineType());
        item.setMaterialId(source.getMaterialId());
        item.setMaterialTypeSnapshot(source.getMaterialTypeSnapshot());
        item.setServiceRequestId(source.getServiceRequestId());
        item.setServiceDescription(source.getServiceDescription());
        item.setServiceCategory(source.getServiceCategory());
        item.setUnit(unitFromSource(source));
        item.setPeriod(source.getPeriod());
        item.setStage(source.getStage());
        item.setBudgetItemId(source.getBudgetItemId());
        item.setQuantity(source.getQuantity());
        return rfqItemRepository.save(item);
    }

    private void copySource(
        RFQ rfq,
        RFQItem rfqItem,
        PurchaseRequest purchaseRequest,
        PurchaseRequestItem item
    ) {
        RFQPurchaseRequestSource source = new RFQPurchaseRequestSource();
        source.setCode("RFQSRC-" + shortId());
        source.setName(purchaseRequest.getName() + " allocation");
        source.setStatus(RfqSourceAllocationStatus.ACTIVE.name());
        source.setCompanyId(CompanyContext.currentCompany());
        source.setRfqId(rfq.getId());
        source.setRfqItemId(rfqItem.getId());
        source.setPurchaseRequestId(purchaseRequest.getId());
        source.setPurchaseRequestItemId(item.getId());
        source.setAllocatedQuantity(item.getQuantity());
        source.setSourceRequiredDate(item.fulfillmentDate(purchaseRequest));
        source.setSourceDepartmentId(purchaseRequest.getDepartmentId());
        source.setSourceBudgetItemId(item.getBudgetItemId());
        source.setSourceCurrencyCode(purchaseRequest.getCurrencyCode());
        source.setSourceUnit(unitFromSource(item));
        source.setAllocatedAt(LocalDateTime.now());
        sourceRepository.save(source);
    }

    private String unitFromSource(PurchaseRequestItem item) {
        if (item == null) {
            return null;
        }
        String unit = normalize(item.getUnit());
        if (unit != null) {
            return unit;
        }
        var material = item.getMaterial();
        if (material == null || material.getUnitOfMeasure() == null) {
            return null;
        }
        String unitFromMaterial = normalize(material.getUnitOfMeasure().getMeasurementNameShort());
        if (unitFromMaterial != null) {
            return unitFromMaterial;
        }
        return normalize(material.getUnitOfMeasure().getMeasurementNameLong());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private boolean scoped(com.enterprise.mm.common.BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && CompanyContext.currentCompany().equals(entity.getCompanyId());
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
