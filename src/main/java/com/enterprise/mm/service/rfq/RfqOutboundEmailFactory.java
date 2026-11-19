package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RfqOutboundEmailFactory implements RfqDeliveryService.OutboundEmailFactory {
    private final ObjectMapper objectMapper;
    private final RfqEmailRenderer emailRenderer;
    private final AttachmentRepository attachmentRepository;
    private final List<AttachmentStorage> storages;
    private final RfqMailProperties properties;



    @Override
    public RfqOutboundEmail create(RfqInvitationDelivery delivery, URI magicLink) {
        if (delivery == null || delivery.getPublishedVersion() == null) {
            throw new BusinessException("RFQ published version is required for delivery");
        }
        RfqPublishedVersion version = delivery.getPublishedVersion();
        RfqPackageSnapshot snapshot = snapshot(version);
        SupplierRecipient recipient = snapshot.suppliers().stream()
            .filter(candidate -> candidate.supplierId().equals(delivery.getSupplierId()))
            .findFirst()
            .orElseGet(() -> new SupplierRecipient(
                delivery.getSupplierId(),
                delivery.getRecipientName(),
                delivery.getRecipientName(),
                delivery.getRecipientEmail()
            ));
        SupplierRecipient actualRecipient = new SupplierRecipient(
            recipient.supplierId(),
            recipient.supplierName(),
            recipient.contactName(),
            delivery.getRecipientEmail()
        );
        RfqEmailRenderer.RenderedEmail rendered = emailRenderer.render(snapshot, actualRecipient, magicLink);
        return new RfqOutboundEmail(
            properties.getFrom(),
            delivery.getRecipientEmail(),
            properties.getReplyTo(),
            rendered.subject(),
            rendered.html(),
            rendered.text(),
            attachments(version, snapshot)
        );
    }

    @Override
    public RfqOutboundEmail createRenewal(RfqInvitationDelivery delivery, URI magicLink) {
        if (delivery == null) {
            throw new BusinessException("RFQ delivery is required for portal renewal");
        }
        String link = magicLink == null ? "" : magicLink.toString();
        String recipientName = delivery.getRecipientName() == null || delivery.getRecipientName().isBlank()
            ? "Supplier"
            : delivery.getRecipientName().trim();
        String html = "<p>Hello " + escape(recipientName) + ",</p>"
            + "<p>A fresh supplier portal login link was requested for your account.</p>"
            + "<p><a href=\"" + escape(link) + "\">Open supplier portal</a></p>"
            + "<p>This link is valid for 24 hours. If you did not request it, you can ignore this email.</p>";
        String text = "Hello " + recipientName + ",\n\n"
            + "A fresh supplier portal login link was requested for your account.\n"
            + "Open supplier portal: " + link + "\n\n"
            + "This link is valid for 24 hours. If you did not request it, you can ignore this email.";
        return new RfqOutboundEmail(
            properties.getFrom(),
            delivery.getRecipientEmail(),
            properties.getReplyTo(),
            "Supplier portal login link",
            html,
            text,
            List.of()
        );
    }

    private RfqPackageSnapshot snapshot(RfqPublishedVersion version) {
        try {
            return objectMapper.readValue(version.getSnapshotJson(), RfqPackageSnapshot.class);
        } catch (Exception exception) {
            throw new BusinessException("Cannot read published RFQ package snapshot");
        }
    }

    private List<RfqOutboundEmail.Attachment> attachments(
        RfqPublishedVersion version,
        RfqPackageSnapshot snapshot
    ) {
        Set<UUID> attachmentIds = new LinkedHashSet<>();
        if (version.getPdfAttachmentId() != null) {
            attachmentIds.add(version.getPdfAttachmentId());
        }
        if (version.getExcelAttachmentId() != null) {
            attachmentIds.add(version.getExcelAttachmentId());
        }
        if (snapshot.documents() != null) {
            snapshot.documents().forEach(document -> attachmentIds.add(document.attachmentId()));
        }
        List<RfqOutboundEmail.Attachment> result = new ArrayList<>();
        for (UUID attachmentId : attachmentIds) {
            if (attachmentId != null) {
                result.add(readAttachment(attachmentId));
            }
        }
        return List.copyOf(result);
    }

    private RfqOutboundEmail.Attachment readAttachment(UUID attachmentId) {
        Attachment attachment = attachmentRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                attachmentId
            )
            .orElseThrow(() -> new BusinessException("RFQ attachment is unavailable: " + attachmentId));
        AttachmentStorage storage = storages.stream()
            .filter(candidate -> candidate.supports(attachment.getStorageProvider()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "Unsupported RFQ attachment storage provider: " + attachment.getStorageProvider()
            ));
        try (InputStream input = storage.read(attachment.getStorageKey()).getInputStream()) {
            return new RfqOutboundEmail.Attachment(
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                input.readAllBytes()
            );
        } catch (Exception exception) {
            throw new BusinessException("Cannot read RFQ attachment: " + attachment.getOriginalFileName());
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
