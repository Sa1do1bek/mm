package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.enums.PickingMethod;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.security.pilot.PilotCapability;
import com.enterprise.mm.security.pilot.PilotCapabilityPolicy;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import com.enterprise.mm.warehouse.reports.WarehouseReportService;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Document-free quantity tracking in an explicitly selected or single assigned warehouse. */
@Service
@RequiredArgsConstructor
public class FixedAssetStockFactService {
    private final RoleDataScopeService scope;
    private final WarehouseRepository warehouses;
    private final MaterialRepository materials;
    private final MaterialCatalogGuard catalog;
    private final WarehouseStockFactService facts;
    private final StockPickingAllocationService picking;
    private final PilotCapabilityPolicy capabilities;
    private final WarehouseReportService reports;

    public record WarehouseContext(UUID warehouseId, String code, String name) {}
    public record MaterialOption(UUID id, String code, String name, UUID unitOfMeasureId, String unitCode) {}

    @Transactional(readOnly = true)
    public WarehouseContext context() {
        capabilities.require(PilotCapability.FIXED_ASSET_EXECUTION);
        UUID id = scope.requireAssignedWarehouseId();
        scope.assertWarehouseAccess(id);
        var warehouse = warehouses.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id)
            .filter(w -> CompanyContext.currentCompany().equals(w.getCompanyId()) && "ACTIVE".equals(w.getStatus()))
            .orElseThrow(() -> new NotFoundException("Active assigned warehouse not found"));
        return new WarehouseContext(id, warehouse.getCode(), warehouse.getName());
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialOption> materials(String search, String code, String name, Pageable pageable) {
        context();
        return PageResponse.from(materials.findFixedAssetStockMaterials(TenantContext.currentTenant(),
            CompanyContext.currentCompany(), clean(search), clean(code), clean(name), pageable)
            .map(m -> new MaterialOption(m.getId(), m.getCode(), m.getName(), m.getUnitOfMeasureId(),
                m.getUnitOfMeasure() == null ? null : m.getUnitOfMeasure().getCode())));
    }

    @Transactional
    public ManualWarehouseDocumentResponse record(FixedAssetStockFactRequest request,
        List<MultipartFile> files, String key, boolean receipt) {
        var warehouse = context();
        return facts.recordFixedAssets(request.inWarehouse(warehouse.warehouseId(), receipt), files, key, receipt);
    }

    @Transactional(readOnly = true)
    public WarehouseStockFactPickingDtos.Options issueOptions(UUID materialId, PickingMethod method, int page, int size) {
        var warehouse = context();
        requireMaterial(materialId);
        return picking.fixedAssetIssueOptions(warehouse.warehouseId(), materialId, method, page, size);
    }

    @Transactional(readOnly = true)
    public WarehouseStockFactPickingDtos.Preview previewIssue(FixedAssetStockFactRequest request) {
        var warehouse = context();
        for (var line : request.lines()) requireMaterial(line.materialId());
        return new WarehouseStockFactPickingDtos.Preview(warehouse.warehouseId(),
            picking.allocateFixedAssetIssue(warehouse.warehouseId(), request.inWarehouse(warehouse.warehouseId(), false).lines(), false));
    }

    @Transactional(readOnly = true)
    public WarehouseReport balances(LocalDate asOfDate, String search, int page, int size) {
        UUID warehouse = context().warehouseId();
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        return reports.generate(new WarehouseReport.Filter(WarehouseReport.Type.STOCK_BALANCES,
            date, date, warehouse, null, null, search, page, size, null, MaterialType.FIXED_ASSET, null, null), false);
    }

    private void requireMaterial(UUID id) {
        var material = catalog.requireActiveFixedAssetStockMaterial(id, "materialId");
        if (!CompanyContext.currentCompany().equals(material.getCompanyId()) || material.getMaterialType() != MaterialType.FIXED_ASSET)
            throw new BusinessException("Select a fixed-asset material in your company", "FIXED_ASSET_MATERIAL_REQUIRED");
    }

    private String clean(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
