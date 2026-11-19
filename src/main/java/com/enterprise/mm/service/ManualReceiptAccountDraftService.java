package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable, user-owned receipt context used to ground an AI account suggestion. */
@Service
@RequiredArgsConstructor
public class ManualReceiptAccountDraftService {
    private final JdbcTemplate jdbc;
    private final RoleDataScopeService scope;
    private final MaterialCatalogGuard catalog;
    private final WarehouseRepository warehouses;

    public record Create(UUID warehouseId, UUID materialId, LocalDate movementDate,
                         String purpose, BigDecimal quantity, BigDecimal unitCost) {}
    public record Draft(UUID id, UUID warehouseId, UUID materialId, LocalDate movementDate,
                        String purpose, BigDecimal quantity, BigDecimal unitCost, int revision, String status) {}

    @Transactional
    public Draft create(Create input) {
        if (input == null || input.warehouseId() == null || input.materialId() == null
            || input.movementDate() == null || input.movementDate().isAfter(LocalDate.now())
            || input.purpose() == null || input.purpose().isBlank() || input.purpose().length() > 500
            || input.quantity() == null || input.quantity().signum() <= 0
            || input.unitCost() == null || input.unitCost().signum() < 0)
            throw new BadRequestException("Complete the receipt context before requesting an account suggestion");
        scope.assertWarehouseAccess(input.warehouseId());
        warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), input.warehouseId())
            .filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId())
                && "ACTIVE".equals(value.getStatus()))
            .orElseThrow(() -> new NotFoundException("Active warehouse not found"));
        catalog.requireActiveTmbMaterial(input.materialId(), "materialId");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into manual_receipt_account_drafts
              (id,tenant_id,company_id,owner_id,warehouse_id,material_id,movement_date,purpose,quantity,unit_cost)
            values (?,?,?,?,?,?,?,?,?,?)
            """, id, TenantContext.currentTenant(), CompanyContext.currentCompany(), owner(),
            input.warehouseId(), input.materialId(), input.movementDate(), input.purpose().trim(),
            input.quantity(), input.unitCost());
        return require(id, false);
    }

    @Transactional(readOnly = true)
    public Draft get(UUID id) { return require(id, false); }

    public Draft lockForPosting(UUID id, UUID warehouseId, UUID materialId, LocalDate movementDate,
                                String purpose, BigDecimal quantity, BigDecimal unitCost) {
        Draft draft = require(id, true);
        if (!Objects.equals(draft.warehouseId(), warehouseId)
            || !Objects.equals(draft.materialId(), materialId)
            || !Objects.equals(draft.movementDate(), movementDate)
            || !Objects.equals(draft.purpose(), purpose == null ? null : purpose.trim())
            || quantity == null || draft.quantity().compareTo(quantity) != 0
            || unitCost == null || draft.unitCost().compareTo(unitCost) != 0)
            throw new ConflictException("Receipt details changed after the AI suggestion; request a new suggestion");
        return draft;
    }

    public void markPosted(UUID id, UUID movementId) {
        int changed = jdbc.update("""
            update manual_receipt_account_drafts set status='POSTED',posted_movement_id=?,updated_at=current_timestamp
            where id=? and tenant_id=? and company_id=? and owner_id=? and status='DRAFT'
            """, movementId, id, TenantContext.currentTenant(), CompanyContext.currentCompany(), owner());
        if (changed != 1) throw new ConflictException("Receipt draft was already used");
    }

    @Transactional
    public void recordPostedContext(Create input, UUID movementId) {
        markPosted(create(input).id(), movementId);
    }

    private Draft require(UUID id, boolean lock) {
        if (id == null) throw new BadRequestException("Receipt draft is required");
        List<Draft> rows = jdbc.query("""
            select id,warehouse_id,material_id,movement_date,purpose,quantity,unit_cost,revision,status
            from manual_receipt_account_drafts
            where id=? and tenant_id=? and company_id=? and owner_id=?
            """ + (lock ? " for update" : ""), (row, index) -> new Draft(
            row.getObject("id", UUID.class), row.getObject("warehouse_id", UUID.class),
            row.getObject("material_id", UUID.class), row.getObject("movement_date", LocalDate.class),
            row.getString("purpose"), row.getBigDecimal("quantity"), row.getBigDecimal("unit_cost"),
            row.getInt("revision"), row.getString("status")), id, TenantContext.currentTenant(),
            CompanyContext.currentCompany(), owner());
        if (rows.isEmpty()) throw new NotFoundException("Receipt draft not found");
        Draft draft = rows.getFirst();
        scope.assertWarehouseAccess(draft.warehouseId());
        if (!"DRAFT".equals(draft.status())) throw new ConflictException("Receipt draft was already posted");
        return draft;
    }

    private UUID owner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof AuthenticatedTokenState state))
            throw new BadRequestException("Authenticated user is required");
        return state.userId();
    }
}
