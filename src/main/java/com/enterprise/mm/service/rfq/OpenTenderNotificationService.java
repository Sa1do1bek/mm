package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import jakarta.mail.internet.InternetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenTenderNotificationService {
    private final RFQRepository rfqRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final SupplierRepository supplierRepository;
    private final RfqEmailTransport emailTransport;
    private final RfqMailProperties mailProperties;
    private final AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyPublished(String tenantId, UUID companyId, UUID rfqId, UUID publishedVersionId) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        try {
            RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(rfqId)
                .filter(value -> tenantId.equals(value.getTenantId()))
                .filter(value -> companyId.equals(value.getCompanyId()))
                .filter(RfqSupplierAccessPolicy::usesOpenSupplierAccess)
                .orElse(null);
            if (rfq == null) {
                return;
            }
            RfqPublishedVersion version = versionRepository
                .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenantId, companyId, publishedVersionId)
                .filter(value -> rfqId.equals(value.getRfqId()))
                .orElse(null);
            if (version == null) {
                return;
            }
            if (!mailReady()) {
                auditService.log("RFQ", rfqId, "OPEN_TENDER_NOTIFICATION_SKIPPED", "RFQ mail is not configured");
                return;
            }
            int sent = 0;
            Set<String> seenEmails = new LinkedHashSet<>();
            for (Supplier supplier : supplierRepository.findByTenantIdAndCompanyIdAndStatusAndIsDeletedFalse(
                tenantId, companyId, "ACTIVE")) {
                String email = normalizeEmail(supplier.getEmail());
                if (email == null || !seenEmails.add(email)) {
                    continue;
                }
                try {
                    emailTransport.send(email(rfq, version, supplier, email));
                    sent++;
                } catch (Exception exception) {
                    log.warn("Open tender notification failed for RFQ {} supplier {}", rfqId, supplier.getId(), exception);
                    auditService.log(
                        "RFQ",
                        rfqId,
                        "OPEN_TENDER_NOTIFICATION_FAILED",
                        "supplier=" + supplier.getId() + ", email=" + email
                    );
                }
            }
            auditService.log("RFQ", rfqId, "OPEN_TENDER_NOTIFIED", "sent=" + sent);
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private RfqOutboundEmail email(RFQ rfq, RfqPublishedVersion version, Supplier supplier, String recipientEmail) {
        String link = portalLink(rfq.getId());
        String title = firstNonBlank(rfq.getName(), rfq.getCode());
        String subject = "Open procurement opportunity published: " + title;
        String supplierName = firstNonBlank(supplier.getLegalName(), supplier.getName());
        String text = "Dear " + firstNonBlank(supplierName, "supplier") + ",\n\n"
            + "A new open procurement opportunity is available in the supplier portal.\n"
            + "RFQ: " + title + "\n"
            + "Due date: " + rfq.getDueDate() + "\n"
            + "Version: " + version.getVersionNumber() + "\n"
            + "Portal: " + link + "\n\n"
            + "This is a notification only; no invitation is required for eligible active suppliers.";
        String html = "<p>Dear " + escapeHtml(firstNonBlank(supplierName, "supplier")) + ",</p>"
            + "<p>A new open procurement opportunity is available in the supplier portal.</p>"
            + "<p><strong>RFQ:</strong> " + escapeHtml(title) + "<br>"
            + "<strong>Due date:</strong> " + rfq.getDueDate() + "<br>"
            + "<strong>Version:</strong> " + version.getVersionNumber() + "</p>"
            + "<p><a href=\"" + escapeHtml(link) + "\">Open supplier portal</a></p>"
            + "<p>This is a notification only; no invitation is required for eligible active suppliers.</p>";
        return new RfqOutboundEmail(
            mailProperties.getFrom(),
            recipientEmail,
            mailProperties.getReplyTo(),
            subject,
            html,
            text,
            List.of()
        );
    }

    private boolean mailReady() {
        return mailProperties.isEnabled()
            && normalizeEmail(mailProperties.getFrom()) != null
            && normalizeEmail(mailProperties.getReplyTo()) != null;
    }

    private String portalLink(UUID rfqId) {
        String base = mailProperties.getPortalBaseUrl() == null ? "" : mailProperties.getPortalBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/rfq/" + rfqId;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            InternetAddress address = new InternetAddress(normalized, true);
            address.validate();
            return normalized;
        } catch (Exception exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
