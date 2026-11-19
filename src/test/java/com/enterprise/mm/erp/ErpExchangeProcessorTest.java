package com.enterprise.mm.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErpExchangeProcessorTest {
    private final ErpExchangeProcessor processor = new ErpExchangeProcessor();

    @Test
    void disabledExchangeDoesNotCreateAnEnvelope() {
        var decision = processor.accept(false, ErpDocumentKinds.POST_WHEN_CONFIRMED, false, 0);

        assertThat(decision.skipped()).isTrue();
        assertThat(decision.created()).isFalse();
        assertThat(decision.addPosting()).isFalse();
    }

    @Test
    void sameVersionDoesNotCreateASecondEnvelopeOrPosting() {
        var again = processor.accept(true, ErpDocumentKinds.POST_WHEN_CONFIRMED, true, 1);

        assertThat(again.created()).isFalse();
        assertThat(again.addPosting()).isFalse();
        assertThat(again.posted()).isTrue();
    }

    @Test
    void confirmedDocumentPostsOnArrivalAndALaterConfirmDoesNotPostAgain() {
        MemoryExchange memory = new MemoryExchange();
        memory.enabled = true;
        memory.mode = ErpDocumentKinds.POST_WHEN_CONFIRMED;

        apply(memory, processor.accept(memory.enabled, memory.mode, memory.envelope, memory.postings));
        assertThat(memory.envelope).isTrue();
        assertThat(memory.postings).isEqualTo(1);
        assertThat(memory.state).isEqualTo("POSTED");

        apply(memory, processor.accept(memory.enabled, memory.mode, memory.envelope, memory.postings));
        apply(memory, processor.confirm(memory.postings));

        assertThat(memory.postings).isEqualTo(1);
    }

    @Test
    void heldDocumentPostsOnceWhenConfirmed() {
        MemoryExchange memory = new MemoryExchange();
        memory.enabled = true;
        memory.mode = ErpDocumentKinds.HOLD_UNTIL_CONFIRMED;

        apply(memory, processor.accept(memory.enabled, memory.mode, memory.envelope, memory.postings));
        assertThat(memory.envelope).isTrue();
        assertThat(memory.postings).isZero();
        assertThat(memory.state).isEqualTo("AWAITING_CONFIRMATION");

        apply(memory, processor.confirm(memory.postings));
        assertThat(memory.postings).isEqualTo(1);
        assertThat(memory.state).isEqualTo("POSTED");

        apply(memory, processor.confirm(memory.postings));
        assertThat(memory.postings).isEqualTo(1);
        assertThat(memory.documentId).isNotNull();
    }

    private void apply(MemoryExchange memory, ErpExchangeProcessor.Decision decision) {
        if (decision.skipped()) {
            return;
        }
        if (decision.created()) {
            memory.envelope = true;
            memory.documentId = UUID.randomUUID();
        }
        if (decision.addPosting()) {
            memory.postings++;
        }
        if (decision.posted()) {
            memory.state = "POSTED";
        } else if (decision.created()) {
            memory.state = "AWAITING_CONFIRMATION";
        }
    }

    private static final class MemoryExchange {
        private boolean enabled;
        private String mode;
        private boolean envelope;
        private int postings;
        private String state;
        private UUID documentId;
    }
}
