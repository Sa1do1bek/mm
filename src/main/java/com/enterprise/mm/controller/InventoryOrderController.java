package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.InventoryOrderService;
import com.enterprise.mm.service.InventoryOrderService.AllocationRequest;
import com.enterprise.mm.service.InventoryOrderService.AllocationResponse;
import com.enterprise.mm.service.InventoryOrderService.CreateOrderRequest;
import com.enterprise.mm.service.InventoryOrderService.OrderResponse;
import com.enterprise.mm.service.InventoryOrderService.OrderSummary;
import com.enterprise.mm.service.InventoryOrderService.TaskReviewRequest;
import com.enterprise.mm.service.InventoryOrderService.AssignedTask;
import com.enterprise.mm.service.InventoryOrderService.TaskDetail;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/orders")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class InventoryOrderController {
    private final InventoryOrderService orders;
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> create(@RequestBody CreateOrderRequest request) { return ApiResponse.created(orders.create(request)); }
    @GetMapping public ApiResponse<List<OrderSummary>> list() { return ApiResponse.ok(orders.list()); }
    @GetMapping("/history") public ApiResponse<Page<OrderSummary>> history(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String search) { return ApiResponse.ok(orders.history(page, size, search)); }
    @GetMapping("/assigned-tasks") public ApiResponse<List<AssignedTask>> assignedTasks() { return ApiResponse.ok(orders.assignedTasks()); }
    @GetMapping("/{id}") public ApiResponse<OrderResponse> get(@PathVariable UUID id) { return ApiResponse.ok(orders.get(id)); }
    @GetMapping("/{id}/pages/{section}")
    public ApiResponse<InventoryOrderService.OrderPage> page(@PathVariable UUID id, @PathVariable String section,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String search) {
        return ApiResponse.ok(orders.page(id, section, offset, limit, search));
    }
    @GetMapping("/{id}/commission-matches")
    public ApiResponse<List<com.enterprise.mm.service.InventoryOrderCommissionMatchService.Recommendation>> commissionMatches(@PathVariable UUID id) {
        return ApiResponse.ok(orders.commissionMatches(id));
    }
    @GetMapping("/{id}/object-matches")
    public ApiResponse<List<com.enterprise.mm.service.InventoryOrderObjectMatchService.BandMatch>> objectMatches(@PathVariable UUID id) {
        return ApiResponse.ok(orders.objectMatches(id));
    }
    @PostMapping("/{id}/allocations") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AllocationResponse> allocate(@PathVariable UUID id, @RequestBody AllocationRequest request) {
        return ApiResponse.created(orders.createAllocation(id, request));
    }
    @GetMapping("/{id}/allocations/{taskId}")
    public ApiResponse<TaskDetail> getTask(@PathVariable UUID id, @PathVariable UUID taskId) {
        return ApiResponse.ok(orders.getTask(id, taskId));
    }
    @PutMapping("/{id}/allocations/{taskId}")
    public ApiResponse<AllocationResponse> review(@PathVariable UUID id, @PathVariable UUID taskId, @RequestBody TaskReviewRequest request) {
        return ApiResponse.ok(orders.reviewTask(id, taskId, request));
    }
    @PostMapping("/{id}/allocations/{taskId}/submit")
    public ApiResponse<AllocationResponse> submit(@PathVariable UUID id, @PathVariable UUID taskId) { return ApiResponse.ok(orders.submitTask(id, taskId)); }
    @PostMapping("/{id}/allocations/{taskId}/approve")
    public ApiResponse<AllocationResponse> approve(@PathVariable UUID id, @PathVariable UUID taskId) { return ApiResponse.ok(orders.approveTask(id, taskId)); }
    @PostMapping("/{id}/allocations/{taskId}/accept")
    public ApiResponse<AllocationResponse> accept(@PathVariable UUID id, @PathVariable UUID taskId) { return ApiResponse.ok(orders.acceptTask(id, taskId)); }
}
