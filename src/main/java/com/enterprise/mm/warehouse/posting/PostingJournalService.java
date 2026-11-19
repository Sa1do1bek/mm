package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.erp.ErpDocumentKinds;
import com.enterprise.mm.erp.ErpExchangeListener;
import com.enterprise.mm.service.InventoryPeriodGuardService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates one warehouse subledger receipt inside the caller's stock transaction. */
@Service
public class PostingJournalService {
    private static final Logger log = LoggerFactory.getLogger(PostingJournalService.class);
    private final PostingEvidenceStore store;
    private final InventoryPeriodGuardService periodGuardService;
    private final ErpExchangeListener erpExchange;
    @Autowired(required=false)
    private com.enterprise.mm.erp.warehouse.outbound.WarehouseOutboundService warehouseOutbound;

    @Autowired
    public PostingJournalService(PostingEvidenceStore store,
                                 @Autowired(required = false) InventoryPeriodGuardService periodGuardService,
                                 @Autowired(required = false) ErpExchangeListener erpExchange) {
        this.store = store;
        this.periodGuardService = periodGuardService;
        this.erpExchange = erpExchange;
    }

    public PostingJournalService(PostingEvidenceStore store, InventoryPeriodGuardService periodGuardService) {
        this(store, periodGuardService, null);
    }

    public PostingJournalService(PostingEvidenceStore store) {
        this(store, null, null);
    }

    /**
     * Apply local stock, cost, allocation and audit writes in this transaction.
     * Queue an approved outbox event here when its contract is enabled; never deliver it inline.
     */
    @FunctionalInterface
    public interface PostingOperation {
        List<PostingEffect> apply(PostingRule selectedRule);
    }

    @Transactional(readOnly = true)
    public PostingRule preview(PostingRuleQuery query) {
        if (query == null) {
            throw new BadRequestException("Posting rule query is required");
        }
        return selectRuleEvidence(TenantContext.currentTenant(), CompanyContext.currentCompany(), query).rule();
    }

    @Transactional
    public PostingReceipt execute(PostingCommand command, PostingOperation operation) {
        if (command == null || operation == null) {
            throw new BadRequestException("Posting command and operation are required");
        }
        if (periodGuardService != null) {
            periodGuardService.ensureOpen(
                null,
                command.postingDate(),
                command.book() + ":" + command.operation()
            );
        }
        var key = new PostingEvidenceStore.Key(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            command.operation(), command.resourceId(), command.idempotencyKey());
        String requestHash = PostingCommandFingerprint.sha256(command);

        var existing = store.find(key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        String accountCode = command.payload() != null && command.payload().hasNonNull("accountCode")
            ? command.payload().get("accountCode").asText()
            : null;
        var evidence = selectRuleEvidence(key.tenantId(), key.companyId(),
            new PostingRuleQuery(command.book(), command.operation(), command.contextKey(), command.postingDate(), accountCode));
        PostingRule selected = evidence.rule();
        UUID batchId = UUID.randomUUID();
        if (!store.reserve(key, requestHash, command, evidence, batchId)) {
            return replay(store.find(key).orElseThrow(() -> new ConflictException("Posting reservation is unavailable")),
                requestHash);
        }

        List<PostingEffect> effects = operation.apply(selected);
        PostingReceipt receipt = new PostingReceipt(batchId, selected.id(), selected.ruleVersion(),
            command.postingDate(), effects);
        store.complete(batchId, key, receipt);
        if (warehouseOutbound != null) warehouseOutbound.capturePosting(command, receipt);
        return receipt;
    }

    private void notifyExchange(PostingCommand command) {
        if (erpExchange == null || ErpDocumentKinds.fromContext(command.contextKey()) == null) {
            return;
        }
        try {
            erpExchange.acceptFromPosting(command);
        } catch (RuntimeException ex) {
            log.warn("ERP exchange did not roll back warehouse posting {}: {}", command.resourceId(), ex.getMessage());
        }
    }

    private PostingReceipt replay(PostingEvidenceStore.Existing existing, String requestHash) {
        if (!Objects.equals(existing.requestHash(), requestHash)) {
            throw new ConflictException("Idempotency-Key was already used with a different posting payload");
        }
        if (existing.receipt() == null) {
            throw new ConflictException("Posting receipt is not committed");
        }
        return existing.receipt();
    }

    private PostingEvidenceStore.RuleEvidence selectRuleEvidence(String tenantId, UUID companyId,
                                                                 PostingRuleQuery query) {
        var available = store.rules(tenantId, companyId, query);
        PostingRule selected = PostingRuleSelector.select(available.stream().map(PostingEvidenceStore.RuleEvidence::rule).toList(),
            query.book(), query.operation(), query.contextKey(), query.postingDate(), query.accountCode());
        return available.stream().filter(item -> Objects.equals(item.rule().id(), selected.id()))
            .findFirst().orElseThrow();
    }
}
