package com.enterprise.mm.supplier.po.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.PoDispatchMailProperties;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.enums.NotificationEntityType;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.NotificationService;
import com.enterprise.mm.service.SupplierMagicLinkService;
import com.enterprise.mm.service.rfq.RfqDeliveryMagicLinkIssuer;
import com.enterprise.mm.service.rfq.RfqEmailTransport;
import com.enterprise.mm.service.rfq.RfqOutboundEmail;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks up {@code QUEUED} {@link SupplierPoDispatch} rows, issues the PO-scoped magic
 * link, sends the portal email, and transitions to {@code SENT} (with {@code sentAt})
 * on success or records {@code lastDeliveryError} / increments {@code deliveryAttempts}
 * and stays {@code QUEUED} for retry (eventually {@code FAILED} after
 * {@code maxAutomaticRetries}, mirroring {@code RfqDeliveryService}'s
 * attempt-threshold retry policy -- same shape, no new retry policy invented).
 * Mirrors {@code RfqDeliveryService.scheduledDispatch()}'s config-property + polling
 * pattern exactly; reuses the same {@link RfqEmailTransport} / {@link RfqOutboundEmail}
 * transport the RFQ mailer uses, with new PO-specific template content.
 */
@Service
@RequiredArgsConstructor
public class SupplierPoDispatchMailService {
    private static final Logger log = LoggerFactory.getLogger(SupplierPoDispatchMailService.class);

    private final SupplierPoDispatchRepository dispatchRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierMagicLinkService magicLinkService;
    private final RfqEmailTransport transport;
    private final RfqMailProperties mailProperties;
    private final PoDispatchMailProperties properties;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectProvider<SupplierPoDispatchMailService> selfProvider;

    @Scheduled(fixedDelayString = "${mm.po-dispatch-mail.poll-delay-ms:30000}")
    public void scheduledDispatch() {
        if (properties.isEnabled()) {
            dispatchDue(properties.getBatchSize());
        }
    }

    public int dispatchDue(int batchSize) {
        List<SupplierPoDispatch> due = dispatchRepository.findByDispatchStatusAndIsDeletedFalseOrderByQueuedAtAsc(
            "QUEUED", PageRequest.of(0, Math.max(1, batchSize))
        );
        int attempted = 0;
        for (SupplierPoDispatch dispatch : due) {
            String previousTenant = TenantContext.currentTenant();
            UUID previousCompany = CompanyContext.currentCompany();
            TenantContext.setTenant(dispatch.getTenantId());
            CompanyContext.setCompany(dispatch.getCompanyId());
            try {
                selfProvider.getObject().dispatchOne(dispatch.getId());
                attempted++;
            } catch (Exception exception) {
                attempted++;
                log.warn("Supplier PO dispatch {} could not be mailed", dispatch.getId(), exception);
            } finally {
                TenantContext.setTenant(previousTenant);
                CompanyContext.setCompany(previousCompany);
            }
        }
        return attempted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchOne(UUID dispatchId) {
        SupplierPoDispatch dispatch = dispatchRepository.findByIdAndIsDeletedFalse(dispatchId).orElse(null);
        if (dispatch == null || !"QUEUED".equals(dispatch.getDispatchStatus())) {
            return;
        }
        if (dispatch.getRecipientEmail() == null || dispatch.getRecipientEmail().isBlank()) {
            markFailed(dispatch, "No supplier portal account / recipient email on file");
            return;
        }
        try {
            RfqDeliveryMagicLinkIssuer.IssuedLink link = magicLinkService.issueForPurchaseOrderDispatch(dispatch);
            PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdAndIsDeletedFalse(dispatch.getPurchaseOrderId()).orElse(null);
            RfqOutboundEmail email = buildEmail(dispatch, purchaseOrder, link.url().toString());
            RfqEmailTransport.SendResult result = transport.send(email);
            markSent(dispatch, result == null ? null : result.providerMessageId());
        } catch (Exception exception) {
            markFailed(dispatch, truncate(exception.getMessage()));
        }
    }

    private RfqOutboundEmail buildEmail(SupplierPoDispatch dispatch, PurchaseOrder purchaseOrder, String magicLinkUrl) {
        String poCode = purchaseOrder == null ? dispatch.getCode() : purchaseOrder.getCode();
        String subject = "New purchase order " + poCode + " -- action required in the supplier portal";
        String html = "<p>You have received purchase order <b>" + poCode + "</b>.</p>"
            + "<p><a href=\"" + magicLinkUrl + "\">Open in the supplier portal</a> to review and respond"
            + (dispatch.getResponseDueDate() == null ? "." : " by " + dispatch.getResponseDueDate() + ".") + "</p>";
        String text = "Purchase order " + poCode + " requires your response. Open: " + magicLinkUrl
            + (dispatch.getResponseDueDate() == null ? "" : " (due " + dispatch.getResponseDueDate() + ")");
        return new RfqOutboundEmail(mailProperties.getFrom(), dispatch.getRecipientEmail(), mailProperties.getReplyTo(), subject, html, text, List.of());
    }

    private void markSent(SupplierPoDispatch dispatch, String providerMessageId) {
        dispatch.setDispatchStatus("SENT");
        dispatch.setSentAt(LocalDateTime.now());
        dispatch.setProviderMessageId(providerMessageId);
        dispatch.setLastDeliveryError(null);
        dispatchRepository.save(dispatch);
        auditService.log("PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_DISPATCH_SENT", dispatch.getRecipientEmail(), null, null);
        notificationService.createForRole(
            RoleName.PROCUREMENT_MANAGER,
            "Supplier PO dispatch sent",
            "Purchase order " + dispatch.getCode() + " was sent to the supplier portal.",
            "PO dispatch sent " + dispatch.getCode(),
            NotificationEntityType.PURCHASE_ORDER.name(),
            dispatch.getPurchaseOrderId()
        );
    }

    private void markFailed(SupplierPoDispatch dispatch, String errorMessage) {
        int attempts = dispatch.getDeliveryAttempts() + 1;
        dispatch.setDeliveryAttempts(attempts);
        dispatch.setLastDeliveryError(errorMessage);
        boolean exhausted = attempts >= properties.getMaxAutomaticRetries();
        if (exhausted) {
            dispatch.setDispatchStatus("FAILED");
        }
        dispatchRepository.save(dispatch);
        auditService.log("PurchaseOrder", dispatch.getPurchaseOrderId(), "PO_SUPPLIER_DISPATCH_FAILED", errorMessage, null, null);
        if (exhausted) {
            notificationService.createForRole(
                RoleName.PROCUREMENT_MANAGER,
                "Supplier PO dispatch failed",
                "Purchase order " + dispatch.getCode() + " could not be delivered to the supplier after "
                    + attempts + " attempts: " + errorMessage,
                "PO dispatch failed " + dispatch.getCode(),
                NotificationEntityType.PURCHASE_ORDER.name(),
                dispatch.getPurchaseOrderId()
            );
        }
    }

    private String truncate(String value) {
        String safe = value == null || value.isBlank() ? "Unknown mail delivery error" : value.trim();
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }
}
