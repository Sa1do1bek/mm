package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.SupplierPoCommitment;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPoReconfirmationNotification;
import com.enterprise.mm.entity.SupplierPoResponse;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPoReconfirmationNotificationRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SupplierMagicLinkService;
import com.enterprise.mm.service.rfq.RfqDeliveryMagicLinkIssuer;
import com.enterprise.mm.service.rfq.RfqEmailTransport;
import com.enterprise.mm.service.rfq.RfqOutboundEmail;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierPoReconfirmationNotificationService {
    public static final String EVENT_TYPE = "SUPPLIER_RECONFIRMATION_REQUESTED";
    private static final int MAX_ATTEMPTS = 5;

    private final SupplierPoReconfirmationNotificationRepository notificationRepository;
    private final SupplierPortalAccountRepository portalAccountRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupplierPoDispatchRepository dispatchRepository;
    private final SupplierMagicLinkService magicLinkService;
    private final RfqEmailTransport emailTransport;
    private final RfqMailProperties mailProperties;
    private final AuditService auditService;
    private final ObjectProvider<SupplierPoReconfirmationNotificationService> selfProvider;

    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierPoReconfirmationNotification queue(
        SupplierPoResponse response,
        SupplierPoCommitment cancelledCommitment,
        PurchaseOrder purchaseOrder,
        String reason,
        String requestedBy
    ) {
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        return notificationRepository
            .findByTenantIdAndCompanyIdAndReconfirmationResponseIdAndEventTypeAndIsDeletedFalse(
                tenantId,
                companyId,
                response.getId(),
                EVENT_TYPE
            )
            .orElseGet(() -> createQueued(response, cancelledCommitment, purchaseOrder, reason, requestedBy, tenantId, companyId));
    }

    private SupplierPoReconfirmationNotification createQueued(
        SupplierPoResponse response,
        SupplierPoCommitment cancelledCommitment,
        PurchaseOrder purchaseOrder,
        String reason,
        String requestedBy,
        String tenantId,
        UUID companyId
    ) {
        SupplierPortalAccount account = resolvePortalAccount(response, tenantId, companyId);
        PurchaseOrderItem item = purchaseOrderItemRepository.findByIdAndIsDeletedFalse(cancelledCommitment.getPurchaseOrderItemId())
            .filter(found -> tenantId.equals(found.getTenantId()))
            .filter(found -> Objects.equals(companyId, found.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Purchase order item is required for reconfirmation notification"));

        SupplierPoReconfirmationNotification notification = new SupplierPoReconfirmationNotification();
        notification.setCode("PO-RECONFIRM-NOTIFY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        notification.setName("Supplier reconfirmation notification " + purchaseOrder.getCode());
        notification.setStatus("QUEUED");
        notification.setEventType(EVENT_TYPE);
        notification.setPurchaseOrderId(purchaseOrder.getId());
        notification.setPurchaseOrderItemId(cancelledCommitment.getPurchaseOrderItemId());
        notification.setCommitmentId(cancelledCommitment.getId());
        notification.setReconfirmationResponseId(response.getId());
        notification.setSupplierId(response.getSupplierId());
        notification.setPortalAccountId(account.getId());
        notification.setRecipientEmail(account.getLoginEmail());
        notification.setPoCode(purchaseOrder.getCode());
        notification.setLineName(item.getName());
        notification.setReason(reason);
        notification.setRequestedBy(requestedBy);
        notification.setQueuedAt(LocalDateTime.now());
        SupplierPoReconfirmationNotification saved = notificationRepository.save(notification);
        auditService.logInCurrentTransaction(
            "PurchaseOrder",
            purchaseOrder.getId(),
            EVENT_TYPE,
            cancelledCommitment.getId().toString(),
            null,
            null
        );
        return saved;
    }

    private SupplierPortalAccount resolvePortalAccount(SupplierPoResponse response, String tenantId, UUID companyId) {
        if (response.getPortalAccountId() != null) {
            var direct = portalAccountRepository.findByIdAndIsDeletedFalse(response.getPortalAccountId())
                .filter(found -> tenantId.equals(found.getTenantId()))
                .filter(found -> Objects.equals(companyId, found.getCompanyId()))
                .filter(found -> Objects.equals(response.getSupplierId(), found.getSupplierId()))
                .filter(this::isInvitedOrActive);
            if (direct.isPresent()) {
                return direct.get();
            }
        }
        return portalAccountRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                tenantId,
                companyId,
                response.getSupplierId()
            )
            .stream()
            .filter(this::isInvitedOrActive)
            .min(this::portalAccountOrder)
            .orElseThrow(() -> new BusinessException("Supplier portal account is required for reconfirmation notification"));
    }

    @Scheduled(fixedDelayString = "${mm.po-reconfirmation-notification.poll-delay-ms:30000}")
    public int scheduledDispatch() {
        return dispatchDue(50);
    }

    public int dispatchDue(int batchSize) {
        List<SupplierPoReconfirmationNotification> due = notificationRepository.findDue(
            List.of("QUEUED", "RETRY"),
            LocalDateTime.now(),
            PageRequest.of(0, Math.max(1, batchSize))
        );
        int attempted = 0;
        for (SupplierPoReconfirmationNotification notification : due) {
            String previousTenant = TenantContext.currentTenant();
            UUID previousCompany = CompanyContext.currentCompany();
            TenantContext.setTenant(notification.getTenantId());
            CompanyContext.setCompany(notification.getCompanyId());
            try {
                selfProvider.getObject().dispatchOne(notification.getId());
                attempted++;
            } finally {
                TenantContext.setTenant(previousTenant);
                CompanyContext.setCompany(previousCompany);
            }
        }
        return attempted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchOne(UUID notificationId) {
        SupplierPoReconfirmationNotification notification = notificationRepository.findByIdAndIsDeletedFalse(notificationId)
            .orElse(null);
        if (notification == null || "SENT".equals(notification.getStatus())) {
            return;
        }
        try {
            SupplierPoDispatch dispatch = dispatchRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndCurrentTrueAndIsDeletedFalse(
                    notification.getTenantId(),
                    notification.getCompanyId(),
                    notification.getPurchaseOrderId()
                )
                .orElseThrow(() -> new BusinessException("Current supplier PO dispatch not found"));
            RfqDeliveryMagicLinkIssuer.IssuedLink link = magicLinkService.issueForPurchaseOrderDispatch(dispatch);
            RfqEmailTransport.SendResult result = emailTransport.send(buildEmail(notification, link.url().toString()));
            notification.setStatus("SENT");
            notification.setSentAt(LocalDateTime.now());
            notification.setProviderMessageId(result == null ? null : result.providerMessageId());
            notification.setLastError(null);
            notification.setNextRetryAt(null);
            notificationRepository.save(notification);
            auditService.log(
                "PurchaseOrder",
                notification.getPurchaseOrderId(),
                EVENT_TYPE + "_SENT",
                notification.getRecipientEmail(),
                null,
                null
            );
        } catch (Exception exception) {
            markFailed(notification, exception);
        }
    }

    private RfqOutboundEmail buildEmail(SupplierPoReconfirmationNotification notification, String magicLinkUrl) {
        String poCode = notification.getPoCode() == null ? notification.getPurchaseOrderId().toString() : notification.getPoCode();
        String subject = "Supplier reconfirmation required for purchase order " + poCode;
        String lineText = notification.getLineName() == null || notification.getLineName().isBlank()
            ? "the affected purchase order line"
            : notification.getLineName();
        String reasonText = notification.getReason() == null || notification.getReason().isBlank()
            ? ""
            : "<p>Buyer reason: " + escape(notification.getReason()) + "</p>";
        String html = "<p>The existing supplier commitment for <b>" + escape(poCode) + "</b> was cancelled.</p>"
            + "<p>A new supplier confirmation is required for " + escape(lineText) + ".</p>"
            + reasonText
            + "<p><a href=\"" + magicLinkUrl + "\">Open in the supplier portal</a> to review and submit the reconfirmation.</p>";
        String text = "The existing supplier commitment for purchase order " + poCode
            + " was cancelled. A new supplier confirmation is required for " + lineText + "."
            + (notification.getReason() == null || notification.getReason().isBlank() ? "" : " Buyer reason: " + notification.getReason())
            + " Open: " + magicLinkUrl;
        return new RfqOutboundEmail(
            mailProperties.getFrom(),
            notification.getRecipientEmail(),
            mailProperties.getReplyTo(),
            subject,
            html,
            text,
            List.of()
        );
    }

    private void markFailed(SupplierPoReconfirmationNotification notification, Exception exception) {
        int attempts = notification.getAttempts() + 1;
        notification.setAttempts(attempts);
        notification.setLastError(safeMessage(exception));
        if (attempts >= MAX_ATTEMPTS) {
            notification.setStatus("DEAD");
            notification.setNextRetryAt(null);
        } else {
            notification.setStatus("RETRY");
            notification.setNextRetryAt(LocalDateTime.now().plusMinutes(Math.min(60, attempts * 5L)));
        }
        notificationRepository.save(notification);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private boolean isInvitedOrActive(SupplierPortalAccount account) {
        return SupplierPortalStatus.INVITED.name().equals(account.getStatus())
            || SupplierPortalStatus.ACTIVE.name().equals(account.getStatus());
    }

    private int portalAccountOrder(SupplierPortalAccount left, SupplierPortalAccount right) {
        int leftRank = portalAccountRank(left);
        int rightRank = portalAccountRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        LocalDateTime leftCreated = left.getCreatedAt();
        LocalDateTime rightCreated = right.getCreatedAt();
        if (leftCreated == null && rightCreated == null) {
            return 0;
        }
        if (leftCreated == null) {
            return 1;
        }
        if (rightCreated == null) {
            return -1;
        }
        return leftCreated.compareTo(rightCreated);
    }

    private int portalAccountRank(SupplierPortalAccount account) {
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

    private String escape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
