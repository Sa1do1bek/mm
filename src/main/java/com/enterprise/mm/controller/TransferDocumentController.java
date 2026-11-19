package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.service.approval.ApprovalOperationSubmissionService;
import com.enterprise.mm.service.approval.TransferApproveOperationHandler;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferDocumentController {
    private final TransferDocumentService service;
    private final ApprovalOperationSubmissionService operationApprovals;
    private final com.enterprise.mm.service.transfer.TransferSubmissionService submissions;
    private final com.enterprise.mm.service.transfer.TransferLegacyReconciliationService reconciliation;

    @GetMapping("/{id}/reconciliation")
    public ApiResponse<com.enterprise.mm.payload.TransferLegacyReconciliationDtos.ReconciliationPreview> inspectReconciliation(@PathVariable UUID id) {
        return ApiResponse.ok(reconciliation.inspect(id));
    }
    @PostMapping("/{id}/reconciliation")
    public ApiResponse<TransferDocumentResponse> confirmReconciliation(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key,
        @RequestBody com.enterprise.mm.payload.TransferLegacyReconciliationDtos.ReconciliationCommand command) {
        return ApiResponse.ok(reconciliation.confirm(id,command,key));
    }


    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','AUDITOR','VIEWER','FINANCE_CONTROLLER','ACCOUNTANT')")
    public ApiResponse<PageResponse<TransferDocumentResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID sourceWarehouseId,
        @RequestParam(required = false) UUID destinationWarehouseId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.search(
            new TransferSearchRequest(search, status, warehouseId, sourceWarehouseId, destinationWarehouseId, dateFrom, dateTo, action),
            pageable
        )));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','AUDITOR','VIEWER','FINANCE_CONTROLLER','ACCOUNTANT')")
    public ApiResponse<TransferDocumentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> create(@Valid @RequestBody TransferCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> edit(@PathVariable UUID id, @Valid @RequestBody com.enterprise.mm.payload.TransferEditRequest request) {
        return ApiResponse.ok(service.edit(id, request));
    }
    @PostMapping("/{id}/return-for-correction")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> returnForCorrection(@PathVariable UUID id, @RequestBody com.enterprise.mm.payload.TransferShipmentDtos.Action request) {
        return ApiResponse.ok(service.returnForCorrection(id, request));
    }
    @PostMapping("/{id}/cancel-remainder")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> cancelRemainder(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
        @RequestBody com.enterprise.mm.payload.TransferShipmentDtos.Action request) {
        return ApiResponse.ok(service.cancelRemainder(id, request, key));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> submit(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ApiResponse.ok(submissions.submit(id, idempotencyKey));
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<ApprovalOperationResponse> approve(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody TransferApproveRequest request
    ) {
        return ApiResponse.ok(operationApprovals.submit(
            TransferApproveOperationHandler.MODULE_KEY, id, request.expectedVersion(), "{}", idempotencyKey
        ));
    }

    @GetMapping("/{id}/dispatch-context")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<com.enterprise.mm.payload.TransferPickingDtos.Context> dispatchContext(@PathVariable UUID id) {
        return ApiResponse.ok(service.dispatchContext(id));
    }

    @GetMapping("/{id}/lines/{lineId}/stock-options")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<com.enterprise.mm.payload.TransferPickingDtos.StockOptions> stockOptions(@PathVariable UUID id,
        @PathVariable UUID lineId,
        @RequestParam(defaultValue = "FIFO") com.enterprise.mm.enums.PickingMethod pickingMethod,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(service.stockOptions(id, lineId, pickingMethod, page, size));
    }

    @PostMapping("/{id}/dispatch-preview")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<com.enterprise.mm.payload.TransferPickingDtos.Preview> dispatchPreview(@PathVariable UUID id,
        @Valid @RequestBody TransferDispatchRequest request) {
        return ApiResponse.ok(service.dispatchPreview(id, request));
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<TransferDocumentResponse> dispatch(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody TransferDispatchRequest request
    ) {
        return ApiResponse.ok(service.dispatch(id, request, idempotencyKey));
    }

    @GetMapping("/{id}/receive-context")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<TransferReceiptDtos.Context> receiveContext(@PathVariable UUID id) {
        return ApiResponse.ok(service.receiptContext(id));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<TransferDocumentResponse> receive(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody TransferReceiveRequest request
    ) {
        return ApiResponse.ok(service.receive(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/record-discrepancy")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<TransferDocumentResponse> recordDiscrepancy(
        @PathVariable UUID id,
        @Valid @RequestBody TransferDiscrepancyRequest request
    ) {
        return ApiResponse.ok(service.recordDiscrepancy(id, request));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> close(
        @PathVariable UUID id,
        @RequestBody com.enterprise.mm.payload.TransferShipmentDtos.Action request
    ) {
        return ApiResponse.ok(service.close(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<TransferDocumentResponse> cancel(
        @PathVariable UUID id,
        @RequestBody com.enterprise.mm.payload.TransferShipmentDtos.Action request
    ) {
        return ApiResponse.ok(service.cancel(id, request));
    }
}
