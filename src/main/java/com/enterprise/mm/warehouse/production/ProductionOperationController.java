package com.enterprise.mm.warehouse.production;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ProductionOperationService;
import com.enterprise.mm.service.ProductionHistoryService;
import com.enterprise.mm.warehouse.production.ProductionOperationDtos.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouse/production-operations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
public class ProductionOperationController {
    private final ProductionOperationService operations;
    private final ProductionHistoryService history;
    public record Update(@NotNull @PositiveOrZero Long version, @NotNull Draft draft) {}

    @GetMapping
    public ApiResponse<PageResponse<Operation>> list(@RequestParam UUID warehouseId,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return ApiResponse.ok(operations.list(warehouseId, page, size));
    }
    @GetMapping("/{id}")
    public ApiResponse<Operation> get(@PathVariable UUID id) { return ApiResponse.ok(operations.get(id)); }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Operation> create(@RequestBody Draft draft, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(operations.create(draft, key));
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Operation> update(@PathVariable UUID id, @Valid @RequestBody Update command) {
        return ApiResponse.ok(operations.update(id, command.version(), command.draft()));
    }
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Operation> post(@PathVariable UUID id, @Valid @RequestBody VersionCommand command,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(operations.post(id, command.version(), key));
    }
    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Operation> reverse(@PathVariable UUID id, @Valid @RequestBody ReversalCommand command,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(operations.reverse(id, command, key));
    }
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<Operation> cancel(@PathVariable UUID id, @Valid @RequestBody VersionCommand command) {
        return ApiResponse.ok(operations.cancel(id, command.version()));
    }
    @GetMapping("/history/materials/{materialId}")
    public ApiResponse<History> history(@PathVariable UUID materialId, @RequestParam UUID warehouseId,
        @RequestParam(required=false) UUID stockBatchId) {
        return ApiResponse.ok(history.material(materialId, warehouseId, stockBatchId));
    }
}
