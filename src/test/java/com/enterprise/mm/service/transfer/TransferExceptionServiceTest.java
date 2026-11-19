package com.enterprise.mm.service.transfer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransferExceptionServiceTest {
    @Test void destinationOperatorCannotConfirmSourceReturnAndFinanceCannotReviewWarehouseDisposition() {
        assertThat(TransferExceptionService.caseActions("RETURN_IN_TRANSIT", "RETURN_TO_SOURCE", "DAMAGED", false, true, false, false)).doesNotContain("CONFIRM_RETURN");
        assertThat(TransferExceptionService.caseActions("RETURN_IN_TRANSIT", "RETURN_TO_SOURCE", "DAMAGED", false, true, false, true)).contains("CONFIRM_RETURN");
        assertThat(TransferExceptionService.caseActions("APPROVED", "CONFIRM_LOSS", "MISSING", false, false, true, false)).containsExactly("POST");
        assertThat(TransferExceptionService.caseActions("REVIEWED", "CONFIRM_LOSS", "MISSING", true, true, false, true)).contains("SUBMIT", "REVIEW").doesNotContain("POST");
    }

    @Test void warehouseReviewAloneCannotPostLoss() {
        assertThat(new TransferExceptionService.DecisionState(4, 4, true, false).canPostLoss()).isFalse();
    }
    @Test void staleApprovalCannotAuthorizeAChangedCase() {
        assertThat(new TransferExceptionService.DecisionState(5, 4, true, true).canPostLoss()).isFalse();
        assertThat(new TransferExceptionService.DecisionState(5, 5, true, true).canPostLoss()).isTrue();
        assertThat(new TransferExceptionService.DecisionState(5, 5, false, true).canPostLoss()).isFalse();
    }
}
