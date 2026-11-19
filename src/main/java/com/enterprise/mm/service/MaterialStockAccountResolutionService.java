package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.repository.ChartOfAccountRepository;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.payload.PageResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account selection for manual material receipts, shared with Material Matching. */
@Service
@RequiredArgsConstructor
public class MaterialStockAccountResolutionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MaterialRepository materials;
    private final StockAccountResolver accounts;
    private final ChartOfAccountRepository chartOfAccounts;
    private final RoleDataScopeService roleScope;

    public record Account(UUID id, String code, String name) {}
    public record Evidence(UUID entryId, UUID importId, LocalDate snapshotDate, UUID warehouseId,
                           String warehouseCode, String quantity, UUID accountId, String rowSha256) {}
    public record Resolution(UUID materialId, Long materialVersion, boolean applicable, String status,
                             Account configuredAccount, Account effectiveAccount, String effectiveSource,
                             List<Account> candidateAccounts, LocalDate snapshotDate,
                             String evidenceFingerprint, List<Evidence> evidence) {}

    private static final String BALANCES = """
        with latest as (
          select max(snapshot_date) snapshot_date from warehouse_account_imports
          where tenant_id=:tenant and company_id=:company and status='COMPLETED'
        )
        select e.id entry_id, e.import_id, r.snapshot_date, coalesce(e.warehouse_id,lw.master_id) warehouse_id,
               e.warehouse_code, e.quantity, e.row_sha256, c.id account_id,
               trim(c.account_code) account_code, c.name account_name
        from warehouse_account_entries e
        join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id
          and r.company_id=e.company_id and r.status='COMPLETED'
        join latest on latest.snapshot_date=r.snapshot_date
        left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id and lm.company_id=e.company_id
          and lm.reference_kind='MATERIAL' and lm.reference_type=e.material_reference_type
          and lm.source_id=e.material_source_id
        left join warehouse_source_master_links lw on lw.tenant_id=e.tenant_id and lw.company_id=e.company_id
          and lw.reference_kind='WAREHOUSE' and lw.reference_type='00000055'
          and lw.source_id=e.warehouse_source_id
        join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id
          and c.account_code=e.account_code and c.is_delete=false and c.status='ACTIVE'
          and c.is_group=false and trim(c.account_code)<>'' and trim(c.account_code) not like '002%'
        where e.tenant_id=:tenant and e.company_id=:company and e.kind='BALANCE'
          and coalesce(e.material_id,lm.master_id)=:materialId
        order by c.account_code, c.id, e.id
        """;

    @Transactional(readOnly = true)
    public Resolution get(UUID materialId) {
        Material material = materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Material not found"));
        Resolution resolved = resolve(material);
        return new Resolution(resolved.materialId(), resolved.materialVersion(), resolved.applicable(),
            resolved.status(), resolved.configuredAccount(), resolved.effectiveAccount(),
            resolved.effectiveSource(), resolved.candidateAccounts(), resolved.snapshotDate(),
            resolved.evidenceFingerprint(), resolved.evidence().stream()
                .filter(item -> roleScope.canAccessWarehouse(item.warehouseId())).limit(25).toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<Evidence> evidence(UUID materialId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid evidence page or size");
        materials.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .filter(value -> Objects.equals(value.getCompanyId(), CompanyContext.currentCompany()))
            .orElseThrow(() -> new NotFoundException("Material not found"));
        var parameters = new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany()).addValue("materialId", materialId)
            .addValue("limit", size).addValue("offset", (long) page * size);
        var currentScope = roleScope.currentScope();
        parameters.addValue("unrestricted", !currentScope.warehouseScoped());
        parameters.addValue("warehouses", currentScope.warehouseIds().isEmpty()
            ? List.of(new UUID(0, 0)) : currentScope.warehouseIds());
        String scoped = " from (" + BALANCES + ") matching_balances where "
            + "(:unrestricted=true or warehouse_id in (:warehouses))";
        long total = jdbc.queryForObject("select count(*)" + scoped,
            parameters, Long.class);
        List<Evidence> rows = jdbc.query("select *" + scoped
            + " order by account_code,account_id,entry_id limit :limit offset :offset", parameters,
            (row, index) -> new Evidence(row.getObject("entry_id", UUID.class),
                row.getObject("import_id", UUID.class), row.getObject("snapshot_date", LocalDate.class),
                row.getObject("warehouse_id", UUID.class), row.getString("warehouse_code"),
                row.getBigDecimal("quantity").toPlainString(), row.getObject("account_id", UUID.class),
                row.getString("row_sha256")));
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public Resolution resolve(Material material) {
        Account configured = material.getManualReceiptStockAccountId() == null ? null
            : configuredAccount(material.getManualReceiptStockAccountId());
        if (material.getMaterialType() != MaterialType.TMB || !"ACTIVE".equals(material.getStatus())) {
            return new Resolution(material.getId(), material.getVersion(), false, "NOT_APPLICABLE",
                configured, null, null, List.of(), null, fingerprint(material, null, List.of()), List.of());
        }

        var scope = new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany()).addValue("materialId", material.getId());
        List<Evidence> evidence = new ArrayList<>();
        Map<UUID, Account> candidates = new LinkedHashMap<>();
        jdbc.query(BALANCES, scope, row -> {
            UUID accountId = row.getObject("account_id", UUID.class);
            candidates.putIfAbsent(accountId, new Account(accountId, row.getString("account_code"),
                row.getString("account_name")));
            evidence.add(new Evidence(row.getObject("entry_id", UUID.class), row.getObject("import_id", UUID.class),
                row.getObject("snapshot_date", LocalDate.class), row.getObject("warehouse_id", UUID.class),
                row.getString("warehouse_code"), row.getBigDecimal("quantity").toPlainString(),
                accountId, row.getString("row_sha256")));
        });
        LocalDate snapshotDate = jdbc.queryForObject("""
            select max(snapshot_date) from warehouse_account_imports
            where tenant_id=:tenant and company_id=:company and status='COMPLETED'
            """, scope, LocalDate.class);
        List<Account> choices = List.copyOf(candidates.values());
        boolean configuredEligible = configured != null && eligible(configured.id());
        String status = choices.size() > 1 ? "CHOICE_REQUIRED" : choices.size() == 1 ? "AUTO_1C"
            : !configuredEligible ? "ACCOUNT_REQUIRED" : "CONFIGURED_FALLBACK";
        Account effective = choices.size() == 1 ? choices.getFirst()
            : choices.isEmpty() && configuredEligible ? configured : null;
        String source = choices.size() == 1 ? "ONE_C_BALANCE" : choices.isEmpty() && configuredEligible ? "MATERIAL" : null;
        return new Resolution(material.getId(), material.getVersion(), true, status, configured, effective,
            source, choices, snapshotDate, fingerprint(material, snapshotDate, evidence), List.copyOf(evidence));
    }

    public Account requireReceiptAccount(Resolution resolution, UUID selectedId) {
        if (!resolution.applicable()) throw new BadRequestException("Material is unavailable for manual receipt");
        if ("CHOICE_REQUIRED".equals(resolution.status())) {
            if (selectedId == null) throw new BadRequestException("Choose a stock account for this receipt line");
            return resolution.candidateAccounts().stream().filter(candidate -> candidate.id().equals(selectedId))
                .findFirst().orElseThrow(() -> new BadRequestException("Choose an account from the current 1C balance candidates"));
        }
        Account effective = resolution.effectiveAccount();
        if (effective == null) throw new BadRequestException("Configure a stock account for this material before recording a manual receipt");
        if (selectedId != null && !selectedId.equals(effective.id()))
            throw new BadRequestException("Selected stock account differs from the current resolution");
        return effective;
    }

    @Transactional
    public Resolution configureFallback(UUID materialId, UUID accountId, Long expectedVersion,
                                        String expectedEvidenceFingerprint) {
        if (accountId == null || expectedVersion == null || expectedEvidenceFingerprint == null)
            throw new BadRequestException("Account, material version and evidence fingerprint are required");
        Material material = materials.lockForStockBalanceCreation(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), materialId)
            .orElseThrow(() -> new NotFoundException("Material not found"));
        Resolution current = resolve(material);
        if (!expectedVersion.equals(material.getVersion())
            || !expectedEvidenceFingerprint.equals(current.evidenceFingerprint()))
            throw new ConflictException("Material or 1C account evidence changed; review again");
        if (!"ACCOUNT_REQUIRED".equals(current.status())
            && !"CONFIGURED_FALLBACK".equals(current.status()))
            throw new ConflictException("Material account is resolved by 1C or is not applicable");
        accounts.configureManualReceiptAccount(materialId, accountId);
        materials.flush();
        return get(materialId);
    }

    private Account configuredAccount(UUID accountId) {
        return chartOfAccounts.findByIdAndIsDeletedFalse(accountId)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId()))
            .filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId()))
            .map(value -> new Account(value.getId(), value.getAccountCode(), value.getName()))
            .orElse(null);
    }

    private boolean eligible(UUID accountId) {
        try {
            accounts.requireEligibleOwnedAccount(accountId);
            return true;
        } catch (BadRequestException exception) {
            return false;
        }
    }

    private String fingerprint(Material material, LocalDate date, List<Evidence> evidence) {
        StringBuilder value = new StringBuilder().append(material.getId()).append(':')
            .append(material.getVersion()).append(':').append(date).append(':')
            .append(material.getManualReceiptStockAccountId());
        evidence.forEach(item -> value.append(':').append(item.entryId()).append(':')
            .append(item.accountId()).append(':').append(item.rowSha256()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
