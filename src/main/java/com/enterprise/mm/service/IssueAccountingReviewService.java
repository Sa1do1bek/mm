package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounting classification is reviewed independently of the immutable physical posting. */
@Service
@PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')")
public class IssueAccountingReviewService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RoleDataScopeService access;

    public IssueAccountingReviewService(JdbcTemplate jdbc, AuditService audit, RoleDataScopeService access) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.access = access;
    }

    public record Page(List<Map<String,Object>> content, int page, int size, long totalElements, int totalPages) {}

    @Transactional(readOnly = true)
    public Page list(String status, int page, int size) {
        scope();
        if (!List.of("PENDING_REVIEW", "RESOLVED").contains(status) || page < 0 || size < 1 || size > 100)
            throw new BadRequestException("Invalid accounting review filter");
        String tenant = TenantContext.currentTenant(); UUID company = CompanyContext.currentCompany();
        var warehouses = access.warehouseReadScope(null, null);
        var args = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("tenant", tenant).addValue("company", company).addValue("status", status)
            .addValue("warehouses", warehouses).addValue("size", size).addValue("offset", (long) page * size);
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        String scopeFilter = warehouses.isEmpty() ? "" : " and m.warehouse_id in (:warehouses) ";
        Long total = named.queryForObject("""
            select count(*) from stock_issue_account_reviews r join stock_movements m on m.id=r.movement_id
            where r.tenant_id=:tenant and r.company_id=:company and r.status=:status and m.tenant_id=r.tenant_id
              and m.company_id=r.company_id and not m.is_delete and m.status in ('POSTED','CANCELLED')
            """ + scopeFilter, args, Long.class);
        List<Map<String,Object>> entries = named.queryForList("""
            select r.id, r.movement_id as "movementId", r.status, r.stock_account_code as "stockAccountCode",
                   r.counter_account_code as "counterAccountCode", r.reviewed_by as "reviewedBy",
                   r.reviewed_at as "reviewedAt", r.review_note as "reviewNote", m.movement_date as "movementDate",
                   m.reference_type as "referenceType", cast(m.quantity as text) as quantity,
                   cast(m.total_cost as text) as "totalCost",
                   m.account_code_snapshot as "previousAccountCode", m.material_id as "materialId",
                   p.code as "materialCode", p.name as "materialName", m.warehouse_id as "warehouseId",
                   w.name as "warehouseName", m.document_id as "documentId",
                   coalesce(u.measurement_name_short_uz,u.measurement_name_short_ru,u.code) as "unit",
                   k.source_snapshot->>'buyerName' as "buyerName",
                   k.source_snapshot->>'transferType' as "transferType",
                   (select string_agg(distinct part->>'accountCode', ', ' order by part->>'accountCode')
                      from jsonb_array_elements(fg.source_evidence) part) as "sourceAccountCodes"
            from stock_issue_account_reviews r join stock_movements m on m.id=r.movement_id
              and m.tenant_id=r.tenant_id and m.company_id=r.company_id
            join materials p on p.id=m.material_id and p.tenant_id=r.tenant_id and p.company_id=r.company_id
            join warehouses w on w.id=m.warehouse_id and w.tenant_id=r.tenant_id and w.company_id=r.company_id
            left join unit_of_measures u on u.id=coalesce(m.uom_id_snapshot,p.unit_of_measure_id)
              and u.tenant_id=r.tenant_id and u.company_id=r.company_id
            left join kimyosanoat_issue_links k on k.movement_document_id=m.document_id
              and k.tenant_id=r.tenant_id and k.company_id=r.company_id
            left join lateral (select source_evidence from finished_goods_opening_positions fg
              where fg.tenant_id=r.tenant_id and fg.company_id=r.company_id and fg.material_id=m.material_id
                and fg.warehouse_id=m.warehouse_id and fg.snapshot_date<=m.movement_date
              order by fg.snapshot_date desc limit 1) fg on true
            where r.tenant_id=:tenant and r.company_id=:company and r.status=:status and not m.is_delete and m.status in ('POSTED','CANCELLED')
            """ + scopeFilter + " order by r.created_at,r.id limit :size offset :offset", args);
        long count = total == null ? 0 : total;
        return new Page(List.copyOf(entries), page, size, count, (int) ((count + size - 1) / size));
    }

    @Transactional
    public void resolve(UUID id, String stockAccount, String counterAccount, String note) {
        scope();
        String stock = code(stockAccount), counter = code(counterAccount);
        if (id == null || note == null || note.isBlank() || note.length() > 2000)
            throw new BadRequestException("An accounting review and evidence note are required");
        String tenant = TenantContext.currentTenant(); UUID company = CompanyContext.currentCompany();
        var found = jdbc.queryForList("""
            select r.status,r.stock_account_code,r.counter_account_code,m.status as movement_status,m.is_delete,m.warehouse_id
            from stock_issue_account_reviews r join stock_movements m on m.id=r.movement_id
              and m.tenant_id=r.tenant_id and m.company_id=r.company_id
            where r.id=? and r.tenant_id=? and r.company_id=? for update of r,m
            """, id, tenant, company);
        if (found.isEmpty()) throw new NotFoundException("Accounting review not found");
        var row = found.getFirst();
        access.warehouseReadScope((UUID) row.get("warehouse_id"), null);
        if ("RESOLVED".equals(row.get("status"))) {
            if (stock.equals(row.get("stock_account_code")) && counter.equals(row.get("counter_account_code"))) return;
            throw new ConflictException("Accounting review was already resolved with different accounts");
        }
        if (!List.of("POSTED", "CANCELLED").contains(row.get("movement_status")) || Boolean.TRUE.equals(row.get("is_delete")))
            throw new ConflictException("Only a posted issue or its cancelled original can be classified");
        requireAccount(tenant, company, stock);
        requireAccount(tenant, company, counter);
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new BadRequestException("Authenticated accountant is required");
        jdbc.update("""
            update stock_issue_account_reviews set status='RESOLVED',stock_account_code=?,counter_account_code=?,
              reviewed_by=?,reviewed_at=current_timestamp,review_note=? where id=? and tenant_id=? and company_id=?
            """, stock, counter, principal.getName(), note.trim(), id, tenant, company);
        audit.logInCurrentTransaction("StockIssueAccountReview", id, "RESOLVE", "Issue accounting reviewed",
            null, Map.of("stockAccountCode", stock, "counterAccountCode", counter, "note", note.trim()));
    }

    private void requireAccount(String tenant, UUID company, String code) {
        Integer matches = jdbc.queryForObject("""
            select count(*) from chart_of_accounts where tenant_id=? and company_id=? and account_code=?
              and status='ACTIVE' and not is_group and not is_delete
            """, Integer.class, tenant, company, code);
        if (matches == null || matches != 1) throw new BadRequestException("Select one active posting account: " + code);
    }

    private static String code(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 40)
            throw new BadRequestException("Both accounting codes are required");
        return value.trim();
    }

    private static void scope() {
        if (TenantContext.currentTenant() == null || CompanyContext.DEFAULT_COMPANY_ID.equals(CompanyContext.currentCompany()))
            throw new BadRequestException("Explicit company and tenant are required");
    }
}
