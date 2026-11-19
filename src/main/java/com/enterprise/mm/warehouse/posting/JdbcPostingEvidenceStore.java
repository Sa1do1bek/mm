package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcPostingEvidenceStore implements PostingEvidenceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPostingEvidenceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void lockAccountPosition(String tenantId, UUID companyId, UUID materialId,
                                    UUID warehouseId, String accountCode, UUID ownerId,
                                    String stockCategory) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Account position lock requires an active posting transaction");
        }
        String key = String.join("\u001f", tenantId, companyId.toString(), materialId.toString(),
            warehouseId.toString(), accountCode, ownerId == null ? companyId.toString() : ownerId.toString(),
            stockCategory);
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
            rs -> null, key);
    }

    @Override
    public Optional<Existing> find(Key key) {
        return jdbc.query("select request_hash,status,receipt_json::text as receipt_json "
                + "from warehouse_posting_batches where tenant_id=? and company_id=? and operation=? "
                + "and resource_id=? and idempotency_key=?",
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String receiptJson = rs.getString("receipt_json");
                return Optional.of(new Existing(rs.getString("request_hash"),
                    "COMMITTED".equals(rs.getString("status")) ? readReceipt(receiptJson) : null));
            }, key.tenantId(), key.companyId(), key.operation(), key.resourceId(), key.idempotencyKey());
    }

    @Override
    public List<RuleEvidence> rules(String tenantId, UUID companyId, PostingRuleQuery query) {
        StringBuilder sql = new StringBuilder("select id,book,operation,context_key,rule_version,effective_from,effective_through,"
                + "approved,account_code,currency_code,rule_snapshot::text as rule_snapshot "
                + "from warehouse_posting_rules where tenant_id=? and company_id=? and book=? and operation=? "
                + "and context_key=? and approved and effective_from<=? "
                + "and (effective_through is null or effective_through>=?)");
        List<Object> params = new ArrayList<>(List.of(
            tenantId, companyId, query.book(), query.operation(), query.contextKey(),
            query.postingDate(), query.postingDate()
        ));
        if (query.accountCode() != null && !query.accountCode().isBlank()) {
            sql.append(" and (account_code = ? or account_code is null)");
            params.add(query.accountCode().trim());
        }
        return jdbc.query(sql.toString(),
            (rs, rowNum) -> new RuleEvidence(rule(rs), rs.getString("rule_snapshot")),
            params.toArray());
    }

    @Override
    public boolean reserve(Key key, String requestHash, PostingCommand command, RuleEvidence rule, UUID batchId) {
        int inserted = jdbc.update("insert into warehouse_posting_batches "
                + "(id,tenant_id,company_id,book,operation,context_key,resource_id,idempotency_key,request_hash,"
                + "posting_date,expected_version,rule_id,rule_version,rule_snapshot,status) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING') "
                + "on conflict on constraint warehouse_posting_batch_identity do nothing",
            batchId, key.tenantId(), key.companyId(), command.book(), command.operation(), command.contextKey(),
            key.resourceId(), key.idempotencyKey(), requestHash, command.postingDate(), command.expectedVersion(),
            rule.rule().id(), rule.rule().ruleVersion(), rule.snapshotJson());
        return inserted == 1;
    }

    @Override
    public void complete(UUID batchId, Key key, PostingReceipt receipt) {
        int sequence = 0;
        for (PostingEffect effect : receipt.effects()) {
            jdbc.update("insert into warehouse_posting_effects "
                    + "(batch_id,effect_sequence,effect_type,material_id,warehouse_id,account_code,base_unit_code,"
                    + "quantity,amount,currency_code,storage_location_id,storage_bin_id,stock_batch_id,owner_id,"
                    + "stock_category,disposition,source_reference) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                batchId, ++sequence, effect.type().name(), effect.materialId(), effect.warehouseId(),
                effect.accountCode(), effect.baseUnitCode(), effect.quantity(), effect.amount(), effect.currencyCode(),
                effect.storageLocationId(), effect.storageBinId(), effect.stockBatchId(), effect.ownerId(),
                effect.stockCategory(), effect.disposition(), effect.sourceReference());
        }
        int updated = jdbc.update("update warehouse_posting_batches "
                + "set status='COMMITTED',receipt_json=?::jsonb,committed_at=current_timestamp "
                + "where id=? and tenant_id=? and company_id=? and status='PENDING'",
            writeReceipt(receipt), batchId, key.tenantId(), key.companyId());
        if (updated != 1) {
            throw new ConflictException("Posting reservation was not pending in the current scope");
        }
    }

    @Override
    public BigDecimal getAccountAvailableQuantity(String tenantId, UUID companyId, UUID materialId, UUID warehouseId, String accountCode) {
        Integer count = jdbc.queryForObject(
            "select count(*) from warehouse_posting_effects e "
            + "join warehouse_posting_batches b on b.id = e.batch_id "
            + "where b.tenant_id = ? and b.company_id = ? "
            + "and e.material_id = ? and e.warehouse_id = ? "
            + "and e.effect_type = 'ACCOUNT' and b.status = 'COMMITTED'",
            Integer.class,
            tenantId, companyId, materialId, warehouseId
        );
        if (count == null || count == 0) {
            return null;
        }

        BigDecimal qty = jdbc.queryForObject(
            "select coalesce(sum(e.quantity), 0) from warehouse_posting_effects e "
            + "join warehouse_posting_batches b on b.id = e.batch_id "
            + "where b.tenant_id = ? and b.company_id = ? "
            + "and e.material_id = ? and e.warehouse_id = ? "
            + "and e.account_code = ? and e.effect_type = 'ACCOUNT' and b.status = 'COMMITTED'",
            BigDecimal.class,
            tenantId, companyId, materialId, warehouseId, accountCode
        );
        return qty == null ? BigDecimal.ZERO : qty;
    }

    @Override
    public BigDecimal getAccountAndOwnerAvailableQuantity(
        String tenantId, UUID companyId, UUID materialId, UUID warehouseId,
        String accountCode, UUID ownerId, String stockCategory
    ) {
        Integer count = jdbc.queryForObject(
            "select count(*) from warehouse_posting_effects e "
            + "join warehouse_posting_batches b on b.id = e.batch_id "
            + "where b.tenant_id = ? and b.company_id = ? "
            + "and e.material_id = ? and e.warehouse_id = ? "
            + "and e.effect_type = 'ACCOUNT' and b.status = 'COMMITTED'",
            Integer.class,
            tenantId, companyId, materialId, warehouseId
        );
        if (count == null || count == 0) {
            return null;
        }

        StringBuilder sql = new StringBuilder(
            "select coalesce(sum(e.quantity), 0) from warehouse_posting_effects e "
            + "join warehouse_posting_batches b on b.id = e.batch_id "
            + "where b.tenant_id = ? and b.company_id = ? "
            + "and e.material_id = ? and e.warehouse_id = ? "
            + "and e.effect_type = 'ACCOUNT' and b.status = 'COMMITTED'"
        );
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(companyId);
        params.add(materialId);
        params.add(warehouseId);

        if (accountCode != null) {
            sql.append(" and e.account_code = ?");
            params.add(accountCode);
        }
        if (ownerId != null) {
            sql.append(" and e.owner_id = ?");
            params.add(ownerId);
        } else {
            sql.append(" and (e.owner_id is null or e.owner_id = ?)");
            params.add(companyId);
        }
        if (stockCategory != null) {
            sql.append(" and e.stock_category = ?");
            params.add(stockCategory);
        }

        BigDecimal qty = jdbc.queryForObject(sql.toString(), BigDecimal.class, params.toArray());
        return qty == null ? BigDecimal.ZERO : qty;
    }

    @Override
    public BigDecimal getAccountAndOwnerAvailableQuantityForUnit(
        String tenantId, UUID companyId, UUID materialId, UUID warehouseId,
        String accountCode, UUID ownerId, String stockCategory, String baseUnitCode,
        String currencyCode
    ) {
        BigDecimal quantity = jdbc.queryForObject(
            "select coalesce(sum(e.quantity), 0) from warehouse_posting_effects e "
                + "join warehouse_posting_batches b on b.id = e.batch_id "
                + "where b.tenant_id = ? and b.company_id = ? and b.status = 'COMMITTED' "
                + "and e.effect_type = 'ACCOUNT' and e.material_id = ? and e.warehouse_id = ? "
                + "and e.account_code = ? and coalesce(e.owner_id, b.company_id) = ? "
                + "and coalesce(e.stock_category, 'OWNED') = ? "
                + "and e.base_unit_code = ? and e.currency_code = ?",
            BigDecimal.class, tenantId, companyId, materialId, warehouseId, accountCode,
            ownerId != null ? ownerId : companyId, stockCategory, baseUnitCode, currencyCode);
        return quantity == null ? BigDecimal.ZERO : quantity;
    }

    private PostingRule rule(ResultSet rs) throws SQLException {
        return new PostingRule(rs.getObject("id", UUID.class), rs.getString("book"), rs.getString("operation"),
            rs.getString("context_key"), rs.getInt("rule_version"),
            rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_through", LocalDate.class),
            rs.getBoolean("approved"), rs.getString("account_code"), rs.getString("currency_code"));
    }

    private PostingReceipt readReceipt(String json) {
        if (json == null) {
            throw new ConflictException("Committed posting has no receipt");
        }
        try {
            return mapper.readValue(json, PostingReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Cannot restore posting receipt");
        }
    }

    private String writeReceipt(PostingReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Cannot persist posting receipt");
        }
    }
}
