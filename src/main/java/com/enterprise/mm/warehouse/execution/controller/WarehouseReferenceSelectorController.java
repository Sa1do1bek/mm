package com.enterprise.mm.warehouse.execution.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.WarehouseReferenceOption;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.SelectorOption;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Limited lookup identities; never returns supplier financial or commission member details. */
@RestController
@RequestMapping("/api/workspaces/warehouse-execution/selectors")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER')")
@RequiredArgsConstructor
public class WarehouseReferenceSelectorController {
    private static final UUID NO_ID = new UUID(0, 0);
    private final SupplierRepository suppliers;
    private final com.enterprise.mm.repository.ContractRepository contracts;
    private final CommissionRepository commissions;
    private final WarehouseRepository warehouses;
    private final RoleDataScopeService scopes;
    private final InventoryAccessService inventoryAccess;

    @GetMapping("/contracts")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
    public ApiResponse<PageResponse<com.enterprise.mm.payload.ContractReferenceResponse>> contracts(
        @RequestParam(required = false) String search, @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID purchaseOrderId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        var scope = scopes.currentScope();
        if ((scope.warehouseScoped() && scope.warehouseIds().isEmpty()) || scopes.isDepartmentIssueScoped()) {
            if (!scopes.isDepartmentIssueScoped() || scope.departmentId() == null) {
                throw new AccessDeniedException("Warehouse or department assignment required");
            }
        }
        return ApiResponse.ok(PageResponse.from(contracts.referenceOptions(TenantContext.currentTenant(),
            CompanyContext.currentCompany(), supplierId, purchaseOrderId, search == null ? "" : search.trim(),
            page(page,size))));
    }

    @GetMapping("/suppliers")
    public ApiResponse<PageResponse<SelectorOption>> suppliers(@RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        requireAssignment();
        return ApiResponse.ok(PageResponse.from(supplierOptions(null, search, page(page, size))));
    }

    @GetMapping("/suppliers/{id}")
    public ApiResponse<SelectorOption> supplier(@PathVariable UUID id) {
        requireAssignment();
        return ApiResponse.ok(supplierOptions(id, null, page(0, 1)).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Supplier not found")));
    }

    @GetMapping("/inventory-commissions")
    public ApiResponse<PageResponse<SelectorOption>> commissions(@RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        requireAssignment();
        return ApiResponse.ok(PageResponse.from(commissionOptions(null, search, page(page, size))));
    }

    @GetMapping("/inventory-commissions/{id}")
    public ApiResponse<SelectorOption> commission(@PathVariable UUID id) {
        requireAssignment();
        return ApiResponse.ok(commissionOptions(id, null, page(0, 1)).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Inventory commission not found")));
    }

    private void requireAssignment() {
        var scope = scopes.currentScope();
        if (scope.warehouseScoped() && (scope.warehouseIds().isEmpty() || scope.warehouseIds().stream().anyMatch(id -> warehouses
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(),
                CompanyContext.currentCompany(), id).isEmpty()))) {
            throw new AccessDeniedException("Warehouse assignment required");
        }
    }

    private Page<SelectorOption> supplierOptions(UUID id, String search, Pageable pageable) {
        return suppliers.searchWarehouseOptions(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            id == null, id == null ? NO_ID : id, normalized(search), pageable).map(this::option);
    }

    private Page<SelectorOption> commissionOptions(UUID id, String search, Pageable pageable) {
        return commissions.searchMemberInventoryOptions(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            inventoryAccess.currentUserId(), id == null, id == null ? NO_ID : id, normalized(search), LocalDate.now(), pageable).map(this::option);
    }

    private SelectorOption option(WarehouseReferenceOption record) {
        return new SelectorOption(record.getId(), record.getCode(), record.getName(), record.getName(), record.getStatus());
    }

    private Pageable page(int page, int size) {
        return PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending().and(Sort.by("id")));
    }

    private String normalized(String search) {
        return search == null ? "" : search.trim();
    }
}
