package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ConfirmProcessorConsumptionCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.DispatchToProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ExecuteTransformationCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.ReturnFromProcessorCommand;
import com.enterprise.mm.warehouse.production.ProcessingCommands.TransformationResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Industrial Transformations (Cutting/Raskroy, Assembly, Disassembly, Tolling).
 */
@RestController
@RequestMapping("/api/warehouse/processing")
public class WarehouseProcessingController {

    private final ProcessingTransformationService processingService;

    public WarehouseProcessingController(ProcessingTransformationService processingService) {
        this.processingService = processingService;
    }

    @PostMapping("/transformations")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','PRODUCTION_OPERATOR')")
    public ResponseEntity<ApiResponse<TransformationResult>> executeTransformation(
        @Valid @RequestBody ExecuteTransformationCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.executeTransformation(command)));
    }

    @PostMapping("/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<TransformationResult>> dispatchToProcessor(
        @Valid @RequestBody DispatchToProcessorCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.dispatchToProcessor(command)));
    }

    @PostMapping("/consumption")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','ACCOUNTANT')")
    public ResponseEntity<ApiResponse<TransformationResult>> confirmConsumption(
        @Valid @RequestBody ConfirmProcessorConsumptionCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.confirmProcessorConsumption(command)));
    }

    @PostMapping("/returns")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<TransformationResult>> returnFromProcessor(
        @Valid @RequestBody ReturnFromProcessorCommand command
    ) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.returnFromProcessor(command)));
    }
}
