package com.enterprise.mm.controller;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.enums.InventoryScopeMode;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.InventoryScopeService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/scope-preview")
@RequiredArgsConstructor
public class InventoryScopePreviewController {
    private final InventoryScopeService scope;

    public record PreviewRequest(List<UUID> departmentIds, List<UUID> warehouseIds) {}
    public record PreviewResponse(List<UUID> warehouseIds) {}

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','DEPARTMENT_HEAD')")
    public ApiResponse<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        InventoryDocument document = new InventoryDocument();
        document.setCompanyId(CompanyContext.currentCompany());
        document.setScopeMode(InventoryScopeMode.MIXED);
        document.setDepartmentIds(new LinkedHashSet<>(request.departmentIds() == null ? List.of() : request.departmentIds()));
        document.setSelectedWarehouseIds(new LinkedHashSet<>(request.warehouseIds() == null ? List.of() : request.warehouseIds()));
        scope.prepareScope(document);
        return ApiResponse.ok(new PreviewResponse(scope.warehouseIds(document)));
    }
}
