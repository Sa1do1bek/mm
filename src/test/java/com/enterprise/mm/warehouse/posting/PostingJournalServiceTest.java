package com.enterprise.mm.warehouse.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostingJournalServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryStore store = new MemoryStore();
    private final PostingJournalService service = new PostingJournalService(store);
    private final UUID resourceId = UUID.randomUUID();

    @BeforeEach
    void setScope() {
        TenantContext.setTenant("warehouse-test");
        CompanyContext.setCompany(UUID.randomUUID());
    }

    @AfterEach
    void clearScope() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void committedReplayReturnsReceiptBeforeStaleVersionCallback() throws Exception {
        var command = command("{\"quantity\":3}");
        AtomicInteger mutations = new AtomicInteger();
        var first = service.execute(command, rule -> {
            mutations.incrementAndGet();
            return List.of(stockEffect());
        });
        var retry = service.execute(command, rule -> {
            throw new ConflictException("stale version");
        });

        assertThat(retry).isEqualTo(first);
        assertThat(mutations).hasValue(1);
        assertThat(store.records).hasSize(1);
    }

    @Test
    void sameKeyWithChangedPayloadConflictsWithoutMutation() throws Exception {
        service.execute(command("{\"quantity\":3}"), rule -> List.of(stockEffect()));
        AtomicInteger mutations = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(command("{\"quantity\":4}"), rule -> {
            mutations.incrementAndGet();
            return List.of(stockEffect());
        })).isInstanceOf(ConflictException.class).hasMessageContaining("different");
        assertThat(mutations).hasValue(0);
    }

    @Test
    void ambiguousRuleStopsBeforeMutationOrReservation() throws Exception {
        store.rules = List.of(store.rule, store.rule);
        AtomicInteger mutations = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(command("{\"quantity\":3}"), rule -> {
            mutations.incrementAndGet();
            return List.of(stockEffect());
        })).isInstanceOf(ConflictException.class).hasMessageContaining("Ambiguous");
        assertThat(mutations).hasValue(0);
        assertThat(store.records).isEmpty();
    }

    @Test
    void rulePreviewIsReadOnlyAndDoesNotNeedAnIdempotencyKey() {
        var preview = service.preview(new PostingRuleQuery("WAREHOUSE", "ISSUE", "OWNED",
            LocalDate.of(2026, 10, 14)));

        assertThat(preview).isEqualTo(store.rule.rule());
        assertThat(store.records).isEmpty();
    }

    private PostingCommand command(String payload) throws Exception {
        return new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", resourceId,
            "issue-123", LocalDate.of(2026, 10, 14), 7L, mapper.readTree(payload));
    }

    private PostingEffect stockEffect() {
        return new PostingEffect(PostingEffectType.STOCK, UUID.randomUUID(), UUID.randomUUID(),
            "1010", "PCS", new BigDecimal("-3"), BigDecimal.ZERO, null);
    }

    private static final class MemoryStore implements PostingEvidenceStore {
        private final Map<Key, Existing> records = new HashMap<>();
        private final RuleEvidence rule = new RuleEvidence(new PostingRule(UUID.randomUUID(), "WAREHOUSE", "ISSUE",
            "OWNED", 1, LocalDate.of(2026, 10, 1), null, true, "1010", "UZS"), "{\"accountCode\":\"1010\"}");
        private List<RuleEvidence> rules = List.of(rule);

        @Override
        public Optional<Existing> find(Key key) {
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public List<RuleEvidence> rules(String tenantId, UUID companyId, PostingRuleQuery query) {
            return rules;
        }

        @Override
        public boolean reserve(Key key, String requestHash, PostingCommand command, RuleEvidence rule, UUID batchId) {
            if (records.containsKey(key)) {
                return false;
            }
            records.put(key, new Existing(requestHash, null));
            return true;
        }

        @Override
        public void complete(UUID batchId, Key key, PostingReceipt receipt) {
            Existing pending = records.get(key);
            records.put(key, new Existing(pending.requestHash(), receipt));
        }

        @Override
        public BigDecimal getAccountAvailableQuantity(String tenantId, UUID companyId, UUID materialId, UUID warehouseId, String accountCode) {
            return null;
        }
    }
}
