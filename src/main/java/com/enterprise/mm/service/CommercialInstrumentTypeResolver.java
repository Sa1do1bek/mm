package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementAwardOutcome;
import com.enterprise.mm.enums.CommercialInstrumentType;
import com.enterprise.mm.enums.ProcurementAwardSourceType;
import com.enterprise.mm.repository.ProcurementAwardOutcomeRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves invoice/payment commercial instrument type for payables lineage.
 * Framework call-off POs are identified via {@link ProcurementAwardOutcome}
 * with {@link ProcurementAwardSourceType#FRAMEWORK_CALLOFF}.
 */
@Service
@RequiredArgsConstructor
public class CommercialInstrumentTypeResolver {
    private final ProcurementAwardOutcomeRepository awardOutcomeRepository;

    public CommercialInstrumentType resolve(
        CommercialInstrumentType explicit,
        UUID contractId,
        UUID purchaseOrderId
    ) {
        if (explicit != null) {
            return explicit;
        }
        if (contractId == null && purchaseOrderId != null) {
            return CommercialInstrumentType.STANDALONE_PO;
        }
        if (contractId != null && purchaseOrderId != null && isFrameworkCallOff(purchaseOrderId)) {
            return CommercialInstrumentType.FRAMEWORK_CALLOFF;
        }
        return CommercialInstrumentType.CONTRACT_CALLOFF;
    }

    private boolean isFrameworkCallOff(UUID purchaseOrderId) {
        return awardOutcomeRepository
            .findFirstByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByFinalizedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                purchaseOrderId
            )
            .map(ProcurementAwardOutcome::getSourceType)
            .filter(ProcurementAwardSourceType.FRAMEWORK_CALLOFF::equals)
            .isPresent();
    }
}
