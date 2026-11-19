package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AdvanceShippingNoticeBatch;
import com.enterprise.mm.entity.AdvanceShippingNoticeSerial;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPoResponseLine;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.enums.ApprovalInstanceStatus;
import com.enterprise.mm.enums.BatchTrackingPolicy;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.enums.SerialTrackingPolicy;
import com.enterprise.mm.enums.ServiceAcceptanceActStatus;
import com.enterprise.mm.enums.TrackingMode;
import com.enterprise.mm.enums.WorkCompletionActStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.fulfillment.FulfillmentQuantities;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnBatchRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnBatchView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnLineView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnSerialRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnSerialView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAttachmentView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCommitmentView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionAttachmentView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderDetail;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderLine;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderSummary;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseLineView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseView;
import com.enterprise.mm.repository.AdvanceShippingNoticeBatchRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeItemRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeRepository;
import com.enterprise.mm.repository.AdvanceShippingNoticeSerialRepository;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.SupplierPoCommitmentRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoResponseLineRepository;
import com.enterprise.mm.repository.SupplierPoResponseRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.service.approval.WorkCompletionActApprovalAdapter;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService.AsnDraftCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoAsnService.AsnLineCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.CommitmentCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseCommands.LineDecisionCommand;
import com.enterprise.mm.supplier.po.service.SupplierPoResponseService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public supplier-portal service for the supplier PO collaboration domain -- the
 * PO-scoped counterpart to {@link SupplierPortalExternalService}. Reuses the exact
 * same token-resolution / tenant-company-supplier context / terms-acceptance pattern
 * (see {@link #resolve(String)}, {@link #withPortalContext}) rather than inventing a
 * different auth mechanism.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierPortalPurchaseOrderExternalService {
    private static final List<String> TERMINAL_DISPATCH_STATUSES = List.of("SUPERSEDED", "CANCELLED");
    private static final Set<String> EDITABLE_COMPLETION_STATUSES = Set.of("DRAFT", "REJECTED", "REVISION_REQUESTED");
    private static final Set<String> SUBMITTED_COMPLETION_STATUSES = Set.of(
        "SUBMITTED",
        "SUBMITTED_FOR_BUYER_REVIEW",
        ServiceAcceptanceActStatus.TECHNICAL_REVIEW.name(),
        ServiceAcceptanceActStatus.DOCUMENT_REVIEW.name(),
        ServiceAcceptanceActStatus.FINANCE_REVIEW.name(),
        ServiceAcceptanceActStatus.APPROVED.name(),
        ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name(),
        ServiceAcceptanceActStatus.PAID.name(),
        WorkCompletionActStatus.APPROVAL_PENDING.name()
    );

    private final SupplierPortalAccountRepository accountRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPoResponseRepository responseRepository;
    private final SupplierPoResponseLineRepository responseLineRepository;
    private final SupplierPoCommitmentRepository commitmentRepository;
    private final AdvanceShippingNoticeRepository asnRepository;
    private final AdvanceShippingNoticeItemRepository asnItemRepository;
    private final AdvanceShippingNoticeBatchRepository asnBatchRepository;
    private final AdvanceShippingNoticeSerialRepository asnSerialRepository;
    private final ServiceAcceptanceActRepository serviceAcceptanceActRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentService attachmentService;
    private final SupplierPoResponseService responseService;
    private final SupplierPoAsnService asnService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final PurchaseOrderServiceOrderLinkService serviceOrderLinkService;
    private final WorkCompletionActService workCompletionActService;
    private final ApprovalInstanceRepository approvalInstanceRepository;

    @Transactional(readOnly = true)
    public Page<SupplierPortalPurchaseOrderSummary> list(String token, Pageable pageable) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            List<SupplierPortalPurchaseOrderSummary> summaries = currentDispatchesForSupplier(account).stream()
                .map(dispatch -> toSummary(account, dispatch))
                .filter(Objects::nonNull)
                .toList();
            return page(summaries, pageable);
        });
    }

    private List<SupplierPoDispatch> currentDispatchesForSupplier(SupplierPortalAccount account) {
        return dispatchRepository.findCurrentPortalCandidatesForSupplierNewest(
            account.getTenantId(),
            account.getCompanyId(),
            account.getSupplierId(),
            TERMINAL_DISPATCH_STATUSES,
            Pageable.unpaged()
        );
    }

    private SupplierPortalPurchaseOrderSummary toSummary(SupplierPortalAccount account, SupplierPoDispatch dispatch) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(dispatch.getPurchaseOrderId()).orElse(null);
        if (po == null || !Objects.equals(po.getSupplierId(), account.getSupplierId())) {
            return null;
        }
        SupplierPoResponse currentResponse = responseRepository
            .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
                account.getTenantId(), account.getCompanyId(), dispatch.getId()
            ).orElse(null);
        boolean alreadyResponded = currentResponse != null && !"DRAFT".equals(currentResponse.getResponseStatus());
        boolean overdue = dispatch.getResponseDueDate() != null
            && dispatch.getResponseDueDate().isBefore(LocalDate.now())
            && !alreadyResponded;
        boolean actionRequired = !alreadyResponded
            || (currentResponse != null && "REVISION_REQUESTED".equals(currentResponse.getResponseStatus()));
        return new SupplierPortalPurchaseOrderSummary(
            po.getId(),
            po.getCode(),
            dispatch.getPurchaseOrderVersion(),
            null,
            po.getTotalAmount(),
            po.getCurrencyCode(),
            po.getOrderDate(),
            dispatch.getResponseDueDate(),
            dispatch.getDispatchStatus(),
            currentResponse == null ? "PENDING" : currentResponse.getResponseStatus(),
            fulfillmentStatus(dispatch, currentResponse),
            overdue,
            actionRequired,
            alreadyResponded,
            false
        );
    }

    private String fulfillmentStatus(SupplierPoDispatch dispatch, SupplierPoResponse response) {
        if (response == null) {
            return "AWAITING_RESPONSE";
        }
        return switch (response.getResponseStatus()) {
            case "BUYER_ACCEPTED" -> "FULFILLING";
            case "BUYER_REJECTED" -> "REJECTED";
            default -> response.getResponseStatus();
        };
    }

    @Transactional
    public SupplierPortalPurchaseOrderDetail detail(String token, UUID purchaseOrderId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            PurchaseOrder po = requirePurchaseOrder(account, purchaseOrderId);
            SupplierPoDispatch dispatch = requireCurrentDispatch(account, purchaseOrderId);
            markViewedOnce(dispatch);
            List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(account.getTenantId(), purchaseOrderId);
            List<SupplierPortalPurchaseOrderLine> lines = items.stream()
                .map(item -> toLine(po, item))
                .toList();
            List<Attachment> attachments = attachmentRepository
                .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    account.getTenantId(), account.getCompanyId(), "PURCHASEORDER", purchaseOrderId
                );
            List<SupplierPortalAttachmentView> artifacts = attachments.stream()
                .map(a -> new SupplierPortalAttachmentView(a.getId(), a.getOriginalFileName(), a.getContentType(), a.getSizeBytes()))
                .toList();
            SupplierPoResponse currentResponse = responseRepository
                .findByTenantIdAndCompanyIdAndDispatchIdAndCurrentTrueAndIsDeletedFalse(
                    account.getTenantId(), account.getCompanyId(), dispatch.getId()
                ).orElse(null);
            boolean alreadyResponded = currentResponse != null && !"DRAFT".equals(currentResponse.getResponseStatus());
            List<SupplierPortalCompletionView> completions = completionViews(account, po, items);
            SupplierPortalCompletionView currentCompletion = completions.isEmpty() ? null : completions.get(0);
            List<SupplierPortalAsnView> asns = asnRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalse(
                    account.getTenantId(), account.getCompanyId(), purchaseOrderId
                )
                .stream()
                .filter(asn -> Objects.equals(asn.getSupplierId(), account.getSupplierId()))
                .map(asn -> toAsnView(account, asn))
                .toList();
            return new SupplierPortalPurchaseOrderDetail(
                po.getId(),
                po.getCode(),
                po.getName(),
                dispatch.getPurchaseOrderVersion(),
                null,
                po.getTotalAmount(),
                po.getCurrencyCode(),
                po.getOrderDate(),
                po.getExpectedDeliveryDate(),
                dispatch.getResponseDueDate(),
                dispatch.getId(),
                dispatch.getDispatchStatus(),
                false,
                alreadyResponded,
                fulfillmentMode(po),
                currentCompletion == null ? "DRAFT" : currentCompletion.status(),
                currentCompletion == null ? "NOT_SUBMITTED" : currentCompletion.buyerReviewStatus(),
                acceptedQuantity(po, items, completions),
                acceptedAmount(po, completions),
                remainingAcceptanceQuantity(po, items, completions),
                remainingAcceptanceAmount(po, completions),
                currentCompletion == null ? null : currentCompletion.acceptanceActId(),
                currentCompletion == null ? null : currentCompletion.acceptanceActNumber(),
                false,
                allowedActions(po),
                lines,
                artifacts,
                currentResponse == null ? null : toResponseView(account, currentResponse),
                currentCompletion,
                completions,
                asns
            );
        });
    }

    private SupplierPortalPurchaseOrderLine toLine(PurchaseOrder po, PurchaseOrderItem item) {
        BigDecimal open = FulfillmentQuantities.physicalReceiptRemaining(item);
        boolean shippableAsGoods = goodsMaterialLine(po, item);
        return new SupplierPortalPurchaseOrderLine(
            item.getId(),
            item.getMaterialId(),
            item.getMaterial() == null ? null : item.getMaterial().getCode(),
            item.getMaterial() == null ? null : item.getMaterial().getName(),
            trackingMode(item),
            batchTrackingPolicy(item),
            serialTrackingPolicy(item),
            item.getUnit(),
            item.getQuantity(),
            item.getUnitPrice(),
            open,
            shippableAsGoods,
            shippableAsGoods
        );
    }

    private String fulfillmentMode(PurchaseOrder po) {
        RequestType requestType = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        return switch (requestType) {
            case SERVICE -> "SERVICE_CONFIRMATION";
            case GOODS -> "GOODS_SHIPMENT";
        };
    }

    private List<String> allowedActions(PurchaseOrder po) {
        RequestType requestType = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        return switch (requestType) {
            case SERVICE -> List.of("ACKNOWLEDGE_PO", "SUBMIT_RESPONSE", "SUBMIT_SERVICE_COMPLETION");
            case GOODS -> List.of("ACKNOWLEDGE_PO", "SUBMIT_RESPONSE", "CREATE_SHIPMENT");
        };
    }


    private void markViewedOnce(SupplierPoDispatch dispatch) {
        if (dispatch.getViewedAt() == null) {
            dispatch.setViewedAt(LocalDateTime.now());
            dispatchRepository.save(dispatch);
            auditService.log("PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_VIEWED", dispatch.getCode(), null, null);
            notificationService.createForRole(
                RoleName.PROCUREMENT_MANAGER,
                "Supplier viewed purchase order",
                "Supplier opened purchase order dispatch " + dispatch.getCode() + " for the first time.",
                "PO supplier viewed " + dispatch.getCode(),
                NotificationEntityType.PURCHASE_ORDER.name(),
                dispatch.getPurchaseOrderId()
            );
        }
    }

    @Transactional(readOnly = true)
    public AttachmentService.AttachmentDownload downloadArtifact(String token, UUID purchaseOrderId, UUID attachmentId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requirePurchaseOrder(account, purchaseOrderId);
            Attachment attachment = attachmentRepository.findByIdAndIsDeletedFalse(attachmentId)
                .filter(a -> account.getTenantId().equals(a.getTenantId()) && Objects.equals(account.getCompanyId(), a.getCompanyId()))
                .filter(a -> "PURCHASEORDER".equals(a.getEntityType()) && Objects.equals(a.getEntityId(), purchaseOrderId))
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
            return attachmentService.download(attachment.getId());
        });
    }

    @Transactional
    public SupplierPortalResponseView createResponseDraft(String token, UUID purchaseOrderId, SupplierPortalResponseDraftRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            SupplierPoDispatch dispatch = requireCurrentDispatch(account, purchaseOrderId);
            SupplierPoResponse draft = responseService.startOrGetDraft(dispatch.getId());
            responseService.saveDraftLines(draft.getId(), toLineCommands(request), request.supplierComment());
            return toResponseView(account, requireOwnedResponse(account, draft.getId()));
        });
    }

    @Transactional
    public SupplierPortalResponseView updateResponseDraft(String token, UUID purchaseOrderId, UUID responseId, SupplierPortalResponseDraftRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            requireOwnedResponse(account, responseId);
            responseService.saveDraftLines(responseId, toLineCommands(request), request.supplierComment());
            return toResponseView(account, requireOwnedResponse(account, responseId));
        });
    }

    @Transactional
    public SupplierPortalResponseView submitResponse(String token, UUID purchaseOrderId, UUID responseId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            requireOwnedResponse(account, responseId);
            SupplierPoResponse submitted = responseService.submit(responseId);
            return toResponseView(account, submitted);
        });
    }

    @Transactional
    public SupplierPortalResponseView replaceResponse(String token, UUID purchaseOrderId, UUID responseId, SupplierPortalResponseDraftRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            requireOwnedResponse(account, responseId);
            SupplierPoResponse next = responseService.replace(responseId, request == null ? null : request.supplierComment());
            if (request != null) {
                responseService.saveDraftLines(next.getId(), toLineCommands(request), request.supplierComment());
            }
            return toResponseView(account, requireOwnedResponse(account, next.getId()));
        });
    }

    @Transactional
    public SupplierPortalResponseView withdrawResponse(String token, UUID purchaseOrderId, UUID responseId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requirePurchaseOrder(account, purchaseOrderId);
            requireOwnedResponse(account, responseId);
            SupplierPoResponse withdrawn = responseService.withdraw(responseId);
            return toResponseView(account, withdrawn);
        });
    }

    @Transactional(readOnly = true)
    public List<SupplierPortalCommitmentView> commitments(String token, UUID purchaseOrderId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            PurchaseOrder po = requirePurchaseOrder(account, purchaseOrderId);
            return commitmentRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndStatusInAndIsDeletedFalse(
                    account.getTenantId(), account.getCompanyId(), purchaseOrderId,
                    List.of("PROPOSED", "CONFIRMED")
                )
                .stream()
                .filter(c -> Objects.equals(c.getSupplierId(), account.getSupplierId()))
                .map(commitment -> toCommitmentView(po, commitment))
                .toList();
        });
    }

    @Transactional
    public SupplierPortalAsnView createAsnDraft(String token, UUID purchaseOrderId, SupplierPortalAsnDraftRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            var asn = asnService.createDraft(purchaseOrderId, account.getSupplierId(), account.getId(), toAsnCommand(request));
            return toAsnView(account, asn);
        });
    }

    @Transactional
    public SupplierPortalAsnView updateAsnDraft(String token, UUID purchaseOrderId, UUID asnId, SupplierPortalAsnDraftRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            var asn = asnService.updateDraft(purchaseOrderId, asnId, account.getSupplierId(), toAsnCommand(request));
            return toAsnView(account, asn);
        });
    }

    @Transactional
    public SupplierPortalAsnView submitAsn(String token, UUID purchaseOrderId, UUID asnId, String idempotencyKey) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            requirePurchaseOrder(account, purchaseOrderId);
            var asn = asnService.submit(purchaseOrderId, asnId, account.getSupplierId(), idempotencyKey);
            return toAsnView(account, asn);
        });
    }

    @Transactional
    public SupplierPortalCompletionView submitServiceCompletion(
        String token,
        UUID purchaseOrderId,
        SupplierPortalCompletionRequest request,
        List<MultipartFile> files,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            PurchaseOrder po = requirePurchaseOrder(account, purchaseOrderId);
            requireCurrentDispatch(account, purchaseOrderId);
            requireFulfillmentMode(po, RequestType.SERVICE, "service-completions");
            validateCompletionRequest(request, true);
            ServiceOrder order = serviceOrderForPortalCompletion(po);

            ServiceAcceptanceAct act = serviceActForSubmit(account, po, order);
            applyServiceCompletionRequest(account, po, order, act, request);
            ServiceAcceptanceAct saved = serviceAcceptanceActRepository.saveAndFlush(act);
            uploadCompletionEvidence("SERVICE_ACCEPTANCE_ACT", saved.getId(), files);
            ServiceAcceptanceAct submitted = serviceAcceptanceActRepository.findByIdAndIsDeletedFalse(saved.getId()).orElse(saved);
            po.setStatus("PARTIALLY_COMPLETED");
            purchaseOrderRepository.save(po);
            auditService.log("ServiceAcceptanceAct", saved.getId(), "SUPPLIER_PORTAL_SERVICE_COMPLETION_SUBMIT", po.getCode(), null, null);
            return toServiceCompletionView(account, po, submitted);
        });
    }

    @Transactional
    public SupplierPortalCompletionView submitWorkCompletion(
        String token,
        UUID purchaseOrderId,
        SupplierPortalCompletionRequest request,
        List<MultipartFile> files,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            PurchaseOrder po = requirePurchaseOrder(account, purchaseOrderId);
            requireCurrentDispatch(account, purchaseOrderId);
            validateCompletionRequest(request, false);

            List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(account.getTenantId(), purchaseOrderId)
                .stream()
                .toList();
            PurchaseOrderItem item = items
                .stream()
                .filter(candidate -> candidate.getLineType() == ProcurementLineType.WORK)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                    "work-completions cannot be used for " + fulfillmentMode(po, items) + " purchase orders"));
            BigDecimal amount = request.completedQuantity().multiply(nvl(item.getUnitPrice()));

            WorkCompletionAct act = workActForSubmit(account, po, item);
            applyWorkCompletionRequest(account, po, item, act, request, amount);
            WorkCompletionAct saved = workCompletionActRepository.save(act);
            uploadCompletionEvidence("WORK_COMPLETION_ACT", saved.getId(), files);
            // Reuse buyer submit path so approval instance + tasks are created (same as WorkCompletionActService).
            workCompletionActService.submitForApproval(saved.getId());
            po.setStatus("PARTIALLY_COMPLETED");
            purchaseOrderRepository.save(po);
            auditService.log("WorkCompletionAct", saved.getId(), "SUPPLIER_PORTAL_WORK_COMPLETION_SUBMIT", po.getCode(), null, null);
            return toWorkCompletionView(account, po, workCompletionActRepository.findByIdAndIsDeletedFalse(saved.getId()).orElse(saved));
        });
    }

    private ServiceOrder serviceOrderForPortalCompletion(PurchaseOrder po) {
        return serviceOrderLinkService
            .ensureForPurchaseOrder(po, "RECOVERY_FROM_SUPPLIER_PORTAL_SERVICE_COMPLETION")
            .orElseThrow(() -> new BusinessException("Service completion is available only for service purchase orders"));
    }

    private ServiceAcceptanceAct serviceActForSubmit(SupplierPortalAccount account, PurchaseOrder po, ServiceOrder order) {
        List<ServiceAcceptanceAct> activeActs = serviceAcceptanceActRepository
            .findByTenantIdAndCompanyIdAndServiceOrderIdAndIsDeletedFalse(
                account.getTenantId(), account.getCompanyId(), order.getId()
            );
        if (!activeActs.isEmpty()) {
            ServiceAcceptanceAct existing = activeActs.get(0);
            assertCompletionEditable(existing.getStatus(), "Service completion is already submitted for buyer review");
            return existing;
        }
        ServiceAcceptanceAct act = new ServiceAcceptanceAct();
        act.setCode("SAA-PORTAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        act.setName(com.enterprise.mm.util.BusinessNameGenerator.workflowDocumentName("ServiceAcceptanceAct", order.getName()));
        act.setTenantId(account.getTenantId());
        act.setCompanyId(account.getCompanyId());
        return act;
    }

    private void applyServiceCompletionRequest(
        SupplierPortalAccount account,
        PurchaseOrder po,
        ServiceOrder order,
        ServiceAcceptanceAct act,
        SupplierPortalCompletionRequest request
    ) {
        act.setStatus(ServiceAcceptanceActStatus.DRAFT.name());
        act.setServiceRequestId(order.getServiceRequestId());
        act.setServiceOrderId(order.getId());
        act.setPurchaseOrderId(po.getId());
        act.setContractId(order.getContractId());
        act.setAcceptedAt(request.completionDate().atStartOfDay());
        act.setAcceptedBy(account.getLoginEmail());
        act.setAmount(request.completedAmount());
        act.setVatAmount(BigDecimal.ZERO);
        act.setVatRate(BigDecimal.ZERO);
        act.setStage("SUPPLIER_PORTAL");
        act.setPeriodStart(request.completionDate());
        act.setPeriodEnd(request.completionDate());
        act.setCommissionNotes(request.comment().trim());
        act.setRemarks(request.comment().trim());
    }

    private WorkCompletionAct workActForSubmit(SupplierPortalAccount account, PurchaseOrder po, PurchaseOrderItem item) {
        List<WorkCompletionAct> activeActs = workCompletionActRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderItemIdAndIsDeletedFalseOrderByCreatedAtDesc(
                account.getTenantId(), account.getCompanyId(), item.getId()
            );
        if (!activeActs.isEmpty()) {
            WorkCompletionAct existing = activeActs.get(0);
            if (isOrphanApprovalPendingWorkAct(existing)) {
                // Portal previously set APPROVAL_PENDING without starting the engine — allow proper resubmit.
                existing.setStatus(WorkCompletionActStatus.DRAFT.name());
                return existing;
            }
            assertCompletionEditable(existing.getStatus(), "Work completion is already submitted for buyer review");
            return existing;
        }
        WorkCompletionAct act = new WorkCompletionAct();
        act.setCode("WCA-PORTAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        act.setName(com.enterprise.mm.util.BusinessNameGenerator.workflowDocumentName("WorkCompletionAct", po.getName()));
        act.setTenantId(account.getTenantId());
        act.setCompanyId(account.getCompanyId());
        return act;
    }

    private boolean isOrphanApprovalPendingWorkAct(WorkCompletionAct act) {
        if (act == null || act.getId() == null
            || !WorkCompletionActStatus.APPROVAL_PENDING.name().equals(act.getStatus())) {
            return false;
        }
        return approvalInstanceRepository
            .findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(
                act.getTenantId(),
                act.getCompanyId(),
                WorkCompletionActApprovalAdapter.MODULE_KEY,
                act.getId()
            )
            .filter(instance -> instance.getApprovalStatus() == ApprovalInstanceStatus.PENDING)
            .isEmpty();
    }

    private void applyWorkCompletionRequest(
        SupplierPortalAccount account,
        PurchaseOrder po,
        PurchaseOrderItem item,
        WorkCompletionAct act,
        SupplierPortalCompletionRequest request,
        BigDecimal amount
    ) {
        // DRAFT until WorkCompletionActService.submitForApproval starts the shared approval engine.
        act.setStatus(WorkCompletionActStatus.DRAFT.name());
        act.setPurchaseOrderId(po.getId());
        act.setPurchaseOrderItemId(item.getId());
        act.setContractId(po.getContractId());
        act.setPeriodStart(request.completionDate());
        act.setPeriodEnd(request.completionDate());
        act.setCompletedQuantity(request.completedQuantity());
        act.setAmount(amount);
        act.setAcceptedAt(request.completionDate().atStartOfDay());
        act.setAcceptedBy(account.getLoginEmail());
        act.setCommissionNotes(request.comment().trim());
        act.setRemarks(request.comment().trim());
    }

    private void assertCompletionEditable(String status, String submittedMessage) {
        String normalized = status == null || status.isBlank() ? "DRAFT" : status;
        if (EDITABLE_COMPLETION_STATUSES.contains(normalized)) {
            return;
        }
        if (SUBMITTED_COMPLETION_STATUSES.contains(normalized)) {
            throw new BusinessException(submittedMessage);
        }
        throw new BusinessException(submittedMessage);
    }

    private AsnDraftCommand toAsnCommand(SupplierPortalAsnDraftRequest request) {
        if (request == null) {
            throw new BusinessException("ASN request body is required");
        }
        List<AsnLineCommand> lines = request.lines() == null
            ? List.of()
            : request.lines().stream()
                .map(line -> new AsnLineCommand(
                    line.supplierPoCommitmentId(),
                    line.quantity(),
                    line.packageCount(),
                    line.packageType(),
                    line.grossWeight(),
                    line.netWeight(),
                    line.batchNumber(),
                    line.serialNumbers(),
                    line.expirationDate(),
                    toAsnBatchCommands(line.batches()),
                    toAsnSerialCommands(line.serials())
                ))
                .toList();
        return new AsnDraftCommand(
            request.asnNumber(),
            request.noticeDate(),
            request.expectedShipDate(),
            request.expectedArrivalDate(),
            request.transportMode(),
            request.carrierName(),
            request.vehicleNumber(),
            request.containerNumber(),
            request.trackingNumber(),
            lines
        );
    }

    private SupplierPortalAsnView toAsnView(SupplierPortalAccount account, com.enterprise.mm.entity.AdvanceShippingNotice asn) {
        List<SupplierPortalAsnLineView> lines = asnItemRepository
            .findByTenantIdAndCompanyIdAndAsn_IdAndIsDeletedFalse(account.getTenantId(), account.getCompanyId(), asn.getId())
            .stream()
            .map(item -> {
                List<SupplierPortalAsnBatchView> batches = asnBatchRepository
                    .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                        account.getTenantId(), account.getCompanyId(), item.getId())
                    .stream()
                    .map(batch -> new SupplierPortalAsnBatchView(
                        batch.getId(), batch.getBatchNumber(), batch.getQuantity(), batch.getExpirationDate()
                    ))
                    .toList();
                List<SupplierPortalAsnSerialView> serials = asnSerialRepository
                    .findByTenantIdAndCompanyIdAndAsnItemIdAndIsDeletedFalseOrderByCreatedAtAsc(
                        account.getTenantId(), account.getCompanyId(), item.getId())
                    .stream()
                    .map(serial -> new SupplierPortalAsnSerialView(
                        serial.getId(), serial.getManufacturerSerialNumber(), serial.getBatchNumber()
                    ))
                    .toList();
                return new SupplierPortalAsnLineView(
                    item.getId(),
                    item.getSupplierPoCommitmentId(),
                    item.getQuantity(),
                    item.getPackageCount(),
                    item.getPackageType(),
                    item.getGrossWeight(),
                    item.getNetWeight(),
                    item.getBatchNumber(),
                    serials.isEmpty()
                        ? splitSerialNumbers(item.getSerialNumbers())
                        : serials.stream().map(SupplierPortalAsnSerialView::manufacturerSerialNumber).toList(),
                    item.getExpirationDate(),
                    batches.isEmpty() ? legacyBatchView(item) : batches,
                    serials
                );
            })
            .toList();
        return new SupplierPortalAsnView(
            asn.getId(), asn.getAsnNumber(), asn.getStatus(), asn.getNoticeDate(), asn.getExpectedShipDate(),
            asn.getExpectedArrivalDate(), asn.getTransportMode(), asn.getCarrierName(), asn.getVehicleNumber(),
            asn.getContainerNumber(), asn.getTrackingNumber(), asn.getTotalQuantity(), lines
        );
    }

    private List<String> splitSerialNumbers(String serialNumbers) {
        if (serialNumbers == null || serialNumbers.isBlank()) {
            return List.of();
        }
        return Arrays.stream(serialNumbers.split("[,;\\r\\n]+"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private List<SupplierPoAsnService.AsnBatchCommand> toAsnBatchCommands(List<SupplierPortalAsnBatchRequest> batches) {
        if (batches == null) {
            return List.of();
        }
        return batches.stream()
            .filter(Objects::nonNull)
            .map(batch -> new SupplierPoAsnService.AsnBatchCommand(batch.batchNumber(), batch.quantity(), batch.expirationDate()))
            .toList();
    }

    private List<SupplierPoAsnService.AsnSerialCommand> toAsnSerialCommands(List<SupplierPortalAsnSerialRequest> serials) {
        if (serials == null) {
            return List.of();
        }
        return serials.stream()
            .filter(Objects::nonNull)
            .map(serial -> new SupplierPoAsnService.AsnSerialCommand(serial.manufacturerSerialNumber(), serial.batchNumber()))
            .toList();
    }

    private List<SupplierPortalAsnBatchView> legacyBatchView(com.enterprise.mm.entity.AdvanceShippingNoticeItem item) {
        if ((item.getBatchNumber() == null || item.getBatchNumber().isBlank()) && item.getExpirationDate() == null) {
            return List.of();
        }
        return List.of(new SupplierPortalAsnBatchView(null, item.getBatchNumber(), item.getQuantity(), item.getExpirationDate()));
    }

    private TrackingMode trackingMode(PurchaseOrderItem item) {
        if (item == null) {
            return TrackingMode.QUANTITY;
        }
        if (item.getTrackingModeSnapshot() != null) {
            return item.getTrackingModeSnapshot();
        }
        return TrackingMode.fromPolicies(batchTrackingPolicy(item), serialTrackingPolicy(item));
    }

    private BatchTrackingPolicy batchTrackingPolicy(PurchaseOrderItem item) {
        if (item == null) {
            return BatchTrackingPolicy.NONE;
        }
        if (item.getBatchTrackingSnapshot() != null) {
            return item.getBatchTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.batchTrackingPolicy(item.getTrackingModeSnapshot());
        }
        if (item.getMaterial() != null && item.getMaterial().getBatchTrackingPolicy() != null) {
            return item.getMaterial().getBatchTrackingPolicy();
        }
        return item.getMaterial() == null ? BatchTrackingPolicy.NONE : TrackingMode.batchTrackingPolicy(item.getMaterial().getTrackingMode());
    }

    private SerialTrackingPolicy serialTrackingPolicy(PurchaseOrderItem item) {
        if (item == null) {
            return SerialTrackingPolicy.NONE;
        }
        if (item.getSerialTrackingSnapshot() != null) {
            return item.getSerialTrackingSnapshot();
        }
        if (item.getTrackingModeSnapshot() != null) {
            return TrackingMode.serialTrackingPolicy(item.getTrackingModeSnapshot());
        }
        if (item.getMaterial() != null && item.getMaterial().getSerialTrackingPolicy() != null) {
            return item.getMaterial().getSerialTrackingPolicy();
        }
        return item.getMaterial() == null ? SerialTrackingPolicy.NONE : TrackingMode.serialTrackingPolicy(item.getMaterial().getTrackingMode());
    }

    private SupplierPortalCommitmentView toCommitmentView(PurchaseOrder po, SupplierPoCommitment commitment) {
        PurchaseOrderItem item = commitment.getPurchaseOrderItem();
        BigDecimal remainingQuantity = "CONFIRMED".equals(commitment.getStatus())
            ? asnService.remainingQuantity(commitment)
            : BigDecimal.ZERO;
        boolean shippableAsGoods = goodsMaterialLine(po, item) && remainingQuantity.signum() > 0;
        return new SupplierPortalCommitmentView(
            commitment.getId(),
            commitment.getPurchaseOrderItemId(),
            item == null ? null : item.getMaterialId(),
            item != null && item.getMaterial() != null ? item.getMaterial().getCode() : null,
            item != null && item.getMaterial() != null ? item.getMaterial().getName() : null,
            trackingMode(item),
            batchTrackingPolicy(item),
            serialTrackingPolicy(item),
            commitment.getProposedQuantity(),
            commitment.getProposedDate(),
            commitment.getConfirmedQuantity(),
            commitment.getConfirmedDate(),
            commitment.getStatus(),
            remainingQuantity,
            shippableAsGoods,
            shippableAsGoods
        );
    }

    private boolean goodsMaterialLine(PurchaseOrder po, PurchaseOrderItem item) {
        if (item == null || item.getMaterialId() == null) {
            return false;
        }
        RequestType requestType = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        if (requestType != RequestType.GOODS) {
            return false;
        }
        ProcurementLineType lineType = item.getLineType() == null ? ProcurementLineType.MATERIAL : item.getLineType();
        return lineType == ProcurementLineType.MATERIAL;
    }

    private void requireFulfillmentMode(PurchaseOrder po, RequestType expected, String endpoint) {
        RequestType actual = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        if (actual != expected) {
            throw new BusinessException(endpoint + " cannot be used for " + actual + " purchase orders");
        }
    }

    private String fulfillmentMode(PurchaseOrder po, List<PurchaseOrderItem> items) {
        RequestType requestType = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        if (requestType == RequestType.SERVICE
            && items != null
            && items.stream().anyMatch(item -> item.getLineType() == ProcurementLineType.WORK)) {
            return "WORKS";
        }
        return requestType.name();
    }

    private void validateCompletionRequest(SupplierPortalCompletionRequest request, boolean service) {
        if (request == null) {
            throw new BusinessException("Completion request body is required");
        }
        BigDecimal value = service ? request.completedAmount() : request.completedQuantity();
        if (value == null || value.signum() <= 0) {
            throw new BusinessException(service
                ? "Completed service amount must be greater than zero"
                : "Completed work quantity must be greater than zero");
        }
        if (request.completionDate() == null) {
            throw new BusinessException("Completion date is required");
        }
        if (request.comment() == null || request.comment().trim().length() < 10) {
            throw new BusinessException("Comment must be at least 10 characters");
        }
    }

    private void uploadCompletionEvidence(String entityType, UUID entityId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                attachmentService.upload(entityType, entityId, "CONTRACTOR_REPORT", file);
            }
        }
    }

    private List<SupplierPortalCompletionView> completionViews(
        SupplierPortalAccount account,
        PurchaseOrder po,
        List<PurchaseOrderItem> items
    ) {
        RequestType requestType = po == null || po.getRequestType() == null ? RequestType.GOODS : po.getRequestType();
        if (requestType == RequestType.SERVICE) {
            return serviceAcceptanceActRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    account.getTenantId(), account.getCompanyId(), po.getId()
                )
                .stream()
                .map(act -> toServiceCompletionView(account, po, act))
                .toList();
        }
        return List.of();
    }




    private SupplierPortalCompletionView toServiceCompletionView(SupplierPortalAccount account, PurchaseOrder po, ServiceAcceptanceAct act) {
        BigDecimal completedAmount = nvl(act.getAmount());
        return new SupplierPortalCompletionView(
            act.getId(),
            act.getVersion(),
            portalCompletionStatus(act.getStatus()),
            null,
            completedAmount,
            completionDate(act.getPeriodEnd(), act.getAcceptedAt()),
            act.getRemarks() == null ? act.getCommissionNotes() : act.getRemarks(),
            act.getCreatedAt(),
            portalBuyerReviewStatus(act.getStatus()),
            null,
            serviceAcceptedAmount(po, List.of(toRawServiceCompletion(act))),
            null,
            nvl(po.getTotalAmount()).subtract(completedAmount).max(BigDecimal.ZERO),
            act.getId(),
            act.getCode(),
            completionAttachments(account, "SERVICE_ACCEPTANCE_ACT", act.getId())
        );
    }

    private SupplierPortalCompletionView toWorkCompletionView(SupplierPortalAccount account, PurchaseOrder po, WorkCompletionAct act) {
        BigDecimal completedQuantity = nvl(act.getCompletedQuantity());
        BigDecimal completedAmount = nvl(act.getAmount());
        return new SupplierPortalCompletionView(
            act.getId(),
            act.getVersion(),
            portalCompletionStatus(act.getStatus()),
            completedQuantity,
            completedAmount,
            completionDate(act.getPeriodEnd(), act.getAcceptedAt()),
            act.getRemarks() == null ? act.getCommissionNotes() : act.getRemarks(),
            act.getCreatedAt(),
            portalBuyerReviewStatus(act.getStatus()),
            workAcceptedQuantity(po, List.of(toRawWorkCompletion(act))),
            workAcceptedAmount(po, List.of(toRawWorkCompletion(act))),
            totalWorkQuantity(po).subtract(completedQuantity).max(BigDecimal.ZERO),
            nvl(po.getTotalAmount()).subtract(completedAmount).max(BigDecimal.ZERO),
            act.getId(),
            act.getCode(),
            completionAttachments(account, "WORK_COMPLETION_ACT", act.getId())
        );
    }

    private List<SupplierPortalCompletionAttachmentView> completionAttachments(
        SupplierPortalAccount account,
        String entityType,
        UUID entityId
    ) {
        return attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                account.getTenantId(), account.getCompanyId(), entityType, entityId
            )
            .stream()
            .map(attachment -> new SupplierPortalCompletionAttachmentView(
                attachment.getId(), attachment.getOriginalFileName(), attachment.getContentType()
            ))
            .toList();
    }

    private String portalCompletionStatus(String status) {
        if (status == null || status.isBlank() || ServiceAcceptanceActStatus.DRAFT.name().equals(status)
            || WorkCompletionActStatus.DRAFT.name().equals(status)) {
            return "DRAFT";
        }
        if (ServiceAcceptanceActStatus.REJECTED.name().equals(status) || WorkCompletionActStatus.REJECTED.name().equals(status)) {
            return "REJECTED";
        }
        if (ServiceAcceptanceActStatus.APPROVED.name().equals(status)
            || ServiceAcceptanceActStatus.SENT_TO_PAYMENT.name().equals(status)
            || ServiceAcceptanceActStatus.PAID.name().equals(status)
            || WorkCompletionActStatus.APPROVED.name().equals(status)
            || WorkCompletionActStatus.SENT_TO_PAYMENT.name().equals(status)
            || WorkCompletionActStatus.PAID.name().equals(status)) {
            return "BUYER_ACCEPTED";
        }
        return "SUBMITTED";
    }

    private String portalBuyerReviewStatus(String status) {
        if (status == null || status.isBlank() || "DRAFT".equals(portalCompletionStatus(status))) {
            return "NOT_SUBMITTED";
        }
        if ("BUYER_ACCEPTED".equals(portalCompletionStatus(status))) {
            return "BUYER_ACCEPTED";
        }
        if ("REJECTED".equals(portalCompletionStatus(status))) {
            return "REJECTED";
        }
        return "SUBMITTED";
    }

    private LocalDate completionDate(LocalDate periodEnd, LocalDateTime acceptedAt) {
        if (periodEnd != null) {
            return periodEnd;
        }
        return acceptedAt == null ? null : acceptedAt.toLocalDate();
    }

    private BigDecimal acceptedQuantity(PurchaseOrder po, List<PurchaseOrderItem> items, List<SupplierPortalCompletionView> completions) {
        return null;
    }

    private BigDecimal acceptedAmount(PurchaseOrder po, List<SupplierPortalCompletionView> completions) {
        if (po == null || po.getRequestType() == RequestType.GOODS) {
            return null;
        }
        return completions.stream()
            .map(SupplierPortalCompletionView::completedAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal remainingAcceptanceQuantity(PurchaseOrder po, List<PurchaseOrderItem> items, List<SupplierPortalCompletionView> completions) {
        return null;
    }


    private BigDecimal remainingAcceptanceAmount(PurchaseOrder po, List<SupplierPortalCompletionView> completions) {
        if (po == null || po.getRequestType() == RequestType.GOODS) {
            return null;
        }
        return nvl(po.getTotalAmount()).subtract(acceptedAmount(po, completions)).max(BigDecimal.ZERO);
    }

    private BigDecimal totalWorkQuantity(PurchaseOrder po) {
        return purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(TenantContext.currentTenant(), po.getId())
            .stream()
            .filter(item -> item.getLineType() == ProcurementLineType.WORK)
            .map(PurchaseOrderItem::getQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private SupplierPortalCompletionView toRawServiceCompletion(ServiceAcceptanceAct act) {
        return new SupplierPortalCompletionView(
            act.getId(), act.getVersion(), act.getStatus(), null, act.getAmount(), completionDate(act.getPeriodEnd(), act.getAcceptedAt()),
            act.getRemarks(), act.getCreatedAt(), act.getStatus(), null, act.getAmount(), null, null, act.getId(), act.getCode(), List.of()
        );
    }

    private SupplierPortalCompletionView toRawWorkCompletion(WorkCompletionAct act) {
        return new SupplierPortalCompletionView(
            act.getId(), act.getVersion(), act.getStatus(), act.getCompletedQuantity(), act.getAmount(),
            completionDate(act.getPeriodEnd(), act.getAcceptedAt()), act.getRemarks(), act.getCreatedAt(), act.getStatus(),
            act.getCompletedQuantity(), act.getAmount(), null, null, act.getId(), act.getCode(), List.of()
        );
    }

    private BigDecimal serviceAcceptedAmount(PurchaseOrder po, List<SupplierPortalCompletionView> completions) {
        return completions.stream()
            .map(SupplierPortalCompletionView::completedAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal workAcceptedQuantity(PurchaseOrder po, List<SupplierPortalCompletionView> completions) {
        return completions.stream()
            .map(SupplierPortalCompletionView::completedQuantity)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal workAcceptedAmount(PurchaseOrder po, List<SupplierPortalCompletionView> completions) {
        return completions.stream()
            .map(SupplierPortalCompletionView::completedAmount)
            .map(this::nvl)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private SupplierPortalResponseView toResponseView(SupplierPortalAccount account, SupplierPoResponse response) {
        List<SupplierPortalResponseLineView> lines = responseLineRepository
            .findByTenantIdAndCompanyIdAndResponseIdAndIsDeletedFalse(account.getTenantId(), account.getCompanyId(), response.getId())
            .stream()
            .map(line -> toResponseLineView(account, line))
            .toList();
        return new SupplierPortalResponseView(
            response.getId(), response.getResponseRevision(), response.getResponseType(), response.getResponseStatus(),
            response.getSupplierComment(), response.getRejectionReason(), response.getSubmittedAt(),
            response.getBuyerDecisionAt(), response.getBuyerDecisionComment(), lines
        );
    }

    private SupplierPortalResponseLineView toResponseLineView(SupplierPortalAccount account, SupplierPoResponseLine line) {
        List<SupplierPortalCommitmentView> commitments = commitmentRepository
            .findByTenantIdAndCompanyIdAndResponseLineIdAndIsDeletedFalse(account.getTenantId(), account.getCompanyId(), line.getId())
            .stream()
            .map(commitment -> toCommitmentView(
                commitment.getPurchaseOrderItem() == null ? null : commitment.getPurchaseOrderItem().getPurchaseOrder(),
                commitment
            ))
            .toList();
        return new SupplierPortalResponseLineView(
            line.getId(), line.getPurchaseOrderItemId(), line.getDecision(), line.getOrderedQuantity(),
            line.getOpenQuantityAtDispatch(), line.getAcceptedQuantity(), line.getUnconfirmedQuantity(),
            line.getReasonCode(), line.getSupplierComment(), commitments
        );
    }

    private List<LineDecisionCommand> toLineCommands(SupplierPortalResponseDraftRequest request) {
        if (request == null || request.lines() == null) {
            throw new BusinessException("Response lines are required");
        }
        return request.lines().stream()
            .map(line -> new LineDecisionCommand(
                line.purchaseOrderItemId(),
                line.decision(),
                line.acceptedQuantity(),
                line.reasonCode(),
                line.supplierComment(),
                line.commitments() == null
                    ? List.of()
                    : line.commitments().stream()
                        .map(c -> new CommitmentCommand(c.proposedQuantity(), c.proposedDate()))
                        .toList()
            ))
            .toList();
    }

    private PurchaseOrder requirePurchaseOrder(SupplierPortalAccount account, UUID purchaseOrderId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(found -> account.getTenantId().equals(found.getTenantId()) && Objects.equals(account.getCompanyId(), found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        if (!Objects.equals(po.getSupplierId(), account.getSupplierId())) {
            throw new BusinessException("Purchase order does not belong to the authenticated supplier");
        }
        return po;
    }

    private SupplierPoDispatch requireCurrentDispatch(SupplierPortalAccount account, UUID purchaseOrderId) {
        SupplierPoDispatch dispatch = dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
                account.getTenantId(), account.getCompanyId(), purchaseOrderId
            )
            .orElseThrow(() -> new NotFoundException("No active dispatch for this purchase order"));
        if (!Objects.equals(dispatch.getSupplierId(), account.getSupplierId())) {
            throw new BusinessException("Dispatch does not belong to the authenticated supplier");
        }
        if ("SUPERSEDED".equals(dispatch.getDispatchStatus()) || "CANCELLED".equals(dispatch.getDispatchStatus())) {
            throw new BusinessException("This purchase order dispatch is no longer current");
        }
        return dispatch;
    }

    private SupplierPoResponse requireOwnedResponse(SupplierPortalAccount account, UUID responseId) {
        SupplierPoResponse response = responseRepository.findByIdAndIsDeletedFalse(responseId)
            .filter(found -> account.getTenantId().equals(found.getTenantId()) && Objects.equals(account.getCompanyId(), found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier response not found: " + responseId));
        if (!Objects.equals(response.getSupplierId(), account.getSupplierId())) {
            throw new BusinessException("Response does not belong to the authenticated supplier");
        }
        return response;
    }

    private void requireTerms(SupplierPortalAccount account) {
        if (account.getTermsAcceptedAt() == null) {
            throw new BusinessException("Supplier portal terms must be accepted before submissions");
        }
    }

    private SupplierPortalAccount resolve(String token) {
        String rawToken = requireText(token, "Supplier portal token is required");
        String tokenHash = sha256(rawToken);
        SupplierPortalAccount account = accountRepository.findByPortalTokenHashAndIsDeletedFalse(tokenHash)
            .orElseThrow(() -> {
                logSessionLookupMiss(tokenHash);
                return new BusinessException("Supplier portal session not found");
            });
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException("Supplier portal account is not active");
        }
        if (account.getPortalTokenExpiresAt() == null || account.getPortalTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Supplier portal session expired");
        }
        return account;
    }

    private void logSessionLookupMiss(String tokenHash) {
        List<SupplierPortalAccount> accounts = accountRepository.findByPortalTokenHash(tokenHash);
        log.warn(
            "Supplier portal PO session lookup miss: tokenHashPrefix={}, tenantContext={}, companyContext={}, countByHash={}, candidates={}",
            tokenHashPrefix(tokenHash),
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            accountRepository.countByPortalTokenHash(tokenHash),
            accounts.stream()
                .map(account -> Map.of(
                    "id", Objects.toString(account.getId(), ""),
                    "tenantId", Objects.toString(account.getTenantId(), ""),
                    "companyId", Objects.toString(account.getCompanyId(), ""),
                    "status", Objects.toString(account.getStatus(), ""),
                    "portalTokenExpiresAt", Objects.toString(account.getPortalTokenExpiresAt(), ""),
                    "deleted", account.isDeleted()
                ))
                .toList()
        );
    }

    private String tokenHashPrefix(String tokenHash) {
        return tokenHash == null ? null : tokenHash.substring(0, Math.min(8, tokenHash.length()));
    }

    private <T> T withPortalContext(SupplierPortalAccount account, Supplier<T> action) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        var previousAuth = SecurityContextHolder.getContext().getAuthentication();
        TenantContext.setTenant(account.getTenantId());
        CompanyContext.setCompany(account.getCompanyId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("supplier:" + account.getLoginEmail(), null, List.of())
        );
        try {
            return action.get();
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
            SecurityContextHolder.getContext().setAuthentication(previousAuth);
        }
    }

    private <T> Page<T> page(List<T> content, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return new PageImpl<>(content);
        }
        int from = Math.min((int) pageable.getOffset(), content.size());
        int to = Math.min(from + pageable.getPageSize(), content.size());
        return new PageImpl<>(content.subList(from, to), pageable, content.size());
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash supplier portal token", ex);
        }
    }
}
