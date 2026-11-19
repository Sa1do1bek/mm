package com.enterprise.mm.service;

import static com.enterprise.mm.entity.DamagedStockCase.Status.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DamagedStockCase;
import com.enterprise.mm.entity.DamagedStockCase.Status;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.DamagedStockDtos.*;
import com.enterprise.mm.repository.DamagedStockCaseRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DamagedStockService {
    private static final String TYPE = DamagedStockCase.TYPE;
    private static final UUID NO_WAREHOUSE = new UUID(0, 0);
    private static final Set<Status> EDITABLE = Set.of(QUARANTINED, RETURNED);
    private static final Set<Status> RELEASABLE = Set.of(QUARANTINED, RETURNED, REJECTED);
    private final DamagedStockCaseRepository cases;
    private final StockBalanceRepository balances;
    private final RoleDataScopeService scopes;
    private final FinancialAuthorizationService roles;
    private final InventoryFreezeGuardService freeze;
    private final StockBalanceMutationService mutations;
    private final MaterialCatalogGuard materials;
    private final IdempotencyService idempotency;
    private final ApprovalEngineService approvals;
    private final StockMovementService movements;
    private final AuditService audit;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<CaseView> list(UUID warehouseId, Status status, String search, Pageable pageable) {
        requireReader();
        var scope = scopes.currentScope();
        if (warehouseId != null) scopes.assertWarehouseAccess(warehouseId);
        Set<UUID> warehouses = warehouseId == null ? scope.warehouseIds() : Set.of(warehouseId);
        boolean allWarehouses = warehouseId == null && !scope.warehouseScoped();
        if (warehouses.isEmpty() && !allWarehouses) return Page.empty(pageable);
        String pattern = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase(Locale.ROOT) + "%";
        return cases.search(TenantContext.currentTenant(), CompanyContext.currentCompany(), allWarehouses,
            warehouses.isEmpty() ? Set.of(NO_WAREHOUSE) : warehouses, status == null ? null : status.name(),
            pattern, pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public CaseView get(UUID id) {
        return view(readCase(id));
    }

    public CaseView report(Report request, String key) {
        requireReporter();
        StockBalance balance = findBalance(request.balanceId());
        guardFreeze(balance);
        StockBalance locked = lockBalance(balance.getId());
        return idempotency.executeRequired("DAMAGED_STOCK_REPORT", locked.getId(), key, request, CaseView.class,
            () -> quarantine(request, locked));
    }

    private CaseView quarantine(Report request, StockBalance balance) {
        var material = materials.requireActiveTmbMaterial(balance.getMaterialId(), "materialId");
        if (!"OWNED".equals(balance.getStockCategory()) || balance.getOwnerPartyId() != null)
            throw new BusinessException("Use company-owned stock for a damage report", "damagedStock.ownedOnly");
        if (material.getSerialTrackingPolicy() == SerialTrackingPolicy.REQUIRED
            || material.getMaterialSubtype() == MaterialSubtype.RETURNABLE_PACKAGING)
            throw new BusinessException("This material requires a specialized stock workflow", "damagedStock.managedMaterial");
        StockUomPolicy.quantity(material, request.quantity(), false);
        mutations.moveAvailableToQuarantine(balance, request.quantity());

        DamagedStockCase stockCase = new DamagedStockCase();
        stockCase.setCode("DMG-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        stockCase.setName(material.getName());
        stockCase.setStatus(QUARANTINED.name());
        stockCase.setBalanceId(balance.getId());
        stockCase.setBalance(balance);
        stockCase.setWarehouseId(balance.getWarehouseId());
        stockCase.setQuantity(request.quantity());
        stockCase.setCondition(request.condition());
        stockCase.setDescription(request.reason().strip());
        cases.saveAndFlush(stockCase);
        audit.logInCurrentTransaction(TYPE, stockCase.getId(), "QUARANTINED", stockCase.getDescription());
        return view(stockCase);
    }

    public CaseView submit(UUID id) {
        approvals.start(TYPE, id);
        return get(id);
    }

    public CaseView release(UUID id, Release request) {
        requireManager();
        DamagedStockCase stockCase = lockCase(id);
        if (status(stockCase) == RELEASED) return view(stockCase);
        requireStatus(stockCase, RELEASABLE);
        StockBalance balance = findBalance(stockCase.getBalanceId());
        guardFreeze(balance);
        balance = lockBalance(balance.getId());
        BigDecimal quantity = stockCase.getQuantity();
        mutations.applyExactDelta(balance, null, quantity, null, quantity.negate(), null);
        stockCase.setStatus(RELEASED.name());
        stockCase.setResolutionNote(request.reason().strip());
        audit.logInCurrentTransaction(TYPE, id, "RELEASED", stockCase.getResolutionNote());
        return view(stockCase);
    }

    public CaseView post(UUID id, Post request) {
        roles.requireFinance("post a stock write-off");
        DamagedStockCase stockCase = lockCase(id);
        if (status(stockCase) == WRITTEN_OFF) return view(stockCase);
        requireStatus(stockCase, Set.of(APPROVED));
        StockBalance balance = findBalance(stockCase.getBalanceId());
        UUID movementId = movements.postDamagedStockWriteOff(stockCase, balance, request.postingDate());
        stockCase.setMovementId(movementId);
        stockCase.setStatus(WRITTEN_OFF.name());
        audit.logInCurrentTransaction(TYPE, id, "WRITTEN_OFF", movementId.toString());
        return view(stockCase);
    }

    @Transactional(readOnly = true)
    public DamagedStockCase readCase(UUID id) {
        requireReader();
        return scoped(cases.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Damage report not found")));
    }

    public DamagedStockCase lockCase(UUID id) {
        return scoped(cases.lock(id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Damage report not found")));
    }

    public void requireEditableEvidence(UUID id) {
        requireReporter();
        requireStatus(lockCase(id), EDITABLE);
    }

    public void requireManager() {
        if (!canManage()) throw new AccessDeniedException("Warehouse review authority is required");
    }

    public void requireSubmittable(DamagedStockCase stockCase) {
        requireManager();
        requireStatus(stockCase, EDITABLE);
    }

    public static void requireStatus(DamagedStockCase stockCase, Set<Status> allowed) {
        if (!allowed.contains(status(stockCase)))
            throw new BusinessException("This action is unavailable at the current report status", "damagedStock.invalidStatus");
    }

    public static Status status(DamagedStockCase stockCase) {
        return Status.valueOf(stockCase.getStatus());
    }

    private DamagedStockCase scoped(DamagedStockCase stockCase) {
        scopes.assertWarehouseAccess(stockCase.getWarehouseId());
        return stockCase;
    }

    private StockBalance findBalance(UUID id) {
        return scopedBalance(balances.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException("Stock position not found")));
    }

    private StockBalance lockBalance(UUID id) {
        StockBalance balance = findBalance(id);
        entityManager.refresh(balance, LockModeType.PESSIMISTIC_WRITE);
        if (balance.isDeleted()) throw new NotFoundException("Stock position not found");
        return balance;
    }

    private StockBalance scopedBalance(StockBalance balance) {
        if (!TenantContext.currentTenant().equals(balance.getTenantId())
            || !CompanyContext.currentCompany().equals(balance.getCompanyId()))
            throw new NotFoundException("Stock position not found");
        scopes.assertWarehouseAccess(balance.getWarehouseId());
        return balance;
    }

    private void guardFreeze(StockBalance balance) {
        freeze.ensureStockKeyAllowed(balance.getCompanyId(), balance.getWarehouseId(),
            balance.getStorageLocationId(), balance.getStorageBinId(), balance.getMaterialId());
    }

    private void requireReader() {
        if (!canReport() && !roles.hasAnyRole("FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER"))
            throw new AccessDeniedException("Warehouse damage report access is required");
    }

    private void requireReporter() {
        if (!canReport()) throw new AccessDeniedException("Warehouse reporting authority is required");
    }

    private boolean canManage() {
        return roles.hasAnyRole("ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "DEPARTMENT_HEAD");
    }

    private boolean canReport() {
        return canManage() || roles.hasAnyRole("WAREHOUSE_KEEPER", "WAREHOUSE_OPERATOR");
    }

    private CaseView view(DamagedStockCase stockCase) {
        StockBalance balance = stockCase.getBalance();
        var material = balance.getMaterial();
        var unit = material.getUnitOfMeasure();
        Status status = status(stockCase);
        List<String> actions = new ArrayList<>();
        if (canReport() && EDITABLE.contains(status)) actions.add("EVIDENCE");
        if (canManage() && EDITABLE.contains(status)) actions.add("SUBMIT");
        if (canManage() && RELEASABLE.contains(status)) actions.add("RELEASE");
        if (status == APPROVED && roles.hasAnyRole("ADMIN", "FINANCE_CONTROLLER", "ACCOUNTANT")) actions.add("POST");
        return new CaseView(stockCase.getId(), stockCase.getCode(), status, stockCase.getCondition(), stockCase.getQuantity(),
            unit == null ? null : unit.getCode(), stockCase.getDescription(), balance.getId(), material.getCode(), material.getName(),
            balance.getWarehouseId(), balance.getWarehouse().getName(),
            balance.getStorageLocation() == null ? null : balance.getStorageLocation().getName(),
            balance.getStorageBin() == null ? null : balance.getStorageBin().getName(),
            balance.getStockBatch() == null ? null : balance.getStockBatch().getBatchNumber(),
            stockCase.getCreatedBy(), stockCase.getCreatedAt(), stockCase.getApprovalInstanceId(), stockCase.getMovementId(),
            stockCase.getResolutionNote(), List.copyOf(actions));
    }
}
