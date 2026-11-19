package com.enterprise.mm.warehouse.holding;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.holding.HoldingCommands.AcknowledgeHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ConsumeHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.DispatchHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalActivationResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HistoricalHoldingActivationCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingConsumptionResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingHandoverResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingReturnResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementQuery;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingStatementReport;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingTransferResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.HoldingWriteOffResult;
import com.enterprise.mm.warehouse.holding.HoldingCommands.RejectHandoverCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.ReturnHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.TransferHoldingCommand;
import com.enterprise.mm.warehouse.holding.HoldingCommands.WriteOffHoldingCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * REST API for Materials in Operational Use / Account 014 (Spetsodejda, tools, equipment).
 */
@RestController
@RequestMapping("/api/warehouse/in-use")
public class WarehouseInUseController {

    private final WarehouseHoldingService holdingService;

    public WarehouseInUseController(WarehouseHoldingService holdingService) {
        this.holdingService = holdingService;
    }

    @PostMapping("/handovers/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<HoldingHandoverResult>> dispatchHandover(
        @Valid @RequestBody DispatchHandoverCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.dispatchHandover(command)));
    }

    @PostMapping("/handovers/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','DEPARTMENT_MANAGER','EMPLOYEE')")
    public ResponseEntity<ApiResponse<HoldingHandoverResult>> acknowledgeHandover(
        @Valid @RequestBody AcknowledgeHandoverCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.acknowledgeHandover(command)));
    }

    @PostMapping("/handovers/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','DEPARTMENT_MANAGER','EMPLOYEE')")
    public ResponseEntity<ApiResponse<HoldingHandoverResult>> rejectHandover(
        @Valid @RequestBody RejectHandoverCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.rejectHandover(command)));
    }

    @PostMapping("/consumption")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<HoldingConsumptionResult>> consumeHolding(
        @Valid @RequestBody ConsumeHoldingCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.consumeHolding(command)));
    }

    @PostMapping("/write-offs")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<HoldingWriteOffResult>> writeOffHolding(
        @Valid @RequestBody WriteOffHoldingCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.writeOffHolding(command)));
    }

    @PostMapping("/returns")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<HoldingReturnResult>> returnHolding(
        @Valid @RequestBody ReturnHoldingCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.returnHoldingToWarehouse(command)));
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','DEPARTMENT_MANAGER')")
    public ResponseEntity<ApiResponse<HoldingTransferResult>> transferHolding(
        @Valid @RequestBody TransferHoldingCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.transferHolding(command)));
    }

    @PostMapping("/activations")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<HistoricalActivationResult>> activateHistoricalHolding(
        @Valid @RequestBody HistoricalHoldingActivationCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(holdingService.activateHistoricalHolding(command)));
    }

    @GetMapping("/statement")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<HoldingStatementReport>> getStatement(
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) UUID employeeId,
        @RequestParam(required = false) String accountCode
    ) {
        HoldingStatementQuery query = new HoldingStatementQuery(materialId, departmentId, employeeId, accountCode);
        return ResponseEntity.ok(ApiResponse.ok(holdingService.generateHoldingStatement(query)));
    }
}
