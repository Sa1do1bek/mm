package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandBlocker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ProcurementBlockerResolver {

    public List<CommandBlocker> resolveBlockers(CaseFacts facts) {
        if (facts == null) {
            return List.of(blocker("PROCUREMENT_CASE_UNAVAILABLE", "OPEN_PURCHASE_REQUEST"));
        }
        if ("CANCELLED".equalsIgnoreCase(facts.purchaseRequestStatus())) {
            return List.of();
        }
        List<CommandBlocker> blockers = new ArrayList<>();
        // Deliberate policy order: supplier, accreditation, approval, tender,
        // contract, then integration. UI order must never determine precedence.
        if (facts.supplierBlocked()) {
            blockers.add(blocker("PROCUREMENT_SUPPLIER_BLOCKED", "RESOLVE_SUPPLIER_BLOCK"));
        }
        if (facts.accreditationFailed()) {
            blockers.add(blocker("PROCUREMENT_ACCREDITATION_FAILED", "RESOLVE_ACCREDITATION"));
        }
        if (facts.approvalRejected()) {
            blockers.add(blocker("PROCUREMENT_APPROVAL_REJECTED", "RESUBMIT_FOR_APPROVAL"));
        }
        if (facts.tenderBlocked()) {
            blockers.add(blocker("PROCUREMENT_TENDER_BLOCKED", "RESOLVE_TENDER_BLOCK"));
        }
        if (facts.poBlocked()) {
            blockers.add(blocker("PROCUREMENT_PO_BLOCKED", "OPEN_PURCHASE_ORDER"));
        }
        if (facts.contractInvalid()) {
            blockers.add(blocker("PROCUREMENT_CONTRACT_INVALID", "OPEN_CONTRACT"));
        }
        if (facts.integrationFailed()) {
            blockers.add(blocker("PROCUREMENT_INTEGRATION_FAILED", "RETRY_INTEGRATION"));
        }
        if (facts.overdue()) {
            blockers.add(new CommandBlocker(
                "PROCUREMENT_WORK_OVERDUE",
                ProcurementBlockerSeverity.WARNING,
                "procurement.command.blocker.PROCUREMENT_WORK_OVERDUE",
                Map.of(), null, null, null, "PROCUREMENT_MANAGER", null, "ESCALATE_CASE"));
        }
        return List.copyOf(blockers);
    }

    private CommandBlocker blocker(String code, String actionKey) {
        return new CommandBlocker(
            code,
            ProcurementBlockerSeverity.BLOCKING,
            "procurement.command.blocker." + code,
            Map.of(), null, null, null, "PROCUREMENT_SPECIALIST", null, actionKey);
    }
}
