package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.AvailableActions;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandAction;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandBlocker;
import org.springframework.stereotype.Component;

import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ProcurementNextActionResolver {

    public AvailableActions resolveActions(CaseFacts facts, List<CommandBlocker> blockers) {
        if (facts != null && "CANCELLED".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return new AvailableActions(null, List.of());
        }
        if (blockers != null && !blockers.isEmpty()) {
            CommandBlocker blocker = blockers.get(0);
            return new AvailableActions(action(blocker.resolutionActionKey(), "NAVIGATE"), List.of());
        }
        // Approval must win over branch defaults so the UI never offers START_SOURCING while PR is pending.
        if (facts != null && (facts.approvalPending()
            || "APPROVAL_PENDING".equalsIgnoreCase(facts.purchaseRequestStatus()))) {
            return new AvailableActions(action("REVIEW_APPROVAL", "NAVIGATE"), List.of());
        }
        if (facts != null && facts.branchStages() != null && !facts.branchStages().isEmpty()) {
            ProcurementCommandStage stage = facts.branchStages().stream()
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(ProcurementCommandStage.SOURCING_SETUP);
            return new AvailableActions(switch (stage) {
                case PR_PREPARATION -> action("COMPLETE_PURCHASE_REQUEST", "NAVIGATE");
                case PR_APPROVAL -> action("REVIEW_APPROVAL", "NAVIGATE");
                case SOURCING_SETUP -> action("START_SOURCING", "NAVIGATE");
                case RFQ_IN_PROGRESS -> action("MONITOR_RFQ", "NAVIGATE");
                case QUOTATION_EVALUATION -> action("EVALUATE_QUOTATIONS", "NAVIGATE");
                case AWARD_DECISION -> action("COMPLETE_AWARD", "NAVIGATE");
                case PO_PREPARATION -> action("COMPLETE_PURCHASE_ORDER", "NAVIGATE");
                case PO_APPROVAL -> action("REVIEW_PO_APPROVAL", "NAVIGATE");
                case PO_READY_TO_DISPATCH -> action("DISPATCH_PURCHASE_ORDER", "COMMAND");
                case PO_DISPATCHED -> action("TRACK_DELIVERY", "NAVIGATE");
                case CANCELLED -> null;
                case BLOCKED -> action("RESOLVE_BLOCKER", "NAVIGATE");
            }, List.of());
        }
        if (facts != null && facts.poReady()) {
            return new AvailableActions(action("DISPATCH_PURCHASE_ORDER", "COMMAND"), List.of());
        }
        if (facts != null && facts.poDraft()) {
            return new AvailableActions(action("COMPLETE_PURCHASE_ORDER", "NAVIGATE"), List.of());
        }
        if (facts != null && facts.quotationReady()) {
            return new AvailableActions(action("EVALUATE_QUOTATIONS", "NAVIGATE"), List.of());
        }
        if (facts != null && "DRAFT".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return new AvailableActions(action("COMPLETE_PURCHASE_REQUEST", "NAVIGATE"), List.of());
        }
        return new AvailableActions(action("START_SOURCING", "NAVIGATE"), List.of());
    }

    private CommandAction action(String key, String invocationKind) {
        return new CommandAction(key, true, null, Map.of(), false, invocationKind, null, null);
    }
}
