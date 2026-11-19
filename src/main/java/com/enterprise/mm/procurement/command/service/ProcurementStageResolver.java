package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class ProcurementStageResolver {

    public ProcurementCommandStage resolveStage(CaseFacts facts) {
        if (facts == null) {
            return ProcurementCommandStage.BLOCKED;
        }
        if ("CANCELLED".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return ProcurementCommandStage.CANCELLED;
        }
        // Pending PR approval must not be masked by downstream RFQ/PO branch stages.
        if (facts.approvalPending() || "APPROVAL_PENDING".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return ProcurementCommandStage.PR_APPROVAL;
        }
        if (facts.approvalRejected()) {
            return ProcurementCommandStage.BLOCKED;
        }
        if (facts.branchStages() != null && !facts.branchStages().isEmpty()) {
            return facts.branchStages().stream()
                .filter(stage -> stage != null)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(ProcurementCommandStage.SOURCING_SETUP);
        }
        if (facts.poReady()) {
            return ProcurementCommandStage.PO_READY_TO_DISPATCH;
        }
        if (facts.poDraft()) {
            return ProcurementCommandStage.PO_PREPARATION;
        }
        if (facts.quotationReady()) {
            return ProcurementCommandStage.QUOTATION_EVALUATION;
        }
        if (facts.rfqStatuses() != null && !facts.rfqStatuses().isEmpty()) {
            return ProcurementCommandStage.RFQ_IN_PROGRESS;
        }
        if ("RFQ_CREATED".equalsIgnoreCase(facts.purchaseRequestStatus())
            || "PARTIALLY_SENT_TO_TENDER".equalsIgnoreCase(facts.purchaseRequestStatus())
            || "FULLY_SENT_TO_TENDER".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return ProcurementCommandStage.RFQ_IN_PROGRESS;
        }
        if ("DRAFT".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return ProcurementCommandStage.PR_PREPARATION;
        }
        return ProcurementCommandStage.SOURCING_SETUP;
    }
}
