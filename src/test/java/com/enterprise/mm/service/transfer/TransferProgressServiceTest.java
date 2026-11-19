package com.enterprise.mm.service.transfer;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class TransferProgressServiceTest {
    private TransferQuantityState quantities(String planned,String dispatched,String received) {
        return new TransferQuantityState(new BigDecimal(planned),new BigDecimal(dispatched),BigDecimal.ZERO,new BigDecimal(received),BigDecimal.ZERO,BigDecimal.ZERO);
    }
    @Test void receiptOfOneShipmentDoesNotCompleteThePlan() {
        var result=TransferProgressService.evaluate(quantities("100","40","40"),0,0);
        assertThat(result.canDispatch()).isTrue();assertThat(result.canClose()).isFalse();assertThat(result.closeBlockers()).contains("UNDISPATCHED_REMAINDER");
    }
    @Test void dispatchAndReceiptCanBeAvailableTogether() {
        var result=TransferProgressService.evaluate(quantities("100","40","20"),0,0);
        assertThat(result.canDispatch()).isTrue();assertThat(result.canReceive()).isTrue();assertThat(result.canClose()).isFalse();
    }
    @Test void physicalCompletionDoesNotOverrideCasesOrUnpostedOperations() {
        var result=TransferProgressService.evaluate(quantities("100","100","100"),1,1);
        assertThat(result.closeBlockers()).containsExactly("OPEN_EXCEPTIONS","UNPOSTED_OPERATIONS");assertThat(result.canClose()).isFalse();
        assertThat(TransferProgressService.evaluate(quantities("100","100","100"),0,0).canClose()).isTrue();
    }
}
