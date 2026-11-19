package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.PurchaseOrderRevision;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.PurchaseOrderRevisionRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates (or reuses) the single immutable {@link SupplierPoDispatch} for a given
 * approved {@link PurchaseOrder} version. This is the method
 * {@code PurchaseOrderService.send()} calls; it never sends email/outbox itself
 * (Phase B owns that) -- it only guarantees the dispatch row + immutable snapshot
 * exist, in QUEUED state, ready for the mailer.
 *
 * <p>"PO version" is the JPA {@code @Version} value on {@link PurchaseOrder} at the
 * moment of dispatch (see the DB unique constraint on
 * {@code (purchase_order_id, purchase_order_version)}). This was chosen over
 * {@link PurchaseOrderRevision#getRevisionNumber()} because a PO can be sent for the
 * very first time with zero revisions recorded (revisions are only written by
 * {@code PurchaseOrderService.update()|createRevision}, not by approval/send), so
 * revisionNumber cannot reliably identify "the version that was just approved and is
 * being sent". {@code @Version} is always present, monotonically increases on every
 * persisted change to the PO row, and -- because no code path mutates a PO between
 * APPROVED and SENT other than {@code send()} itself -- uniquely and stably
 * identifies the exact commercial content dispatched.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoDispatchService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final PurchaseOrderRevisionRepository purchaseOrderRevisionRepository;
    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierPortalAccountRepository portalAccountRepository;
    private final SupplierPoSnapshotService snapshotService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public record DispatchOutcome(SupplierPoDispatch dispatch, boolean newlyCreated) {
    }

    /** Read-only lookup of the current (is_current=true, non-deleted) dispatch for a
     * PO, if any. Used by {@code PurchaseOrderService.send()} to decide whether a
     * SENT-status resend is a legitimate idempotent replay (current dispatch already
     * matches the PO's current version) before mutating anything. */
    @Transactional(readOnly = true)
    public java.util.Optional<SupplierPoDispatch> findCurrent(UUID purchaseOrderId) {
        return dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderId
        );
    }

    /** True when no dispatch row (of any status) has ever existed for this PO. Used to
     * compute the {@code legacy} flag on {@code PurchaseOrderResponse}. */
    @Transactional(readOnly = true)
    public boolean neverDispatched(UUID purchaseOrderId) {
        return dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByPurchaseOrderVersionDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderId
            )
            .isEmpty();
    }

    @Transactional
    public DispatchOutcome createOrReuseDispatch(UUID purchaseOrderId) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .orElseThrow(() -> new NotFoundException("Purchase order not found: " + purchaseOrderId));
        Long currentVersion = purchaseOrder.getVersion();

        var existingForVersion = dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndPurchaseOrderVersionAndIsDeletedFalse(
                tenantId, companyId, purchaseOrderId, currentVersion
            );
        if (existingForVersion.isPresent()) {
            return new DispatchOutcome(existingForVersion.get(), false);
        }

        List<PurchaseOrderItem> items = purchaseOrderItemRepository
            .findByTenantIdAndPurchaseOrder_IdAndIsDeletedFalse(tenantId, purchaseOrderId);
        if (items.isEmpty()) {
            throw new BusinessException("Purchase order has no items to dispatch to the supplier");
        }

        // A new PO version supersedes the previous "current" pointer. Same-version
        // resend never reaches this branch (handled by the reuse path above), so this
        // never touches rows for the version we are about to create.
        dispatchRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(tenantId, companyId, purchaseOrderId)
            .ifPresent(previous -> {
                previous.setCurrent(false);
                dispatchRepository.save(previous);
            });

        SupplierPoSnapshotService.Snapshot snapshot = snapshotService.build(purchaseOrder, items);
        PurchaseOrderRevision latestRevision = purchaseOrderRevisionRepository
            .findByTenantIdAndCompanyIdAndPurchaseOrder_IdAndIsDeletedFalseOrderByRevisionNumberDesc(tenantId, companyId, purchaseOrderId)
            .stream()
            .findFirst()
            .orElse(null);
        SupplierPortalAccount portalAccount = portalAccountRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId, companyId, purchaseOrder.getSupplierId())
            .stream()
            .filter(this::isPortalDispatchAccount)
            .min(this::portalDispatchAccountOrder)
            .orElse(null);

        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setCode("PO-DISPATCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        // Prefer PO code: multilingual workflow names from long PO titles exceed varchar(240)
        // on older schemas and still keep dispatch names short/stable.
        String nameSource = purchaseOrder.getCode() == null || purchaseOrder.getCode().isBlank()
            ? purchaseOrder.getId().toString()
            : purchaseOrder.getCode();
        dispatch.setName(BusinessNameGenerator.workflowDocumentName("SupplierPoDispatch", nameSource));
        dispatch.setPurchaseOrderId(purchaseOrderId);
        dispatch.setPurchaseOrderVersion(currentVersion);
        dispatch.setPurchaseOrderRevisionId(latestRevision == null ? null : latestRevision.getId());
        dispatch.setSupplierId(purchaseOrder.getSupplierId());
        dispatch.setPortalAccountId(portalAccount == null ? null : portalAccount.getId());
        dispatch.setRecipientEmail(portalAccount == null ? null : portalAccount.getLoginEmail());
        dispatch.setDispatchStatus("QUEUED");
        dispatch.setQueuedAt(LocalDateTime.now());
        dispatch.setSnapshotJson(snapshot.json());
        dispatch.setSnapshotHash(snapshot.hash());
        dispatch.setCurrent(true);
        SupplierPoDispatch saved = dispatchRepository.save(dispatch);
        auditService.log("PurchaseOrder", purchaseOrderId, "PO_SUPPLIER_DISPATCH_QUEUED", purchaseOrder.getCode(), null, null);
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Supplier PO dispatch queued",
            "Purchase order " + purchaseOrder.getCode() + " was queued for supplier dispatch.",
            "PO dispatch queued " + purchaseOrder.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            purchaseOrderId
        );
        return new DispatchOutcome(saved, true);
    }

    private boolean isPortalDispatchAccount(SupplierPortalAccount account) {
        return SupplierPortalStatus.INVITED.name().equals(account.getStatus())
            || SupplierPortalStatus.ACTIVE.name().equals(account.getStatus());
    }

    private int portalDispatchAccountOrder(SupplierPortalAccount left, SupplierPortalAccount right) {
        int leftRank = portalDispatchAccountRank(left);
        int rightRank = portalDispatchAccountRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        if (left.getCreatedAt() == null && right.getCreatedAt() == null) {
            return 0;
        }
        if (left.getCreatedAt() == null) {
            return 1;
        }
        if (right.getCreatedAt() == null) {
            return -1;
        }
        return left.getCreatedAt().compareTo(right.getCreatedAt());
    }

    private int portalDispatchAccountRank(SupplierPortalAccount account) {
        boolean admin = SupplierPortalRole.SUPPLIER_ADMIN.name().equals(account.getPortalRole());
        boolean active = SupplierPortalStatus.ACTIVE.name().equals(account.getStatus());
        if (admin && active) {
            return 0;
        }
        if (admin) {
            return 1;
        }
        return active ? 2 : 3;
    }

    /** Full dispatch history (current + superseded + cancelled) for the internal
     * "Supplier collaboration" tab. */
    @Transactional(readOnly = true)
    public java.util.List<SupplierPoDispatch> history(UUID purchaseOrderId) {
        return dispatchRepository.findByTenantIdAndCompanyIdAndPurchaseOrderIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), purchaseOrderId
        );
    }

    /** Buyer-initiated resend: re-queues the current dispatch's email without creating
     * a new dispatch row or touching the PO version -- used when the supplier lost the
     * original email. Does nothing to a SUPERSEDED/CANCELLED dispatch (not current). */
    @Transactional
    public SupplierPoDispatch resend(UUID dispatchId, Long expectedVersion, String reason) {
        SupplierPoDispatch dispatch = dispatchRepository.findByIdAndIsDeletedFalse(dispatchId)
            .filter(found -> TenantContext.currentTenant().equals(found.getTenantId())
                && java.util.Objects.equals(CompanyContext.currentCompany(), found.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier PO dispatch not found: " + dispatchId));
        if (expectedVersion != null && !expectedVersion.equals(dispatch.getVersion())) {
            throw new com.enterprise.mm.error.ConflictException("Dispatch changed by another user; refresh and retry");
        }
        if (!dispatch.isCurrent() || "SUPERSEDED".equals(dispatch.getDispatchStatus()) || "CANCELLED".equals(dispatch.getDispatchStatus())) {
            throw new BusinessException("Only the current, non-superseded dispatch can be resent");
        }
        dispatch.setDispatchStatus("QUEUED");
        dispatch.setQueuedAt(LocalDateTime.now());
        dispatch.setLastDeliveryError(null);
        SupplierPoDispatch saved = dispatchRepository.save(dispatch);
        auditService.log(
            "PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_DISPATCH_QUEUED",
            (reason == null || reason.isBlank() ? "Buyer resend" : reason.trim()), null, null
        );
        return saved;
    }
}
