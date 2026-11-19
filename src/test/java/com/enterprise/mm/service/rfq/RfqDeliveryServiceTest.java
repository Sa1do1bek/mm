package com.enterprise.mm.service.rfq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.RfqDeliveryAttempt;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.enums.RfqDeliveryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.RfqDeliveryAttemptRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.SupplierAccreditationDemandResolver;
import com.enterprise.mm.service.SupplierAccreditationService;
import com.enterprise.mm.payload.SupplierEligibilityDtos.AccreditationDemand;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RfqDeliveryServiceTest {
    private static final String TENANT = "tenant-rfq-mail";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000004401");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 9, 30);

    @Mock
    private RfqInvitationDeliveryRepository deliveryRepository;
    @Mock
    private RfqDeliveryAttemptRepository attemptRepository;
    @Mock
    private RfqPublishedVersionRepository versionRepository;
    @Mock
    private RFQItemRepository rfqItemRepository;
    @Mock
    private RfqDeliveryService.OutboundEmailFactory emailFactory;
    @Mock
    private RfqDeliveryMagicLinkIssuer magicLinkIssuer;
    @Mock
    private RfqEmailTransport transport;
    @Mock
    private AuditService auditService;
    @Mock
    private SupplierAccreditationDemandResolver accreditationDemandResolver;
    @Mock
    private SupplierAccreditationService accreditationService;
    @Mock
    private ObjectProvider<RfqDeliveryService> selfProvider;

    private RfqDeliveryService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        RfqMailProperties properties = new RfqMailProperties();
        properties.setMaxAutomaticRetries(5);
        properties.setBatchSize(20);
        Clock clock = Clock.fixed(Instant.parse("2026-07-18T09:30:00Z"), ZoneOffset.UTC);
        service = new RfqDeliveryService(
            deliveryRepository,
            attemptRepository,
            versionRepository,
            rfqItemRepository,
            emailFactory,
            magicLinkIssuer,
            transport,
            auditService,
            accreditationDemandResolver,
            accreditationService,
            properties,
            clock,
            selfProvider
        );
        lenient().when(versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(any(), any(), any()))
            .thenAnswer(invocation -> {
                RfqPublishedVersion version = publishedVersion(UUID.randomUUID());
                version.setTenantId(invocation.getArgument(0));
                version.setCompanyId(invocation.getArgument(1));
                version.setId(invocation.getArgument(2));
                return Optional.of(version);
            });
        lenient().when(rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(any(), any()))
            .thenReturn(List.of());
        lenient().when(accreditationDemandResolver.forRfqItems(any()))
            .thenReturn(new AccreditationDemand(Set.of(), false, false, false, false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void oneFailedRecipientDoesNotBlockOtherSuppliers() {
        RfqInvitationDelivery good = delivery("good@example.uz", "tenant-good", COMPANY_ID);
        RfqInvitationDelivery bad = delivery("bad@example.uz", "tenant-bad", UUID.randomUUID());
        when(selfProvider.getObject()).thenReturn(service);
        when(deliveryRepository.findDue(eq(NOW), any(Pageable.class))).thenReturn(List.of(good, bad));
        when(deliveryRepository.lockById(good.getTenantId(), good.getCompanyId(), good.getId()))
            .thenReturn(Optional.of(good));
        when(deliveryRepository.lockById(bad.getTenantId(), bad.getCompanyId(), bad.getId()))
            .thenReturn(Optional.of(bad));
        when(magicLinkIssuer.issueForDelivery(any())).thenAnswer(invocation -> issued(invocation.getArgument(0)));
        when(emailFactory.create(any(), any())).thenAnswer(invocation -> outbound(invocation.<RfqInvitationDelivery>getArgument(0).getRecipientEmail()));
        when(transport.send(any())).thenAnswer(invocation -> {
            RfqOutboundEmail email = invocation.getArgument(0);
            if (email.recipientEmail().startsWith("bad")) {
                throw new BusinessException("MAILBOX_UNAVAILABLE");
            }
            return new RfqEmailTransport.SendResult("provider-good");
        });

        int dispatched = service.dispatchDue(NOW, 20);

        assertThat(dispatched).isEqualTo(2);
        assertThat(good.deliveryStatus()).isEqualTo(RfqDeliveryStatus.SENT);
        assertThat(good.getProviderMessageId()).isEqualTo("provider-good");
        assertThat(bad.deliveryStatus()).isEqualTo(RfqDeliveryStatus.FAILED);
        assertThat(bad.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(TenantContext.currentTenant()).isEqualTo(TENANT);
        assertThat(CompanyContext.currentCompany()).isEqualTo(COMPANY_ID);
    }

    @Test
    void fifthFailureExhaustsAutomaticRetry() {
        RfqInvitationDelivery delivery = delivery("retry@example.uz", TENANT, COMPANY_ID);
        delivery.setRetryCount(4);
        when(deliveryRepository.lockById(TENANT, COMPANY_ID, delivery.getId())).thenReturn(Optional.of(delivery));
        when(magicLinkIssuer.issueForDelivery(delivery)).thenReturn(issued(delivery));
        when(emailFactory.create(any(), any())).thenReturn(outbound(delivery.getRecipientEmail()));
        when(transport.send(any())).thenThrow(new BusinessException("SMTP_DOWN"));

        RfqDeliveryService.DeliveryView view = service.dispatchOne(delivery.getId());

        assertThat(view.status()).isEqualTo(RfqDeliveryStatus.FAILED.name());
        assertThat(view.retryCount()).isEqualTo(5);
        assertThat(view.nextRetryAt()).isNull();
    }

    @Test
    void missingAccreditationFailsBeforeInvitationEmailIsSent() {
        RfqInvitationDelivery delivery = delivery("supplier@example.uz", TENANT, COMPANY_ID);
        when(deliveryRepository.lockById(TENANT, COMPANY_ID, delivery.getId()))
            .thenReturn(Optional.of(delivery));
        doThrow(new BusinessException("Supplier accreditation is required for RFQ invitation"))
            .when(accreditationService)
            .assertSupplierEligibleForDemand(eq(delivery.getSupplierId()), any(), any());

        RfqDeliveryService.DeliveryView view = service.dispatchOne(delivery.getId());

        assertThat(view.status()).isEqualTo(RfqDeliveryStatus.FAILED.name());
        assertThat(delivery.getLastErrorCategory()).isEqualTo("SUPPLIER_ACCREDITATION_BLOCKED");
        verify(magicLinkIssuer, never()).issueForDelivery(any());
        verify(emailFactory, never()).create(any(), any());
        verify(transport, never()).send(any());
    }

    @Test
    void correctedRecipientPreservesOldAttemptAndQueuesNewAddress() {
        UUID rfqId = UUID.randomUUID();
        RfqPublishedVersion publishedVersion = publishedVersion(rfqId);
        RfqInvitationDelivery delivery = delivery("wrong@example.uz", TENANT, COMPANY_ID);
        delivery.setPublishedVersionId(publishedVersion.getId());
        delivery.setVersion(3L);
        delivery.setDeliveryStatus(RfqDeliveryStatus.FAILED);
        delivery.setRetryCount(5);
        RfqDeliveryAttempt oldAttempt = attempt(delivery, 5, "wrong@example.uz");
        when(versionRepository.lockLatest(TENANT, COMPANY_ID, rfqId)).thenReturn(Optional.of(publishedVersion));
        when(deliveryRepository.findByTenantIdAndCompanyIdAndPublishedVersionIdAndSupplierIdAndIsDeletedFalse(
            TENANT, COMPANY_ID, publishedVersion.getId(), delivery.getSupplierId()
        )).thenReturn(Optional.of(delivery));
        RfqDeliveryService.DeliveryView view = service.resend(
            rfqId,
            delivery.getSupplierId(),
            new RfqDeliveryService.ResendCommand(3L, "correct@example.uz", "Supplier corrected RFQ contact")
        );

        assertThat(oldAttempt.getRecipientEmail()).isEqualTo("wrong@example.uz");
        assertThat(delivery.getRecipientEmail()).isEqualTo("correct@example.uz");
        assertThat(delivery.deliveryStatus()).isEqualTo(RfqDeliveryStatus.PENDING);
        assertThat(delivery.getRetryCount()).isZero();
        assertThat(view.recipientEmail()).isEqualTo("correct@example.uz");
        verify(attemptRepository, never()).save(oldAttempt);
        verify(auditService).log(
            eq("RfqInvitationDelivery"),
            eq(delivery.getId()),
            eq("RECIPIENT_CORRECTED"),
            any(),
            isNull(),
            isNull()
        );
    }

    @Test
    void recipientCorrectionRequiresExpectedVersionChangedEmailAndReason() {
        UUID rfqId = UUID.randomUUID();
        RfqPublishedVersion publishedVersion = publishedVersion(rfqId);
        RfqInvitationDelivery delivery = delivery("same@example.uz", TENANT, COMPANY_ID);
        delivery.setPublishedVersionId(publishedVersion.getId());
        delivery.setVersion(2L);
        when(versionRepository.lockLatest(TENANT, COMPANY_ID, rfqId)).thenReturn(Optional.of(publishedVersion));
        when(deliveryRepository.findByTenantIdAndCompanyIdAndPublishedVersionIdAndSupplierIdAndIsDeletedFalse(
            TENANT, COMPANY_ID, publishedVersion.getId(), delivery.getSupplierId()
        )).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> service.resend(
            rfqId,
            delivery.getSupplierId(),
            new RfqDeliveryService.ResendCommand(2L, "same@example.uz", "unchanged")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("different");

        assertThatThrownBy(() -> service.resend(
            rfqId,
            delivery.getSupplierId(),
            new RfqDeliveryService.ResendCommand(1L, "new@example.uz", "corrected")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("changed");

        assertThatThrownBy(() -> service.resend(
            rfqId,
            delivery.getSupplierId(),
            new RfqDeliveryService.ResendCommand(2L, "new@example.uz", " ")
        )).isInstanceOf(BusinessException.class).hasMessageContaining("reason");
    }

    @Test
    void attemptsSnapshotActualRecipientAndProviderResult() {
        RfqInvitationDelivery delivery = delivery("actual@example.uz", TENANT, COMPANY_ID);
        when(deliveryRepository.lockById(TENANT, COMPANY_ID, delivery.getId())).thenReturn(Optional.of(delivery));
        when(magicLinkIssuer.issueForDelivery(delivery)).thenReturn(issued(delivery));
        when(emailFactory.create(any(), any())).thenReturn(outbound(delivery.getRecipientEmail()));
        when(transport.send(any())).thenReturn(new RfqEmailTransport.SendResult("provider-42"));

        service.dispatchOne(delivery.getId());

        ArgumentCaptor<RfqDeliveryAttempt> captor = ArgumentCaptor.forClass(RfqDeliveryAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipientEmail()).isEqualTo("actual@example.uz");
        assertThat(captor.getValue().getProviderMessageId()).isEqualTo("provider-42");
        assertThat(captor.getValue().getAttemptNumber()).isOne();
        assertThat(captor.getValue().getStatus()).isEqualTo(RfqDeliveryStatus.SENT.name());
    }

    @Test
    void renewalDispatchUsesLightweightEmailWithoutFullPackageFactory() {
        RfqInvitationDelivery delivery = delivery("renew-dispatch@example.uz", TENANT, COMPANY_ID);
        delivery.setDeliveryPurpose("MAGIC_LINK_RENEWAL");
        when(deliveryRepository.lockById(TENANT, COMPANY_ID, delivery.getId())).thenReturn(Optional.of(delivery));
        when(magicLinkIssuer.issueForDelivery(delivery)).thenReturn(issued(delivery));
        when(emailFactory.createRenewal(any(), any())).thenReturn(outbound(delivery.getRecipientEmail()));
        when(transport.send(any())).thenReturn(new RfqEmailTransport.SendResult("provider-renewal"));

        RfqDeliveryService.DeliveryView view = service.dispatchOne(delivery.getId());

        assertThat(view.status()).isEqualTo(RfqDeliveryStatus.SENT.name());
        assertThat(delivery.getProviderMessageId()).isEqualTo("provider-renewal");
        verify(emailFactory).createRenewal(eq(delivery), any());
        verify(emailFactory, never()).create(any(), any());
    }

    @Test
    void renewalQueuesFreshAttemptWithoutErasingDeliveryHistory() {
        RfqInvitationDelivery delivery = delivery("renew@example.uz", TENANT, COMPANY_ID);
        delivery.setDeliveryStatus(RfqDeliveryStatus.SENT);
        delivery.setRetryCount(2);
        delivery.setProviderMessageId("provider-old");
        delivery.setFirstSentAt(NOW.minusDays(2));
        delivery.setLastSentAt(NOW.minusDays(1));
        delivery.setDeliveredAt(NOW.minusHours(20));
        when(deliveryRepository.lockById(TENANT, COMPANY_ID, delivery.getId()))
            .thenReturn(Optional.of(delivery));

        RfqDeliveryService.DeliveryView view = service.queueRenewal(
            delivery.getId(), "SELF_SERVICE_LOGIN_RENEWAL"
        );

        assertThat(view.status()).isEqualTo(RfqDeliveryStatus.PENDING.name());
        assertThat(delivery.getDeliveryPurpose()).isEqualTo("MAGIC_LINK_RENEWAL");
        assertThat(delivery.getRetryCount()).isZero();
        assertThat(delivery.getProviderMessageId()).isNull();
        assertThat(delivery.getFirstSentAt()).isEqualTo(NOW.minusDays(2));
        assertThat(delivery.getLastSentAt()).isEqualTo(NOW.minusDays(1));
        assertThat(delivery.getDeliveredAt()).isEqualTo(NOW.minusHours(20));
        verify(auditService).log(
            "RfqInvitationDelivery",
            delivery.getId(),
            "MAGIC_LINK_RENEWAL_QUEUED",
            "SELF_SERVICE_LOGIN_RENEWAL",
            null,
            null
        );
    }

    private RfqInvitationDelivery delivery(String email, String tenantId, UUID companyId) {
        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setTenantId(tenantId);
        delivery.setCompanyId(companyId);
        delivery.setVersion(0L);
        delivery.setCode("RFD-" + delivery.getId().toString().substring(0, 8));
        delivery.setName("RFQ delivery");
        delivery.setDeliveryStatus(RfqDeliveryStatus.PENDING);
        delivery.setInvitationId(UUID.randomUUID());
        delivery.setPublishedVersionId(UUID.randomUUID());
        delivery.setSupplierId(UUID.randomUUID());
        delivery.setRecipientEmail(email);
        delivery.setRecipientName("Supplier contact");
        delivery.setRetryCount(0);
        return delivery;
    }

    private RfqPublishedVersion publishedVersion(UUID rfqId) {
        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setId(UUID.randomUUID());
        version.setTenantId(TENANT);
        version.setCompanyId(COMPANY_ID);
        version.setRfqId(rfqId);
        version.setVersionNumber(1);
        return version;
    }

    private RfqDeliveryAttempt attempt(RfqInvitationDelivery delivery, int number, String email) {
        RfqDeliveryAttempt attempt = new RfqDeliveryAttempt();
        attempt.setId(UUID.randomUUID());
        attempt.setTenantId(delivery.getTenantId());
        attempt.setCompanyId(delivery.getCompanyId());
        attempt.setDeliveryId(delivery.getId());
        attempt.setAttemptNumber(number);
        attempt.setRecipientEmail(email);
        return attempt;
    }

    private RfqDeliveryMagicLinkIssuer.IssuedLink issued(RfqInvitationDelivery delivery) {
        UUID id = UUID.randomUUID();
        return new RfqDeliveryMagicLinkIssuer.IssuedLink(
            id,
            "raw-token",
            URI.create("https://portal.example.uz/supplier-portal/magic-link?token=raw-token")
        );
    }

    private RfqOutboundEmail outbound(String recipient) {
        return new RfqOutboundEmail(
            "no-reply@example.uz",
            recipient,
            "procurement@example.uz",
            "RFQ RFQ-26-010",
            "<p>Open RFQ</p>",
            "Open RFQ",
            List.of()
        );
    }
}
