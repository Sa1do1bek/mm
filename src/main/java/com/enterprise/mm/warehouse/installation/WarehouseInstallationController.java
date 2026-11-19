package com.enterprise.mm.warehouse.installation;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.installation.InstallationCommands.AcceptInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizationResult;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizeEquipmentCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.DispatchInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationProjectBalance;
import com.enterprise.mm.warehouse.installation.InstallationCommands.ReturnInstallationCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Installation Equipment / Account 07 and 0820 (Oborudovaniye k ustanovke / Capital projects).
 */
@RestController
@RequestMapping("/api/warehouse/installation")
public class WarehouseInstallationController {

    private final InstallationEquipmentService installationService;

    public WarehouseInstallationController(InstallationEquipmentService installationService) {
        this.installationService = installationService;
    }

    @PostMapping("/dispatches")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<WarehouseInstallationPosition>> dispatchEquipment(
        @Valid @RequestBody DispatchInstallationCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(installationService.dispatchEquipmentToProject(command)));
    }

    @PostMapping("/acceptances")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','PROJECT_MANAGER')")
    public ResponseEntity<ApiResponse<WarehouseInstallationPosition>> acceptEquipment(
        @Valid @RequestBody AcceptInstallationCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(installationService.acceptEquipmentAtProject(command)));
    }

    @PostMapping("/returns")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<WarehouseInstallationPosition>> returnEquipment(
        @Valid @RequestBody ReturnInstallationCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(installationService.returnUnusedEquipmentToWarehouse(command)));
    }

    @PostMapping("/capitalizations")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<CapitalizationResult>> capitalizeEquipment(
        @Valid @RequestBody CapitalizeEquipmentCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(installationService.capitalizeInstallationEquipment(command)));
    }

    @GetMapping("/projects/{projectId}/balance")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT','PROJECT_MANAGER')")
    public ResponseEntity<ApiResponse<InstallationProjectBalance>> getProjectBalance(
        @PathVariable UUID projectId,
        @RequestParam(required = false) UUID materialId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(installationService.getProjectInstallationBalance(projectId, materialId)));
    }
}
