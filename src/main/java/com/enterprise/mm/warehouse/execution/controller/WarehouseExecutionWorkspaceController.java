package com.enterprise.mm.warehouse.execution.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.DocumentType;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.IssueRequestContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.Overview;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueFilter;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.QueueResponse;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.ReservationContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.ReturnContext;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.SelectorOption;
import com.enterprise.mm.warehouse.execution.payload.WarehouseExecutionDtos.TransferContext;
import com.enterprise.mm.warehouse.execution.service.WarehouseExecutionWorkspaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')")
@RequiredArgsConstructor
public class WarehouseExecutionWorkspaceController {
    private final WarehouseExecutionWorkspaceService service;

    @GetMapping("/overview")
    public ApiResponse<Overview> overview(@RequestParam(required = false) UUID warehouseId) {
        return ApiResponse.ok(service.overview(warehouseId));
    }

    @GetMapping("/queues/{type}")
    public ApiResponse<QueueResponse> queue(
        @PathVariable String type,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(service.queue(
            documentType(type),
            new QueueFilter(search, status, warehouseId, action),
            PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending())
        ));
    }

    @GetMapping("/issue-requests/{id}")
    public ApiResponse<IssueRequestContext> issueRequest(@PathVariable UUID id) {
        return ApiResponse.ok(service.issueRequestContext(id));
    }

    @GetMapping("/reservations/{id}")
    public ApiResponse<ReservationContext> reservation(@PathVariable UUID id) {
        return ApiResponse.ok(service.reservationContext(id));
    }

    @GetMapping("/transfers/{id}")
    public ApiResponse<TransferContext> transfer(@PathVariable UUID id) {
        return ApiResponse.ok(service.transferContext(id));
    }

    @GetMapping("/returns/{id}")
    public ApiResponse<ReturnContext> materialReturn(@PathVariable UUID id) {
        return ApiResponse.ok(service.returnContext(id));
    }

    @GetMapping("/selectors/users")
    public ApiResponse<PageResponse<SelectorOption>> users(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("fullName").ascending());
        return ApiResponse.ok(PageResponse.from(service.userSelector(search, pageable)));
    }

    @GetMapping("/selectors/cost-objects")
    public ApiResponse<PageResponse<SelectorOption>> costObjects(
        @RequestParam String type,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("name").ascending());
        return ApiResponse.ok(PageResponse.from(service.costObjectSelector(type, search, pageable)));
    }

    private DocumentType documentType(String value) {
        try {
            return DocumentType.fromPath(value);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(exception.getMessage());
        }
    }
}
