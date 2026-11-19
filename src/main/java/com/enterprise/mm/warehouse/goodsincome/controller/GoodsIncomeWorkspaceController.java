package com.enterprise.mm.warehouse.goodsincome.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueFilter;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeQueueResponse;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/goods-income")
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST','SUPPLIER_MANAGER','AUDITOR','VIEWER')")
@RequiredArgsConstructor
public class GoodsIncomeWorkspaceController {
    private final GoodsIncomeWorkspaceService service;

    @GetMapping("/queues/receiving")
    public ApiResponse<GoodsIncomeQueueResponse> receivingQueue(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(service.queue(
            new GoodsIncomeQueueFilter(search, status, supplierId, contractId, overdue),
            PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending())
        ));
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<GoodsIncomeContext> purchaseOrderContext(
        @PathVariable UUID id,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        java.time.LocalDate asOfDate
    ) {
        return ApiResponse.ok(service.context(id, asOfDate));
    }
}
