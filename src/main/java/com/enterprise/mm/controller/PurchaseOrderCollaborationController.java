package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.BuyerCollaborationCommand;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.CollaborationStatusResponse;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.CommitmentView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.DispatchView;
import com.enterprise.mm.payload.PurchaseOrderCollaborationDtos.ResponseView;
import com.enterprise.mm.service.PurchaseOrderService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationCommandService;
import com.enterprise.mm.supplier.po.service.SupplierPoCollaborationQueryService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal buyer endpoints for the "Supplier collaboration" tab on PO detail --
 * kept as a sibling controller to {@link PurchaseOrderController}, the same
 * "sibling controller for a PO sub-resource" convention {@link PurchaseOrderRevisionController}
 * already follows, rather than growing the already-substantial main PO controller.
 * Data visibility is inherited from {@code PurchaseOrderService.findEntity()}'s
 * {@code RoleDataScopeService} scoping via the {@link PurchaseOrderService#get(UUID)}
 * existence/visibility check performed before every read here.
 */
@RestController
@RequestMapping("/api/purchase-orders/{id}")
@RequiredArgsConstructor
public class PurchaseOrderCollaborationController {
    private final PurchaseOrderService purchaseOrderService;
    private final SupplierPoCollaborationQueryService queryService;
    private final SupplierPoCollaborationCommandService commandService;

    @GetMapping("/supplier-collaboration")
    public ApiResponse<CollaborationStatusResponse> collaboration(@PathVariable UUID id) {
        purchaseOrderService.get(id); // visibility/scoping gate -- throws NotFoundException if not permitted
        return ApiResponse.ok(queryService.statusView(id));
    }

    @GetMapping("/supplier-dispatches")
    public ApiResponse<PageResponse<DispatchView>> dispatches(@PathVariable UUID id) {
        purchaseOrderService.get(id);
        return ApiResponse.ok(PageResponse.from(queryService.dispatchHistory(id)));
    }

    @PostMapping("/supplier-dispatches/{dispatchId}/resend")
    public ApiResponse<DispatchView> resendDispatch(
        @PathVariable UUID id,
        @PathVariable UUID dispatchId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireDispatchBelongsToPurchaseOrder(id, dispatchId);
        return ApiResponse.ok(commandService.resendDispatch(dispatchId, command, idempotencyKey));
    }

    @PostMapping("/supplier-responses/{responseId}/accept")
    public ApiResponse<ResponseView> acceptResponse(
        @PathVariable UUID id,
        @PathVariable UUID responseId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireResponseBelongsToPurchaseOrder(id, responseId);
        return ApiResponse.ok(commandService.acceptResponse(responseId, command, idempotencyKey));
    }

    @PostMapping("/supplier-responses/{responseId}/reject")
    public ApiResponse<ResponseView> rejectResponse(
        @PathVariable UUID id,
        @PathVariable UUID responseId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireResponseBelongsToPurchaseOrder(id, responseId);
        return ApiResponse.ok(commandService.rejectResponse(responseId, command, idempotencyKey));
    }

    @PostMapping("/supplier-responses/{responseId}/request-revision")
    public ApiResponse<ResponseView> requestRevision(
        @PathVariable UUID id,
        @PathVariable UUID responseId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireResponseBelongsToPurchaseOrder(id, responseId);
        return ApiResponse.ok(commandService.requestRevision(responseId, command, idempotencyKey));
    }

    @PostMapping("/supplier-commitments/{commitmentId}/cancel")
    public ApiResponse<CommitmentView> cancelCommitment(
        @PathVariable UUID id,
        @PathVariable UUID commitmentId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireCommitmentBelongsToPurchaseOrder(id, commitmentId);
        return ApiResponse.ok(commandService.cancelCommitment(commitmentId, command, idempotencyKey));
    }

    @PostMapping("/supplier-commitments/{commitmentId}/request-reconfirmation")
    public ApiResponse<ResponseView> requestReconfirmation(
        @PathVariable UUID id,
        @PathVariable UUID commitmentId,
        @Valid @RequestBody BuyerCollaborationCommand command,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        purchaseOrderService.get(id);
        queryService.requireCommitmentBelongsToPurchaseOrder(id, commitmentId);
        return ApiResponse.ok(commandService.requestReconfirmation(commitmentId, command, idempotencyKey));
    }
}
