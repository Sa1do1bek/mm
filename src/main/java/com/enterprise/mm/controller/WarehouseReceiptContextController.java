package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.*;
import com.enterprise.mm.service.WarehouseReceiptAiAdviceService;
import com.enterprise.mm.service.WarehouseReceiptContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workspaces/warehouse-execution/manual/contracts/{contractId}")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class WarehouseReceiptContextController {
    private final WarehouseReceiptContextService contexts;
    private final WarehouseReceiptAiAdviceService ai;

    @GetMapping("/receipt-context-v2")
    public ApiResponse<Context> context(@PathVariable UUID contractId, @RequestParam UUID warehouseId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(contexts.get(warehouseId, contractId, page, size));
    }

    @PostMapping("/receipt-ai-advice")
    public ApiResponse<AiAdvice> advice(@PathVariable UUID contractId, @Valid @RequestBody AiRequest request) {
        return ApiResponse.ok(ai.advise(contractId, request));
    }
}
