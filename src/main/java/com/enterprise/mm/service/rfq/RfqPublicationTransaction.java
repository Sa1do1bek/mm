package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SupplierPortalAccountService;
import com.enterprise.mm.service.rfq.RfqArtifactStagingService.PromotedPackage;
import com.enterprise.mm.service.rfq.RfqPackageSnapshot.SupplierRecipient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@RequiredArgsConstructor
public class RfqPublicationTransaction {
    private static final Logger LOGGER = LoggerFactory.getLogger(RfqPublicationTransaction.class);
    private static final Set<String> PUBLISHABLE_STATUSES = Set.of("DRAFT", "AMENDMENT_DRAFT");

    private final RFQRepository rfqRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final SupplierRepository supplierRepository;
    private final RfqPublicationValidator validator;
    private final RfqArtifactStagingService stagingService;
    private final SupplierPortalAccountService portalAccountService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<RfqDeliveryService> deliveryServiceProvider;
    private RfqMetrics metrics;



    @Autowired
    void setMetrics(RfqMetrics metrics) {
        this.metrics = metrics;
    }

    @Transactional
    public PublishRfqResponse commit(RfqPublicationService.PublicationCandidate candidate) {
        RFQ rfq = rfqRepository.lockScopedById(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            candidate.rfqId()
        ).orElseThrow(() -> new NotFoundException("RFQ not found: " + candidate.rfqId()));
        long actualVersion = rfq.getVersion() == null ? 0L : rfq.getVersion();
        if (actualVersion != candidate.expectedVersion()) {
            throw new BusinessException("RFQ changed; refresh the publication preview and try again");
        }
        if (!PUBLISHABLE_STATUSES.contains(rfq.getStatus())) {
            throw new BusinessException(
                "RFQ must be a draft before publication", "rfq.publish.not_draft"
            );
        }
        validator.revalidateLocked(rfq, candidate.snapshot());

        LocalDateTime now = LocalDateTime.now(clock);
        String publisher = currentUsername();
        String amendmentReason = "AMENDMENT_DRAFT".equals(rfq.getStatus())
            ? rfq.getPendingAmendmentReason()
            : null;
        rfq.setStatus("PUBLISHING");
        int number = Math.toIntExact(versionRepository.countByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfq.getId()
        ) + 1L);

        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setCode(rfq.getCode() + "-V" + number);
        version.setName(rfq.getName() + " version " + number);
        version.setStatus("ACTIVE");
        version.setRfqId(rfq.getId());
        version.setVersionNumber(number);
        version.setSnapshotJson(snapshotJson(candidate.snapshot()));
        version.setPackageLanguage(candidate.snapshot().language());
        version.setTemplateVersion("rfq-package-v1");
        version.setPublishedAt(now);
        version.setPublishedBy(publisher);
        version.setAmendmentReason(amendmentReason);
        version = versionRepository.saveAndFlush(version);

        PromotedPackage artifacts = stagingService.promote(candidate.staged(), version.getId());
        version.setPdfAttachmentId(artifacts.pdfAttachmentId());
        version.setExcelAttachmentId(artifacts.excelAttachmentId());
        versionRepository.save(version);

        boolean openSupplierAccess = usesOpenSupplierAccess(rfq);
        List<RfqSupplierInvitation> invitations = openSupplierAccess
            ? ensureOpenSupplierCommunicationInvitations(rfq)
            : invitationRepository.findActiveForUpdate(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getId()
            );
        Map<UUID, SupplierRecipient> recipients = new LinkedHashMap<>();
        candidate.snapshot().suppliers().forEach(recipient -> recipients.put(recipient.supplierId(), recipient));
        if (openSupplierAccess) {
            openSupplierRecipients(invitations).forEach(recipient -> recipients.put(recipient.supplierId(), recipient));
        }
        List<RfqInvitationDelivery> deliveries = new ArrayList<>();
        for (RfqSupplierInvitation invitation : invitations) {
            SupplierRecipient recipient = recipients.get(invitation.getSupplierId());
            if (recipient == null) {
                if (openSupplierAccess) {
                    continue;
                }
                throw new BusinessException("RFQ package is stale for invited supplier " + invitation.getSupplierId());
            }
            String recipientEmail = normalizeEmail(recipient.email());
            if (!validMailAddress(recipientEmail)) {
                LOGGER.warn("RFQ publication skipped supplier {} because recipient email is invalid", recipient.supplierId());
                continue;
            }
            try {
                portalAccountService.ensureForRfqInvitation(recipient.supplierId(), recipientEmail);
            } catch (BusinessException exception) {
                LOGGER.warn(
                    "RFQ publication skipped supplier {} because portal account provisioning failed: {}",
                    recipient.supplierId(),
                    exception.getMessage()
                );
                continue;
            }
            RfqInvitationDelivery delivery = new RfqInvitationDelivery();
            delivery.setCode("RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            delivery.setName("RFQ delivery to " + recipient.supplierName());
            delivery.setDeliveryStatus(RfqDeliveryStatus.PENDING);
            delivery.setInvitationId(invitation.getId());
            delivery.setPublishedVersionId(version.getId());
            delivery.setSupplierId(recipient.supplierId());
            delivery.setRecipientEmail(recipientEmail);
            delivery.setRecipientName(firstNonBlank(recipient.contactName(), recipient.supplierName()));
            delivery.setRetryCount(0);
            deliveries.add(delivery);
        }
        if (deliveries.isEmpty()) {
            throw new RfqPublicationValidationException(List.of(
                RfqPublicationValidator.issue("RFQ_NO_ELIGIBLE_SUPPLIERS", "suppliers", null)
            ));
        }
        deliveryRepository.saveAll(deliveries);

        rfq.setStatus("OPEN");
        rfq.setActivePublishedVersionId(version.getId());
        rfq.setPublishedAt(now);
        rfq.setPublishedBy(publisher);
        rfq.setPendingAmendmentReason(null);
        rfqRepository.saveAndFlush(rfq);
        auditService.log("RFQ", rfq.getId(), "PUBLISH", "version=" + number + ", deliveries=" + deliveries.size(), null, null);
        if (metrics != null) {
            metrics.publication();
            deliveries.forEach(ignored -> metrics.delivery(RfqDeliveryStatus.PENDING.name()));
        }
        registerDeliveryDispatch(rfq.getTenantId(), rfq.getCompanyId(), deliveries.stream()
            .map(RfqInvitationDelivery::getId)
            .filter(Objects::nonNull)
            .toList());
        return new PublishRfqResponse(rfq.getId(), version.getId(), number, deliveries.size(), "OPEN");
    }

    private List<RfqSupplierInvitation> ensureOpenSupplierCommunicationInvitations(RFQ rfq) {
        List<RfqSupplierInvitation> existing = invitationRepository
            .findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getId()
            )
            .stream()
            .filter(invitation -> "INVITED".equals(invitation.getStatus()))
            .toList();
        Set<UUID> existingSupplierIds = existing.stream()
            .map(RfqSupplierInvitation::getSupplierId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RfqSupplierInvitation> generated = supplierRepository
            .findActiveRfqSupplierCandidates(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream()
            .filter(supplier -> normalizeEmail(supplier.getEmail()) != null)
            .filter(supplier -> existingSupplierIds.add(supplier.getId()))
            .map(supplier -> openSupplierCommunicationInvitation(rfq, supplier))
            .toList();
        if (generated.isEmpty()) {
            return existing;
        }
        List<RfqSupplierInvitation> result = new ArrayList<>(existing);
        result.addAll(invitationRepository.saveAll(generated));
        return result;
    }

    private RfqSupplierInvitation openSupplierCommunicationInvitation(RFQ rfq, Supplier supplier) {
        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setCode("RFI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        invitation.setName("Open RFQ communication to " + firstNonBlank(supplier.getName(), "supplier"));
        invitation.setStatus("INVITED");
        invitation.setRfqId(rfq.getId());
        invitation.setSupplierId(supplier.getId());
        return invitation;
    }

    private List<SupplierRecipient> openSupplierRecipients(List<RfqSupplierInvitation> invitations) {
        if (invitations.isEmpty()) {
            return List.of();
        }
        Map<UUID, Supplier> suppliers = supplierRepository.findAllById(invitations.stream()
            .map(RfqSupplierInvitation::getSupplierId)
            .toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(Supplier::getId, supplier -> supplier));
        return invitations.stream()
            .map(RfqSupplierInvitation::getSupplierId)
            .map(suppliers::get)
            .filter(Objects::nonNull)
            .map(supplier -> new SupplierRecipient(
                supplier.getId(),
                firstNonBlank(supplier.getName(), supplier.getCode()),
                null,
                normalizeEmail(supplier.getEmail())
            ))
            .filter(recipient -> recipient.email() != null)
            .toList();
    }

    private void registerDeliveryDispatch(String tenantId, UUID companyId, List<UUID> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return;
        }
        Runnable action = () -> dispatchPublishedDeliveries(tenantId, companyId, deliveryIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void dispatchPublishedDeliveries(String tenantId, UUID companyId, List<UUID> deliveryIds) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        try {
            RfqDeliveryService deliveryService = deliveryServiceProvider.getObject();
            for (UUID deliveryId : deliveryIds) {
                try {
                    deliveryService.dispatchOne(deliveryId);
                } catch (Exception exception) {
                    LOGGER.warn("RFQ delivery {} could not be dispatched after publication", deliveryId, exception);
                }
            }
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private String snapshotJson(RfqPackageSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Cannot freeze RFQ publication snapshot");
        }
    }

    private String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
            ? "system"
            : authentication.getName();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? Objects.toString(second, "Supplier") : first;
    }

    private String normalizeEmail(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean validMailAddress(String email) {
        if (email == null) {
            return false;
        }
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            return email.equals(address.getAddress());
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean usesOpenSupplierAccess(RFQ rfq) {
        return RfqSupplierAccessPolicy.usesOpenSupplierAccess(rfq);
    }

}
