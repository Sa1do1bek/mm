package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardPurchaseOrderRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardRequest;
import com.enterprise.mm.payload.ProcurementDirectAwardDtos.DirectAwardResponse;
import com.enterprise.mm.payload.ProcurementFrameworkDtos.Response;
import com.enterprise.mm.payload.ProcurementProcedureCancelRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.AuctionAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkAwardRequest;
import com.enterprise.mm.payload.ProcurementProcedureExecutionDtos.FrameworkCallOffRequest;
import com.enterprise.mm.payload.ProcurementProcedureResponse;
import com.enterprise.mm.payload.ProcurementProcedureSearchRequest;
import com.enterprise.mm.payload.PurchaseOrderResponse;
import com.enterprise.mm.service.ProcurementDirectAwardService;
import com.enterprise.mm.service.ProcurementFrameworkService;
import com.enterprise.mm.service.ProcurementProcedureExecutionService;
import com.enterprise.mm.service.ProcurementProcedureService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/procurement/procedures")
@RequiredArgsConstructor
public class ProcurementProcedureController {
    private final ProcurementProcedureService service;
    private final ProcurementDirectAwardService directAwardService;
    private final ProcurementProcedureExecutionService executionService;
    private final ProcurementFrameworkService frameworkService;

    @GetMapping
    public ApiResponse<PageResponse<ProcurementProcedureResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String procedureNumber,
        @RequestParam(required = false) UUID methodDecisionId,
        @RequestParam(required = false) UUID procurementPlanId,
        @RequestParam(required = false) UUID purchaseRequestId,
        @RequestParam(required = false) List<String> method,
        @RequestParam(required = false) UUID campaignId,
        @RequestParam(required = false) UUID rfqId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        var request = new ProcurementProcedureSearchRequest(search, status, dateFrom, dateTo, includeDeleted,
            procedureNumber, methodDecisionId, procurementPlanId, purchaseRequestId, method, campaignId, rfqId);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProcurementProcedureResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/framework")
    public ApiResponse<Response> getFramework(@PathVariable UUID id) {
        return ApiResponse.ok(frameworkService.getByProcedure(id));
    }

    @PostMapping("/{id}/ready")
    public ApiResponse<ProcurementProcedureResponse> ready(@PathVariable UUID id) {
        return ApiResponse.ok(service.ready(id));
    }

    @PostMapping("/{id}/launch")
    public ApiResponse<ProcurementProcedureResponse> launch(@PathVariable UUID id) {
        return ApiResponse.ok(service.launch(id));
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<ProcurementProcedureResponse> complete(@PathVariable UUID id) {
        return ApiResponse.ok(service.complete(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ProcurementProcedureResponse> cancel(
        @PathVariable UUID id,
        @RequestBody ProcurementProcedureCancelRequest request
    ) {
        return ApiResponse.ok(service.cancel(id, request));
    }

    @PostMapping("/{id}/direct-award")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<DirectAwardResponse> createDirectAward(
        @PathVariable UUID id,
        @Valid @RequestBody DirectAwardRequest request
    ) {
        return ApiResponse.ok(directAwardService.create(id, request));
    }

    @GetMapping("/direct-awards/{id}")
    public ApiResponse<DirectAwardResponse> getDirectAward(@PathVariable UUID id) {
        return ApiResponse.ok(directAwardService.get(id));
    }

    @PutMapping("/direct-awards/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<DirectAwardResponse> updateDirectAward(
        @PathVariable UUID id,
        @Valid @RequestBody DirectAwardRequest request
    ) {
        return ApiResponse.ok(directAwardService.update(id, request));
    }

    @PostMapping("/direct-awards/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<DirectAwardResponse> submitDirectAward(@PathVariable UUID id) {
        return ApiResponse.ok(directAwardService.submit(id));
    }

    @PostMapping("/direct-awards/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<DirectAwardResponse> approveDirectAward(@PathVariable UUID id) {
        throw new BusinessException("Direct award approval must be completed through approval task endpoints");
    }

    @PostMapping("/direct-awards/{id}/create-po")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<PurchaseOrderResponse> createDirectAwardPurchaseOrder(
        @PathVariable UUID id,
        @RequestBody(required = false) DirectAwardPurchaseOrderRequest request
    ) {
        return ApiResponse.ok(directAwardService.createPurchaseOrder(id, request));
    }

    @PostMapping("/{id}/auction/publish")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementProcedureResponse> publishAuction(@PathVariable UUID id) {
        return ApiResponse.ok(service.publishAuction(id));
    }

    @PostMapping("/{id}/auction/start")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementProcedureResponse> startAuction(@PathVariable UUID id) {
        return ApiResponse.ok(service.startAuction(id));
    }

    @PostMapping("/{id}/auction/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementProcedureResponse> closeAuction(@PathVariable UUID id) {
        return ApiResponse.ok(service.closeAuction(id));
    }

    @PostMapping("/{id}/auction/award")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<PurchaseOrderResponse> awardAuction(
        @PathVariable UUID id,
        @Valid @RequestBody AuctionAwardRequest request
    ) {
        return ApiResponse.ok(executionService.awardAuction(id, request));
    }

    @PostMapping("/{id}/framework/award")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementProcedureResponse> awardFramework(
        @PathVariable UUID id,
        @Valid @RequestBody FrameworkAwardRequest request
    ) {
        return ApiResponse.ok(executionService.awardFramework(id, request));
    }

    @PostMapping("/{id}/framework/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<Response> submitFramework(@PathVariable UUID id) {
        return ApiResponse.ok(frameworkService.submit(id));
    }

    @PostMapping("/{id}/framework/activate")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER')")
    public ApiResponse<ProcurementProcedureResponse> activateFramework(@PathVariable UUID id) {
        return ApiResponse.ok(executionService.activateFramework(id));
    }

    @PostMapping("/{id}/framework/create-po")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<PurchaseOrderResponse> createFrameworkCallOff(
        @PathVariable UUID id,
        @Valid @RequestBody FrameworkCallOffRequest request
    ) {
        return ApiResponse.ok(executionService.createFrameworkCallOff(id, request));
    }
}
