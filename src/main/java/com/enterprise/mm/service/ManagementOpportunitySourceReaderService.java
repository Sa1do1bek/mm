package com.enterprise.mm.service;

import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.NonMovingStockCaseLine;
import com.enterprise.mm.entity.NonMovingStockDecision;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.NonMovingStockCaseLineRepository;
import com.enterprise.mm.repository.NonMovingStockDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagementOpportunitySourceReaderService {
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialRepository materialRepository;
    private final NonMovingStockCaseLineRepository nonMovingStockCaseLineRepository;
    private final NonMovingStockDecisionRepository nonMovingStockDecisionRepository;
    private final DeliveryScheduleRepository deliveryScheduleRepository;



    public List<PurchaseOrderItem> receiptCandidates(String tenantId, UUID companyId, LocalDate asOfDate) {
        return purchaseOrderItemRepository.findManagementOpportunityReceiptCandidates(tenantId, companyId, asOfDate);
    }

    public List<NonMovingStockCaseLine> carryingCostCandidates(String tenantId, UUID companyId) {
        return nonMovingStockCaseLineRepository.findManagementOpportunityCarryingCostCandidates(tenantId, companyId);
    }

    public List<NonMovingStockDecision> recoveryGapDecisions(String tenantId, UUID companyId) {
        return nonMovingStockDecisionRepository.findManagementOpportunityRecoveryGapCandidates(tenantId, companyId);
    }

    public List<NonMovingStockCaseLine> recoveryGapLines(String tenantId, UUID caseId) {
        return nonMovingStockCaseLineRepository.findByTenantIdAndCaseIdAndIsDeletedFalseOrderByCreatedAtAsc(tenantId, caseId);
    }

    public List<DeliverySchedule> delayCandidates(String tenantId, UUID companyId, LocalDate asOfDate) {
        return deliveryScheduleRepository.findManagementOpportunityDelayCandidates(tenantId, companyId, asOfDate);
    }

    public Optional<Material> material(String tenantId, UUID materialId) {
        if (materialId == null) {
            return Optional.empty();
        }
        return materialRepository.findByTenantIdAndIdAndIsDeletedFalse(tenantId, materialId);
    }
}
