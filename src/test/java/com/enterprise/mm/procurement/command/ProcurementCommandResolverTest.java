package com.enterprise.mm.procurement.command;

import com.enterprise.mm.procurement.command.enums.ProcurementBlockerSeverity;
import com.enterprise.mm.procurement.command.enums.ProcurementCommandStage;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CaseFacts;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandBlocker;
import com.enterprise.mm.procurement.command.service.ProcurementBlockerResolver;
import com.enterprise.mm.procurement.command.service.ProcurementNextActionResolver;
import com.enterprise.mm.procurement.command.service.ProcurementStageResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcurementCommandResolverTest {

    private final ProcurementStageResolver stageResolver = new ProcurementStageResolver();
    private final ProcurementBlockerResolver blockerResolver = new ProcurementBlockerResolver();
    private final ProcurementNextActionResolver nextActionResolver = new ProcurementNextActionResolver();

    @Test
    void blockingSupplierOverridesQuotationDecision() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("RFQ_CREATED")
            .rfqStatuses(List.of("EVALUATED"))
            .quotationReady(true)
            .supplierBlocked(true)
            .build();

        List<CommandBlocker> blockers = blockerResolver.resolveBlockers(facts);

        assertThat(blockers.getFirst().code()).isEqualTo("PROCUREMENT_SUPPLIER_BLOCKED");
        assertThat(blockers.getFirst().severity()).isEqualTo(ProcurementBlockerSeverity.BLOCKING);
        assertThat(nextActionResolver.resolveActions(facts, blockers).primary().key())
            .isEqualTo("RESOLVE_SUPPLIER_BLOCK");
    }

    @Test
    void earliestIncompleteBranchDeterminesCaseStage() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("RFQ_CREATED")
            .branchStages(List.of(
                ProcurementCommandStage.PO_READY_TO_DISPATCH,
                ProcurementCommandStage.RFQ_IN_PROGRESS))
            .build();

        assertThat(stageResolver.resolveStage(facts))
            .isEqualTo(ProcurementCommandStage.RFQ_IN_PROGRESS);
    }

    @Test
    void partialAndFullTenderStatusesResolveToRfqInProgress() {
        assertThat(stageResolver.resolveStage(CaseFacts.builder()
            .purchaseRequestStatus("PARTIALLY_SENT_TO_TENDER")
            .rfqStatuses(List.of("OPEN"))
            .build()))
            .isEqualTo(ProcurementCommandStage.RFQ_IN_PROGRESS);
        assertThat(stageResolver.resolveStage(CaseFacts.builder()
            .purchaseRequestStatus("FULLY_SENT_TO_TENDER")
            .rfqStatuses(List.of("OPEN"))
            .build()))
            .isEqualTo(ProcurementCommandStage.RFQ_IN_PROGRESS);
    }

    @Test
    void integrationFailureSortsAfterBusinessBlockers() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("APPROVED")
            .approvalRejected(true)
            .integrationFailed(true)
            .build();

        assertThat(blockerResolver.resolveBlockers(facts))
            .extracting(CommandBlocker::code)
            .containsExactly("PROCUREMENT_APPROVAL_REJECTED", "PROCUREMENT_INTEGRATION_FAILED");
    }

    @Test
    void poReadyCaseExposesDispatchAsPrimaryAction() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("APPROVED")
            .poReady(true)
            .build();

        assertThat(nextActionResolver.resolveActions(facts, List.of()).primary().key())
            .isEqualTo("DISPATCH_PURCHASE_ORDER");
    }

    @Test
    void cancelledCaseHasNoBlockersOrActionsEvenWhenOverdue() {
        CaseFacts facts = CaseFacts.builder()
            .purchaseRequestStatus("CANCELLED")
            .overdue(true)
            .tenderBlocked(true)
            .build();

        List<CommandBlocker> blockers = blockerResolver.resolveBlockers(facts);

        assertThat(blockers).isEmpty();
        assertThat(nextActionResolver.resolveActions(facts, blockers).primary()).isNull();
    }
}
