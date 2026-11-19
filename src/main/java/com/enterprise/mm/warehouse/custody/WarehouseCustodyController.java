package com.enterprise.mm.warehouse.custody;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.custody.CustodyCommands.ActivateCustodyCommand;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyActivationResult;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyDispatchCommand;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyDispatchResult;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyOwnershipTransferCommand;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyOwnershipTransferResult;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyReceiptCommand;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyReceiptResult;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyStatementQuery;
import com.enterprise.mm.warehouse.custody.CustodyCommands.CustodyStatementReport;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Third-Party Custody Stock / Account 002 (Otvetstvennoye xraneniye / Tolling / Consignment).
 */
@RestController
@RequestMapping("/api/warehouse/custody")
public class WarehouseCustodyController {

    private final CustodyOperationService custodyService;

    public WarehouseCustodyController(CustodyOperationService custodyService) {
        this.custodyService = custodyService;
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<CustodyReceiptResult>> receiveCustodyStock(
        @Valid @RequestBody CustodyReceiptCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(custodyService.receiveCustodyStock(command)));
    }

    @PostMapping("/dispatches")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<CustodyDispatchResult>> dispatchCustodyStock(
        @Valid @RequestBody CustodyDispatchCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(custodyService.dispatchCustodyStock(command)));
    }

    @PostMapping("/ownership-transfers")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<CustodyOwnershipTransferResult>> transferOwnership(
        @Valid @RequestBody CustodyOwnershipTransferCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(custodyService.transferOwnershipToCompany(command)));
    }

    @PostMapping("/activations")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<CustodyActivationResult>> activateCustody(
        @Valid @RequestBody ActivateCustodyCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(custodyService.activateHistoricalCustody(command)));
    }

    @GetMapping("/statement")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<CustodyStatementReport>> getStatement(
        @RequestParam UUID ownerId,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        CustodyStatementQuery query = new CustodyStatementQuery(ownerId, warehouseId, materialId, fromDate, toDate);
        return ResponseEntity.ok(ApiResponse.ok(custodyService.generateCustodyStatement(query)));
    }
}
