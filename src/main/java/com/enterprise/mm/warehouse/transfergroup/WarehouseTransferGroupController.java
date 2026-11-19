package com.enterprise.mm.warehouse.transfergroup;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouse/transfer-groups")
public class WarehouseTransferGroupController {

    private final WarehouseTransferGroupService service;

    public WarehouseTransferGroupController(WarehouseTransferGroupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER')")
    @Operation(summary = "Search readable transfer groups for a selector",
        description = "Current tenant/company only. Warehouse actors see source-owned groups; ADMIN/MM_MANAGER "
            + "see all groups in the current company. Includes completed groups. "
            + "Sorted by createdAt DESC, id DESC. Names follow Accept-Language; search covers all stored translations. "
            + "Uses existing API error codes (BadRequestException, MethodArgumentTypeMismatchException, "
            + "AUTHENTICATION_REQUIRED, AccessDeniedException, NotFoundException).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated id/code/name options"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid UUID, search, page or size"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Read role required"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Source warehouse unavailable or outside assigned scope")
    })
    public ResponseEntity<ApiResponse<PageResponse<TransferGroupOptionResponse>>> options(
        @Parameter(description = "Case-insensitive code/name substring, trimmed, at most 200 characters; %, _ and ! are literal")
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID sourceWarehouseId,
        @Parameter(schema = @Schema(minimum = "0"))
        @RequestParam(defaultValue = "0") int page,
        @Parameter(schema = @Schema(minimum = "1", maximum = "100"))
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
            service.options(search, sourceWarehouseId, page, size)
        )));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR')")
    public ResponseEntity<ApiResponse<TransferGroupResponse>> create(
        @Valid @RequestBody TransferGroupCreateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(service.createGroup(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER')")
    public ResponseEntity<ApiResponse<TransferGroupResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getGroup(id)));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER')")
    public ResponseEntity<ApiResponse<TransferGroupAggregateProgress>> getProgress(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getProgress(id)));
    }

    @PostMapping("/{id}/cancel-remainder")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER')")
    public ResponseEntity<ApiResponse<TransferGroupResponse>> cancelRemainder(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "Cancel unexecuted remainder") String reason,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        return ResponseEntity.ok(ApiResponse.ok(service.cancelGroupRemainder(id, reason, key)));
    }
}
