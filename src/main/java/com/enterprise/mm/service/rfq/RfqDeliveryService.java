package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.RfqDeliveryAttempt;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RfqDeliveryAttemptRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SupplierAccreditationDemandResolver;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.service.SupplierEligibilityService;
import com.enterprise.mm.payload.RfqMailCallbackDtos.RfqMailEvent;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.time.Clock;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RfqDeliveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RfqDeliveryService.class);
    private static final List<Long> BACKOFF_MINUTES = List.of(5L, 10L, 20L, 40L, 60L);
    private static final String PURPOSE_FULL_RFQ_PACKAGE = "FULL_RFQ_PACKAGE";
    private static final String PURPOSE_MAGIC_LINK_RENEWAL = "MAGIC_LINK_RENEWAL";

    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final RfqDeliveryAttemptRepository attemptRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final RFQItemRepository rfqItemRepository;
    private final OutboundEmailFactory emailFactory;
    private final RfqDeliveryMagicLinkIssuer magicLinkIssuer;
    private final RfqEmailTransport transport;
    private final AuditService auditService;
    private final SupplierAccreditationDemandResolver accreditationDemandResolver;
    private final SupplierAccreditationService accreditationService;
    private final SupplierEligibilityService supplierEligibilityService;
    private final RfqMailProperties properties;
    private final Clock clock;
    private final ObjectProvider<RfqDeliveryService> selfProvider;
    private RfqMetrics metrics;

    @Autowired
    public RfqDeliveryService(
        RfqInvitationDeliveryRepository deliveryRepository,
        RfqDeliveryAttemptRepository attemptRepository,
        RfqPublishedVersionRepository versionRepository,
        RFQItemRepository rfqItemRepository,
        OutboundEmailFactory emailFactory,
        RfqDeliveryMagicLinkIssuer magicLinkIssuer,
        RfqEmailTransport transport,
        AuditService auditService,
        SupplierAccreditationDemandResolver accreditationDemandResolver,
        SupplierAccreditationService accreditationService,
        SupplierEligibilityService supplierEligibilityService,
        RfqMailProperties properties,
        Clock clock,
        ObjectProvider<RfqDeliveryService> selfProvider
    ) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.versionRepository = versionRepository;
        this.rfqItemRepository = rfqItemRepository;
        this.emailFactory = emailFactory;
        this.magicLinkIssuer = magicLinkIssuer;
        this.transport = transport;
        this.auditService = auditService;
        this.accreditationDemandResolver = accreditationDemandResolver;
        this.accreditationService = accreditationService;
        this.supplierEligibilityService = supplierEligibilityService;
        this.properties = properties;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    public RfqDeliveryService(
        RfqInvitationDeliveryRepository deliveryRepository,
        RfqDeliveryAttemptRepository attemptRepository,
        RfqPublishedVersionRepository versionRepository,
        RFQItemRepository rfqItemRepository,
        OutboundEmailFactory emailFactory,
        RfqDeliveryMagicLinkIssuer magicLinkIssuer,
        RfqEmailTransport transport,
        AuditService auditService,
        SupplierAccreditationDemandResolver accreditationDemandResolver,
        SupplierAccreditationService accreditationService,
        RfqMailProperties properties,
        Clock clock,
        ObjectProvider<RfqDeliveryService> selfProvider
    ) {
        this(deliveryRepository, attemptRepository, versionRepository, rfqItemRepository, emailFactory,
            magicLinkIssuer, transport, auditService, accreditationDemandResolver, accreditationService,
            null, properties, clock, selfProvider);
    }



    @Autowired
    void setMetrics(RfqMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${mm.rfq-mail.poll-delay-ms:30000}")
    public void scheduledDispatch() {
        if (properties.isEnabled()) {
            dispatchDue(LocalDateTime.now(clock), properties.getBatchSize());
        }
    }

    public int dispatchDue(LocalDateTime now, int batchSize) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now(clock) : now;
        int effectiveBatchSize = Math.max(1, batchSize);
        List<RfqInvitationDelivery> due = deliveryRepository.findDue(
            effectiveNow,
            PageRequest.of(0, effectiveBatchSize)
        );
        int attempted = 0;
        for (RfqInvitationDelivery delivery : due) {
            String previousTenant = TenantContext.currentTenant();
            UUID previousCompany = CompanyContext.currentCompany();
            TenantContext.setTenant(delivery.getTenantId());
            CompanyContext.setCompany(delivery.getCompanyId());
            try {
                selfProvider.getObject().dispatchOne(delivery.getId());
                attempted++;
            } catch (Exception exception) {
                attempted++;
                LOGGER.warn("RFQ delivery {} could not be dispatched", delivery.getId(), exception);
            } finally {
                TenantContext.setTenant(previousTenant);
                CompanyContext.setCompany(previousCompany);
            }
        }
        return attempted;
    }

    @Transactional
    public void applyProviderEvent(RfqMailEvent event) {
        if (event == null || event.providerMessageId() == null || event.providerMessageId().isBlank()) {
            throw new BusinessException("RFQ mail callback provider message is required");
        }
        if (!"DELIVERED".equalsIgnoreCase(event.eventType())) {
            LOGGER.info(
                "Ignoring RFQ provider event {} for message {}",
                event.eventType(),
                event.providerMessageId()
            );
            return;
        }
        deliveryRepository
            .findFirstByProviderMessageIdAndIsDeletedFalseOrderByCreatedAtDesc(event.providerMessageId().trim())
            .ifPresentOrElse(delivery -> withDeliveryContext(delivery, () -> applyDelivered(delivery, event)), () -> LOGGER.info(
                "Ignoring RFQ delivered event for unknown provider message {}",
                event.providerMessageId()
            ));
    }

    private void withDeliveryContext(RfqInvitationDelivery delivery, Runnable action) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        TenantContext.setTenant(delivery.getTenantId());
        CompanyContext.setCompany(delivery.getCompanyId());
        try {
            action.run();
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private void applyDelivered(RfqInvitationDelivery delivery, RfqMailEvent event) {
        if (delivery.deliveryStatus() == RfqDeliveryStatus.DELIVERED) {
            return;
        }
        if (delivery.deliveryStatus() != RfqDeliveryStatus.SENT) {
            LOGGER.info(
                "Ignoring RFQ delivered event for delivery {} in status {}",
                delivery.getId(),
                delivery.getStatus()
            );
            return;
        }
        LocalDateTime deliveredAt = LocalDateTime.ofInstant(event.eventTimestamp(), clock.getZone());
        if (delivery.getLastSentAt() != null && deliveredAt.isBefore(delivery.getLastSentAt())) {
            deliveredAt = LocalDateTime.now(clock);
        }
        delivery.setDeliveryStatus(RfqDeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(deliveredAt);
        delivery.setNextRetryAt(null);
        deliveryRepository.save(delivery);
        auditService.log(
            "RfqInvitationDelivery",
            delivery.getId(),
            "EMAIL_DELIVERED",
            event.providerMessageId() + " at " + event.eventTimestamp()
        , null, null);
        if (metrics != null) {
            metrics.delivery(RfqDeliveryStatus.DELIVERED.name());
            metrics.deliveryLatency(delivery.getFirstSentAt(), deliveredAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryView dispatchOne(UUID deliveryId) {
        RfqInvitationDelivery delivery = deliveryRepository.lockById(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            deliveryId
        ).orElseThrow(() -> new BusinessException("RFQ delivery not found"));
        if (delivery.deliveryStatus() == RfqDeliveryStatus.SENT
            || delivery.deliveryStatus() == RfqDeliveryStatus.DELIVERED) {
            return view(delivery);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int attemptNumber = nextAttemptNumber(delivery);
        RfqDeliveryAttempt attempt = newAttempt(delivery, attemptNumber, now);
        try {
            assertSupplierAccreditedForInvitation(delivery);
            RfqDeliveryMagicLinkIssuer.IssuedLink link = magicLinkIssuer.issueForDelivery(delivery);
            RfqOutboundEmail email = isMagicLinkRenewal(delivery)
                ? emailFactory.createRenewal(delivery, link.url())
                : emailFactory.create(delivery, link.url());
            RfqEmailTransport.SendResult result = transport.send(email);
            markSent(delivery, attempt, result == null ? null : result.providerMessageId(), now);
        } catch (Exception exception) {
            markFailed(delivery, attempt, exception, now);
        }
        attemptRepository.save(attempt);
        deliveryRepository.save(delivery);
        return view(delivery);
    }

    private void assertSupplierAccreditedForInvitation(RfqInvitationDelivery delivery) {
        RfqPublishedVersion version = versionRepository
            .findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                delivery.getTenantId(), delivery.getCompanyId(), delivery.getPublishedVersionId())
            .orElseThrow(() -> new BusinessException("RFQ published version not found"));
        var items = rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
                delivery.getTenantId(), version.getRfqId())
            .stream()
            .filter(item -> delivery.getCompanyId().equals(item.getCompanyId()))
            .toList();
        var demand = accreditationDemandResolver.forRfqItems(items);
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireNewBusinessAllowed(
                delivery.getSupplierId(),
                demand,
                "RFQ_INVITATION"
            );
            return;
        }
        accreditationService.assertSupplierEligibleForDemand(
            delivery.getSupplierId(),
            demand,
            "RFQ invitation for RFQ " + version.getRfqId()
        );
    }

    @Transactional
    public DeliveryView resend(UUID rfqId, UUID supplierId, ResendCommand command) {
        if (command == null || command.expectedVersion() == null) {
            throw new BusinessException("Expected RFQ delivery version is required");
        }
        RfqPublishedVersion publishedVersion = versionRepository.lockLatest(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        ).orElseThrow(() -> new BusinessException("Published RFQ version not found"));
        RfqInvitationDelivery delivery = deliveryRepository
            .findByTenantIdAndCompanyIdAndPublishedVersionIdAndSupplierIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                publishedVersion.getId(),
                supplierId
            )
            .orElseThrow(() -> new BusinessException("RFQ supplier delivery not found"));
        long actualVersion = delivery.getVersion() == null ? 0L : delivery.getVersion();
        if (!Objects.equals(command.expectedVersion(), actualVersion)) {
            throw new BusinessException("RFQ delivery changed; refresh and try again");
        }

        String correctedEmail = normalize(command.correctedRecipientEmail());
        if (correctedEmail != null) {
            requireCorrection(delivery, correctedEmail, command.reason());
            String oldEmail = delivery.getRecipientEmail();
            delivery.setRecipientEmail(correctedEmail);
            auditService.log(
                "RfqInvitationDelivery",
                delivery.getId(),
                "RECIPIENT_CORRECTED",
                oldEmail + " -> " + correctedEmail + "; " + command.reason().trim()
            , null, null);
        } else {
            auditService.log("RfqInvitationDelivery", delivery.getId(), "RESEND_QUEUED", safeReason(command.reason()), null, null);
        }
        queue(delivery);
        deliveryRepository.save(delivery);
        return view(delivery);
    }

    @Transactional
    public int resendFailed(UUID rfqId) {
        RfqPublishedVersion publishedVersion = versionRepository.lockLatest(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        ).orElseThrow(() -> new BusinessException("Published RFQ version not found"));
        int queued = 0;
        for (RfqInvitationDelivery delivery : deliveryRepository
            .findByTenantIdAndCompanyIdAndPublishedVersionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                publishedVersion.getId()
            )) {
            if (delivery.deliveryStatus() == RfqDeliveryStatus.FAILED) {
                queue(delivery);
                deliveryRepository.save(delivery);
                auditService.log("RfqInvitationDelivery", delivery.getId(), "RESEND_QUEUED", "Bulk resend failed", null, null);
                queued++;
            }
        }
        return queued;
    }

    @Transactional
    public DeliveryView queueRenewal(UUID deliveryId, String reason) {
        RfqInvitationDelivery delivery = deliveryRepository.lockById(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            deliveryId
        ).orElseThrow(() -> new BusinessException("RFQ delivery not found"));
        queue(delivery, PURPOSE_MAGIC_LINK_RENEWAL);
        deliveryRepository.save(delivery);
        auditService.log(
            "RfqInvitationDelivery",
            delivery.getId(),
            "MAGIC_LINK_RENEWAL_QUEUED",
            safeReason(reason)
        , null, null);
        return view(delivery);
    }

    private int nextAttemptNumber(RfqInvitationDelivery delivery) {
        return attemptRepository
            .findByTenantIdAndCompanyIdAndDeliveryIdAndIsDeletedFalseOrderByAttemptNumberAsc(
                delivery.getTenantId(),
                delivery.getCompanyId(),
                delivery.getId()
            )
            .stream()
            .map(RfqDeliveryAttempt::getAttemptNumber)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    private RfqDeliveryAttempt newAttempt(
        RfqInvitationDelivery delivery,
        int attemptNumber,
        LocalDateTime attemptedAt
    ) {
        RfqDeliveryAttempt attempt = new RfqDeliveryAttempt();
        attempt.setTenantId(delivery.getTenantId());
        attempt.setCompanyId(delivery.getCompanyId());
        attempt.setCode("RFQ-MAIL-" + delivery.getId().toString().substring(0, 8) + "-" + attemptNumber);
        attempt.setName("RFQ email attempt " + attemptNumber);
        attempt.setDeliveryId(delivery.getId());
        attempt.setAttemptNumber(attemptNumber);
        attempt.setIdempotencyKey(delivery.getId() + ":" + attemptNumber + ":" + delivery.getRecipientEmail().toLowerCase(Locale.ROOT));
        attempt.setAttemptedAt(attemptedAt);
        attempt.setRecipientEmail(delivery.getRecipientEmail());
        return attempt;
    }

    private void markSent(
        RfqInvitationDelivery delivery,
        RfqDeliveryAttempt attempt,
        String providerMessageId,
        LocalDateTime sentAt
    ) {
        attempt.setStatus(RfqDeliveryStatus.SENT.name());
        attempt.setProviderMessageId(providerMessageId);
        delivery.setDeliveryStatus(RfqDeliveryStatus.SENT);
        delivery.setProviderMessageId(providerMessageId);
        delivery.setNextRetryAt(null);
        delivery.setLastErrorCategory(null);
        delivery.setLastSentAt(sentAt);
        if (delivery.getFirstSentAt() == null) {
            delivery.setFirstSentAt(sentAt);
        }
        auditService.log("RfqInvitationDelivery", delivery.getId(), "EMAIL_SENT", delivery.getRecipientEmail(), null, null);
        if (metrics != null) {
            metrics.delivery(RfqDeliveryStatus.SENT.name());
        }
    }

    private void markFailed(
        RfqInvitationDelivery delivery,
        RfqDeliveryAttempt attempt,
        Exception exception,
        LocalDateTime failedAt
    ) {
        int failureCount = (delivery.getRetryCount() == null ? 0 : delivery.getRetryCount()) + 1;
        String category = classify(exception);
        String message = truncate(exception.getMessage(), 500);
        attempt.setStatus(RfqDeliveryStatus.FAILED.name());
        attempt.setErrorCategory(category);
        attempt.setErrorMessage(message);
        delivery.setDeliveryStatus(RfqDeliveryStatus.FAILED);
        delivery.setRetryCount(failureCount);
        delivery.setLastErrorCategory(category);
        delivery.setNextRetryAt(failureCount >= properties.getMaxAutomaticRetries()
            ? null
            : failedAt.plusMinutes(BACKOFF_MINUTES.get(Math.min(failureCount - 1, BACKOFF_MINUTES.size() - 1))));
        auditService.log("RfqInvitationDelivery", delivery.getId(), "EMAIL_FAILED", category + ": " + message, null, null);
        if (metrics != null) {
            metrics.delivery(RfqDeliveryStatus.FAILED.name());
        }
    }

    private void queue(RfqInvitationDelivery delivery) {
        queue(delivery, PURPOSE_FULL_RFQ_PACKAGE);
    }

    private void queue(RfqInvitationDelivery delivery, String purpose) {
        delivery.setDeliveryStatus(RfqDeliveryStatus.PENDING);
        delivery.setDeliveryPurpose(purpose);
        delivery.setRetryCount(0);
        delivery.setNextRetryAt(null);
        delivery.setProviderMessageId(null);
        delivery.setLastErrorCategory(null);
        if (metrics != null) {
            metrics.delivery(RfqDeliveryStatus.PENDING.name());
        }
    }

    private boolean isMagicLinkRenewal(RfqInvitationDelivery delivery) {
        return PURPOSE_MAGIC_LINK_RENEWAL.equals(delivery.getDeliveryPurpose());
    }

    private void requireCorrection(RfqInvitationDelivery delivery, String correctedEmail, String reason) {
        if (correctedEmail.equalsIgnoreCase(delivery.getRecipientEmail())) {
            throw new BusinessException("Corrected recipient email must be different");
        }
        validateEmail(correctedEmail);
        if (reason == null || reason.trim().length() < 5) {
            throw new BusinessException("Recipient correction reason is required");
        }
    }

    private void validateEmail(String email) {
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            if (!email.equals(address.getAddress())) {
                throw new IllegalArgumentException("Email contains a display name");
            }
        } catch (Exception exception) {
            throw new BusinessException("Corrected recipient email is invalid");
        }
    }

    private String classify(Exception exception) {
        String message = exception.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("accreditation") || normalized.contains("material group is required")) {
                return "SUPPLIER_ACCREDITATION_BLOCKED";
            }
            String candidate = message.trim().split("[^A-Za-z0-9_]+", 2)[0].toUpperCase(Locale.ROOT);
            if (candidate.matches("[A-Z][A-Z0-9_]{2,79}")) {
                return candidate;
            }
        }
        return truncate(exception.getClass().getSimpleName().toUpperCase(Locale.ROOT), 80);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeReason(String value) {
        return value == null || value.isBlank() ? "Explicit resend" : value.trim();
    }

    private String truncate(String value, int maximumLength) {
        String safe = value == null || value.isBlank() ? "Unknown delivery error" : value.trim();
        return safe.length() <= maximumLength ? safe : safe.substring(0, maximumLength);
    }

    private DeliveryView view(RfqInvitationDelivery delivery) {
        return new DeliveryView(
            delivery.getId(),
            delivery.getSupplierId(),
            delivery.getRecipientEmail(),
            delivery.getStatus(),
            delivery.getRetryCount() == null ? 0 : delivery.getRetryCount(),
            delivery.getNextRetryAt(),
            delivery.getVersion() == null ? 0L : delivery.getVersion()
        );
    }

    public interface OutboundEmailFactory {
        RfqOutboundEmail create(RfqInvitationDelivery delivery, URI magicLink);

        RfqOutboundEmail createRenewal(RfqInvitationDelivery delivery, URI magicLink);
    }

    public record ResendCommand(Long expectedVersion, String correctedRecipientEmail, String reason) {
    }

    public record DeliveryView(
        UUID deliveryId,
        UUID supplierId,
        String recipientEmail,
        String status,
        int retryCount,
        LocalDateTime nextRetryAt,
        long version
    ) {
    }
}
