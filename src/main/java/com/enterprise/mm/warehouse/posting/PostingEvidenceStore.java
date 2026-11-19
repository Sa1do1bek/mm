package com.enterprise.mm.warehouse.posting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage operations participate in the caller's posting transaction. */
public interface PostingEvidenceStore {
    record Key(String tenantId, UUID companyId, String operation, UUID resourceId, String idempotencyKey) {
    }

    record Existing(String requestHash, PostingReceipt receipt) {
    }

    record RuleEvidence(PostingRule rule, String snapshotJson) {
    }

    Optional<Existing> find(Key key);

    List<RuleEvidence> rules(String tenantId, UUID companyId, PostingRuleQuery query);

    /** Returns false after a concurrent identical key has committed its reservation. */
    boolean reserve(Key key, String requestHash, PostingCommand command, RuleEvidence rule, UUID batchId);

    void complete(UUID batchId, Key key, PostingReceipt receipt);

    /** Serialize all postings to one account position across physical stock rows. */
    default void lockAccountPosition(String tenantId, UUID companyId, UUID materialId,
                                     UUID warehouseId, String accountCode, UUID ownerId,
                                     String stockCategory) {
    }

    /**
     * Returns the committed net quantity available on the given account for the material and warehouse.
     * Returns null if no account-level effects exist yet for this material and warehouse.
     */
    BigDecimal getAccountAvailableQuantity(String tenantId, UUID companyId, UUID materialId, UUID warehouseId, String accountCode);

    /**
     * Returns the committed net quantity available on the given account, owner, and stock category.
     * Returns null if no account-level effects exist yet for this material and warehouse.
     */
    default BigDecimal getAccountAndOwnerAvailableQuantity(
        String tenantId, UUID companyId, UUID materialId, UUID warehouseId,
        String accountCode, UUID ownerId, String stockCategory
    ) {
        return getAccountAvailableQuantity(tenantId, companyId, materialId, warehouseId, accountCode);
    }

    /** Availability for one exact accounting quantity unit and currency. */
    default BigDecimal getAccountAndOwnerAvailableQuantityForUnit(
        String tenantId, UUID companyId, UUID materialId, UUID warehouseId,
        String accountCode, UUID ownerId, String stockCategory, String baseUnitCode,
        String currencyCode
    ) {
        return getAccountAndOwnerAvailableQuantity(tenantId, companyId, materialId, warehouseId,
            accountCode, ownerId, stockCategory);
    }
}
