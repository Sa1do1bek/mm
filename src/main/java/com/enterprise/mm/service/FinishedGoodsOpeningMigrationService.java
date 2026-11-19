package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.StockMovement;
import com.enterprise.mm.enums.MovementType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Supplemental, source-backed opening for 1C finished goods, separate from the original 10xx import. */
@Service
public class FinishedGoodsOpeningMigrationService {
    private static final UUID NAVOIYAZOT = UUID.fromString("753e618e-8d47-4efd-a41e-df8311b83d2f");
    private static final String NAVOIYAZOT_SOURCE_ORG = "af14001b2423947111dd5d6ef8f82783";
    private static final LocalDate AUTHORIZED_SNAPSHOT = LocalDate.of(2026, 10, 8);
    private final FinishedGoodsOpeningReader reader;
    private final JdbcTemplate jdbc;
    private final InventoryScopeLockService locks;
    private final StockMovementService posting;
    private final ObjectMapper json;
    private final RoleDataScopeService access;
    private final AuditService audit;

    public FinishedGoodsOpeningMigrationService(FinishedGoodsOpeningReader reader, JdbcTemplate jdbc,
            InventoryScopeLockService locks, StockMovementService posting, ObjectMapper json, RoleDataScopeService access,
            AuditService audit) {
        this.reader = reader; this.jdbc = jdbc; this.locks = locks; this.posting = posting; this.json = json;
        this.access = access;
        this.audit = audit;
    }

    public record AccountAmount(String accountCode, String quantity, String amount) {}
    public record Entry(UUID materialId, UUID warehouseId,
                        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal quantity,
                        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,
                        String status, String sourceHash, List<String> sourceKeys,
                        String materialCode, String materialName, String warehouseName, String unit,
                        List<AccountAmount> accountBreakdown) {}
    public record Preview(String hash, List<Entry> entries) {}
    public record Result(int postedCount, List<UUID> movementIds) {}
    private record Target(UUID warehouseId, UUID materialId) {}
    private record Master(boolean valid, boolean unitValid, boolean uncontrolled) {}
    private record Evaluated(FinishedGoodsOpeningCalculator.Position source, Entry entry) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')")
    public Preview preview(LocalDate date, UUID warehouse) {
        requireScope(date, warehouse);
        access.warehouseReadScope(warehouse, null);
        List<Evaluated> evaluated = evaluate(date, warehouse);
        return new Preview(previewHash(date, warehouse, evaluated), evaluated.stream().map(Evaluated::entry).toList());
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Result apply(LocalDate date, UUID warehouse, String hash, String idempotencyKey) {
        requireScope(date, warehouse);
        access.warehouseReadScope(warehouse, null);
        if (hash == null || !hash.matches("[a-f0-9]{64}") || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 160)
            throw new BadRequestException("A preview hash and idempotency key are required");
        String tenant = TenantContext.currentTenant(); UUID company = CompanyContext.currentCompany();
        // Every stock mutation participates in the company lock, so the review and writes
        // see a stable inventory boundary even when another warehouse user is active.
        locks.lock(company, List.of());
        // Prevent a concurrent source import from completing between source review and posting.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                try (var statement = connection.createStatement()) {
                    statement.execute("lock table warehouse_account_imports in share mode");
                    statement.execute("lock table warehouse_history_runs, warehouse_history_records, warehouse_source_master_links, warehouse_imported_movements in share mode");
                }
            }
            return null;
        });
        requireScope(date, warehouse);
        if (count("""
            select count(*) from warehouse_account_imports
            where tenant_id=? and company_id=? and snapshot_date>=? and status='IMPORTING'
            """, tenant, company, date) > 0)
            throw new ConflictException("A source import for this or a later snapshot is still in progress");
        if (count("select count(*) from warehouse_history_runs where tenant_id=? and company_id=? and status='IMPORTING'", tenant, company) > 0)
            throw new ConflictException("A 1C history import is still in progress");
        if (count("""
            select count(*) from warehouse_account_imports
            where tenant_id=? and company_id=? and snapshot_date>? and status='COMPLETED'
            """, tenant, company, date) > 0)
            throw new ConflictException("A newer source snapshot requires reconciliation before this opening");
        var previous = jdbc.queryForList("""
            select id,warehouse_id,snapshot_date,preview_sha256,posted_count from finished_goods_opening_runs
            where tenant_id=? and company_id=? and idempotency_key=?
            """, tenant, company, idempotencyKey);
        if (!previous.isEmpty()) {
            Map<String, Object> run = previous.getFirst();
            if (!warehouse.equals(run.get("warehouse_id")) || !date.equals(((java.sql.Date) run.get("snapshot_date")).toLocalDate())
                    || !hash.equals(run.get("preview_sha256")))
                throw new ConflictException("Finished goods opening idempotency key was used with different input");
            UUID runId = (UUID) run.get("id");
            var movements = jdbc.queryForList("""
                select movement_id from finished_goods_opening_positions
                where tenant_id=? and company_id=? and run_id=? order by material_id
                """, UUID.class, tenant, company, runId);
            return new Result(((Number) run.get("posted_count")).intValue(), List.copyOf(movements));
        }
        List<Evaluated> evaluated = evaluate(date, warehouse);
        if (!hash.equals(previewHash(date, warehouse, evaluated)))
            throw new ConflictException("Finished goods source or live stock changed; refresh the preview");
        UUID runId = UUID.randomUUID();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank())
            throw new BadRequestException("An authenticated administrator is required");
        jdbc.update("""
            insert into finished_goods_opening_runs
            (id,tenant_id,company_id,warehouse_id,snapshot_date,idempotency_key,preview_sha256,posted_count,created_by)
            values (?,?,?,?,?,?,?,?,?)
            """, runId, tenant, company, warehouse, date, idempotencyKey, hash, 0, auth.getName());
        audit.logInCurrentTransaction("FinishedGoodsOpeningRun", runId, "IMPORT",
            "Authorized 2026-10-08 opening; later 1C movements unavailable and accepted",
            null, Map.of("warehouseId", warehouse.toString(), "snapshotDate", date.toString(),
                "acceptUnreconciled1cMovements", true));
        List<UUID> movements = OpeningImportContext.within(company, runId, () -> {
            List<UUID> posted = new ArrayList<>();
            for (Evaluated item : evaluated) {
                if (!"ELIGIBLE".equals(item.entry().status())) continue;
                var source = item.source();
                StockMovement movement = new StockMovement();
                movement.setTenantId(tenant); movement.setCompanyId(company);
                movement.setMaterialId(source.materialId()); movement.setWarehouseId(warehouse);
                movement.setMovementType(MovementType.RECEIPT); movement.setMovementDate(date);
                movement.setQuantity(source.quantity()); movement.setReferenceType("OPENING_STOCK_EXACT");
                movement.setReferenceId(runId); movement.setBatchNumber("1C-FG-" + item.entry().sourceHash());
                movement.setDescription("1C finished goods supplemental opening; source=" + item.entry().sourceHash());
                StockMovement saved = posting.postExactOpening(movement, source.amount());
                jdbc.update("""
                    insert into finished_goods_opening_positions
                    (id,tenant_id,company_id,warehouse_id,material_id,snapshot_date,source_sha256,source_evidence,movement_id,run_id)
                    values (?,?,?,?,?,?,?,cast(? as jsonb),?,?)
                    """, UUID.randomUUID(), tenant, company, warehouse, source.materialId(), date,
                    item.entry().sourceHash(), evidence(source), saved.getId(), runId);
                posted.add(saved.getId());
            }
            return posted;
        });
        jdbc.update("update finished_goods_opening_runs set posted_count=? where id=?", movements.size(), runId);
        List<UUID> ordered = jdbc.queryForList("""
            select movement_id from finished_goods_opening_positions
            where tenant_id=? and company_id=? and run_id=? order by material_id
            """, UUID.class, tenant, company, runId);
        return new Result(movements.size(), List.copyOf(ordered));
    }

    private List<Evaluated> evaluate(LocalDate date, UUID warehouse) {
        String tenant = TenantContext.currentTenant(); UUID company = CompanyContext.currentCompany();
        var positions = reader.preview(tenant, company, date, warehouse);
        Map<Target, Integer> targetCounts = new HashMap<>();
        for (var source : positions) if (source.materialId() != null && source.warehouseId() != null)
            targetCounts.merge(new Target(source.warehouseId(), source.materialId()), 1, Integer::sum);
        boolean openingIncomplete = count("""
            select count(*) from exact_opening_imports where tenant_id=? and company_id=? and status='COMPLETED'
            """, tenant, company) != 1;
        List<Evaluated> result = new ArrayList<>();
        for (var source : positions) {
            String sourceHash = sourceHash(source);
            String status = source.status();
            if (source.materialId() != null && source.warehouseId() != null) {
                var previous = jdbc.queryForList("""
                    select source_sha256 from finished_goods_opening_positions
                    where tenant_id=? and company_id=? and snapshot_date=? and warehouse_id=? and material_id=?
                    """, String.class, tenant, company, date, source.warehouseId(), source.materialId());
                if (!previous.isEmpty()) status = previous.getFirst().equals(sourceHash) ? "ALREADY_POSTED" : "SOURCE_CHANGED_REVIEW";
                else if ("LIVE_RECONCILIATION_REQUIRED".equals(status)) {
                    boolean laterSourceMovement = count("""
                        select count(*) from warehouse_account_entries movement
                        join warehouse_account_imports source on source.id=movement.import_id
                          and source.tenant_id=movement.tenant_id and source.company_id=movement.company_id
                          and source.status='COMPLETED'
                        where movement.tenant_id=? and movement.company_id=? and movement.kind='MOVEMENT'
                          and movement.source_date>? and movement.material_reference_type=?
                          and movement.material_source_id=? and movement.warehouse_source_id=?
                        """, tenant, company, date, source.key().materialReferenceType(),
                        source.key().materialSourceId(), source.key().warehouseSourceId()) > 0;
                    laterSourceMovement = laterSourceMovement || count("""
                        select count(*) from warehouse_imported_movements h
                        where h.tenant_id=? and h.company_id=? and h.movement_date>?
                          and (h.material_id=? or h.material_source_id=?)
                          and (h.warehouse_id=? or h.target_warehouse_id=? or h.warehouse_source_id=?)
                        """, tenant, company, date, source.materialId(), source.key().materialSourceId(),
                        source.warehouseId(), source.warehouseId(), source.key().warehouseSourceId()) > 0;
                    laterSourceMovement = laterSourceMovement || count("""
                        select count(*) from warehouse_history_records h
                        join warehouse_history_runs run on run.id=h.import_id and run.tenant_id=h.tenant_id
                          and run.company_id=h.company_id and run.status='COMPLETED'
                        where h.tenant_id=? and h.company_id=? and h.kind='MOVEMENT' and h.posted is distinct from false
                          and h.document_date>=cast(? as date)+1
                          and (h.material_id=? or cast(h.content as jsonb)->>'materialSourceId'=?)
                          and (h.warehouse_id=? or h.target_warehouse_id=? or cast(h.content as jsonb)->>'warehouseSourceId'=?
                               or cast(h.content as jsonb)->>'targetWarehouseSourceId'=?)
                        """, tenant, company, date, source.materialId(), source.key().materialSourceId(),
                        source.warehouseId(), source.warehouseId(), source.key().warehouseSourceId(), source.key().warehouseSourceId()) > 0;
                    Master master = master(tenant, company, source);
                    boolean hasStock = count("""
                        select count(*) from stock_balances where tenant_id=? and company_id=? and material_id=?
                          and warehouse_id=? and is_delete=false
                        """, tenant, company, source.materialId(), source.warehouseId()) > 0;
                    boolean hasMovement = count("""
                        select count(*) from stock_movements where tenant_id=? and company_id=? and material_id=?
                          and (warehouse_id=? or target_warehouse_id=?) and is_delete=false and status='POSTED'
                        """, tenant, company, source.materialId(), source.warehouseId(), source.warehouseId()) > 0;
                    boolean duplicates = targetCounts.getOrDefault(new Target(source.warehouseId(), source.materialId()), 0) > 1;
                    status = laterSourceMovement ? "SOURCE_ACTIVITY_REVIEW" : FinishedGoodsOpeningLiveReview.classify(status,
                        new FinishedGoodsOpeningLiveReview.Evidence(master.valid(), master.unitValid(), master.uncontrolled(),
                            hasStock, hasMovement, duplicates, openingIncomplete));
                }
            }
            var details = jdbc.queryForList("""
                select material_code,material_name,warehouse_name,uom_name_ru from warehouse_account_entries
                where tenant_id=? and company_id=? and source_date=? and source_key=? and kind='BALANCE'
                """, tenant, company, date, source.sourceKeys().getFirst());
            Map<String,Object> names = details.size() == 1 ? details.getFirst() : Map.of();
            Map<String,BigDecimal[]> totals = new java.util.TreeMap<>();
            for (var row : source.sources()) {
                var pair = totals.computeIfAbsent(row.accountCode(), ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                pair[0] = pair[0].add(row.quantity()); pair[1] = pair[1].add(row.amount());
            }
            var breakdown = totals.entrySet().stream().map(pair -> new AccountAmount(pair.getKey(),
                pair.getValue()[0].toPlainString(), pair.getValue()[1].toPlainString())).toList();
            result.add(new Evaluated(source, new Entry(source.materialId(), source.warehouseId(), source.quantity(),
                source.amount(), status, sourceHash, source.sourceKeys(), Objects.toString(names.get("material_code"), ""),
                Objects.toString(names.get("material_name"), ""), Objects.toString(names.get("warehouse_name"), ""),
                Objects.toString(names.get("uom_name_ru"), source.key().uom()), breakdown)));
        }
        return result;
    }

    private Master master(String tenant, UUID company, FinishedGoodsOpeningCalculator.Position source) {
        var records = jdbc.queryForList("""
            select m.material_type,m.material_subtype,m.serial_tracking_policy,m.incoming_inspection_required,
                   u.code,u.measurement_name_short,u.measurement_name_short_ru,u.measurement_name_short_uz,
                   u.unit_type,u.stock_quantity_scale
            from materials m join unit_of_measures u on u.id=m.unit_of_measure_id
              and u.tenant_id=m.tenant_id and u.company_id=m.company_id and u.is_delete=false and u.status='ACTIVE'
            join warehouses w on w.id=? and w.tenant_id=m.tenant_id and w.company_id=m.company_id
              and w.is_delete=false and w.status='ACTIVE'
            where m.id=? and m.tenant_id=? and m.company_id=? and m.is_delete=false and m.status='ACTIVE'
            """, source.warehouseId(), source.materialId(), tenant, company);
        if (records.size() != 1) return new Master(false, false, false);
        Map<String, Object> record = records.getFirst();
        boolean valid = "TMB".equals(record.get("material_type")) && "FINISHED_GOODS".equals(record.get("material_subtype"));
        boolean unitMatches = List.of("code", "measurement_name_short", "measurement_name_short_ru", "measurement_name_short_uz")
            .stream().map(record::get).filter(Objects::nonNull).map(Object::toString)
            .anyMatch(unit -> unit.trim().equalsIgnoreCase(source.key().uom().trim()));
        Integer configured = (Integer) record.get("stock_quantity_scale");
        int scale = configured != null ? configured : "QUANTITY".equals(record.get("unit_type"))
            || "EA".equalsIgnoreCase(Objects.toString(record.get("code"), "")) ? 0 : 4;
        boolean unitValid = unitMatches && source.quantity().stripTrailingZeros().scale() <= scale
            && source.quantity().stripTrailingZeros().precision() - source.quantity().stripTrailingZeros().scale() <= 15
            && source.amount().stripTrailingZeros().scale() <= 2;
        boolean uncontrolled = !Boolean.TRUE.equals(record.get("incoming_inspection_required"))
            && !"REQUIRED".equals(record.get("serial_tracking_policy"));
        return new Master(valid, unitValid, uncontrolled);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private String sourceHash(FinishedGoodsOpeningCalculator.Position position) {
        var builder = new StringBuilder().append(position.key()).append('\0').append(position.quantity().toPlainString())
            .append('\0').append(position.amount().toPlainString());
        position.sources().forEach(row -> builder.append('\0').append(row.sourceKey()).append('\0').append(row.sourceHash()));
        return sha256(builder.toString());
    }

    private String previewHash(LocalDate date, UUID warehouse, List<Evaluated> evaluated) {
        var builder = new StringBuilder().append(TenantContext.currentTenant()).append('\0')
            .append(CompanyContext.currentCompany()).append('\0').append(date).append('\0').append(warehouse);
        evaluated.forEach(item -> builder.append('\0').append(item.entry().sourceHash()).append('\0')
            .append(item.entry().status()).append('\0').append(item.entry().materialId()));
        return sha256(builder.toString());
    }

    private String evidence(FinishedGoodsOpeningCalculator.Position position) {
        try { return json.writeValueAsString(position.sources()); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Cannot preserve source evidence", error); }
    }

    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private void requireScope(LocalDate date, UUID warehouse) {
        if (TenantContext.currentTenant() == null || CompanyContext.currentCompany() == null
                || CompanyContext.DEFAULT_COMPANY_ID.equals(CompanyContext.currentCompany()) || date == null || warehouse == null)
            throw new BadRequestException("An explicit company, warehouse and snapshot date are required");
        if (NAVOIYAZOT.equals(CompanyContext.currentCompany())) {
            if (!AUTHORIZED_SNAPSHOT.equals(date))
                throw new BadRequestException("The authorized Navoiyazot snapshot is 2026-10-08");
            int sources = count("""
                select count(*) from warehouse_account_imports
                where tenant_id=? and company_id=? and snapshot_date=? and status='COMPLETED'
                  and organization_source_id=?
                """, TenantContext.currentTenant(), NAVOIYAZOT, date, NAVOIYAZOT_SOURCE_ORG);
            int otherSources = count("""
                select count(*) from warehouse_account_imports
                where tenant_id=? and company_id=? and snapshot_date=? and status='COMPLETED'
                  and organization_source_id is distinct from ?
                """, TenantContext.currentTenant(), NAVOIYAZOT, date, NAVOIYAZOT_SOURCE_ORG);
            if (sources == 0 || otherSources != 0)
                throw new BadRequestException("The source imports do not match the authorized 1C organization");
        }
    }
}
