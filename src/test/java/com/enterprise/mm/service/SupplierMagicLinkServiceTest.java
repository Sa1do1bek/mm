package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierMagicLink;
import com.enterprise.mm.entity.SupplierMagicLinkRequest;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.SupplierMagicLinkRequestReason;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkRequestResponse;
import com.enterprise.mm.repository.CompanyRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RfqInvitationDeliveryRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierMagicLinkRepository;
import com.enterprise.mm.repository.SupplierMagicLinkRequestRepository;
import com.enterprise.mm.repository.SupplierPoDispatchRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqDeliveryMagicLinkIssuer;
import com.enterprise.mm.service.rfq.RfqDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class SupplierMagicLinkServiceTest {
    private static final String TENANT = "tenant-magic-link";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000006601");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 12, 0);

    @Mock private SupplierMagicLinkRepository linkRepository;
    @Mock private SupplierMagicLinkRequestRepository requestRepository;
    @Mock private SupplierPortalAccountRepository accountRepository;
    @Mock private SupplierPoDispatchRepository poDispatchRepository;
    @Mock private RfqSupplierInvitationRepository invitationRepository;
    @Mock private RfqInvitationDeliveryRepository deliveryRepository;
    @Mock private RfqPublishedVersionRepository publishedVersionRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private ObjectProvider<RfqDeliveryService> deliveryServiceProvider;
    @Mock private RfqDeliveryService deliveryService;
    @Mock private AuditService auditService;

    private SupplierMagicLinkService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        RfqMailProperties properties = new RfqMailProperties();
        properties.setPortalBaseUrl("https://portal.example.uz/supplier-portal");
        properties.setMagicLinkPepper("test-pepper-with-sufficient-length");
        service = new SupplierMagicLinkService(
            linkRepository,
            requestRepository,
            accountRepository,
            poDispatchRepository,
            invitationRepository,
            deliveryRepository,
            publishedVersionRepository,
            purchaseOrderRepository,
            supplierRepository,
            companyRepository,
            deliveryServiceProvider,
            properties,
            auditService,
            Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void exchangeCanReuseMagicLinkWithin24Hours() {
        Fixture fixture = fixture();
        fixture.account().setPortalTokenHash("old-session-hash");
        AtomicReference<SupplierMagicLink> stored = new AtomicReference<>();
        when(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of(fixture.account()));
        when(invitationRepository.findById(fixture.invitation().getId())).thenReturn(Optional.of(fixture.invitation()));
        when(linkRepository.findByTenantIdAndCompanyIdAndInvitationIdAndStatusAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.invitation().getId(), "ISSUED"
        )).thenReturn(List.of());
        when(linkRepository.save(any(SupplierMagicLink.class))).thenAnswer(invocation -> {
            SupplierMagicLink link = invocation.getArgument(0);
            if (link.getId() == null) {
                link.setId(UUID.randomUUID());
            }
            stored.set(link);
            return link;
        });

        RfqDeliveryMagicLinkIssuer.IssuedLink issued = service.issueForDelivery(fixture.delivery());

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.url().toString()).contains("/supplier-portal/magic-link?token=");
        assertThat(stored.get().getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(stored.get().getExpiresAt()).isEqualTo(NOW.plusHours(24));

        when(linkRepository.lockUsable(stored.get().getTokenHash(), NOW))
            .thenReturn(Optional.of(stored.get()), Optional.of(stored.get()), Optional.of(stored.get()));
        when(accountRepository.findByIdAndIsDeletedFalse(fixture.account().getId()))
            .thenReturn(Optional.of(fixture.account()));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, fixture.account().getSupplierId()))
            .thenReturn(Optional.of(fixture.supplier()));
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID))
            .thenReturn(Optional.of(fixture.company()));
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(any()))
            .thenReturn(Optional.of(fixture.account()));

        var response = service.exchange(issued.rawToken());
        var secondResponse = service.exchange(issued.rawToken());
        var thirdResponse = service.exchange(issued.rawToken());

        assertThat(response.expiresAt()).isEqualTo(NOW.plusHours(24));
        assertThat(response.token()).isNotBlank().isNotEqualTo(issued.rawToken());
        assertThat(response.returnPath()).isEqualTo("/supplier-portal/rfq/" + fixture.publishedVersion().getRfqId());
        assertThat(stored.get().getConsumedAt()).isNull();
        assertThat(stored.get().getStatus()).isEqualTo("ISSUED");
        assertThat(fixture.account().getPortalTokenHash()).isNotEqualTo(response.token());
        assertThat(fixture.account().getPortalTokenHash()).isNotEqualTo("old-session-hash");
        assertThat(fixture.account().getStatus()).isEqualTo("ACTIVE");
        assertThat(secondResponse.token()).isNotBlank().isNotEqualTo(response.token());
        assertThat(thirdResponse.token()).isNotBlank().isNotEqualTo(secondResponse.token()).isNotEqualTo(response.token());
        verify(accountRepository, times(3)).saveAndFlush(fixture.account());
    }

    @Test
    void exchangeSucceedsForPurchaseOrderScopedLinkWithoutTouchingInvitationRepository() {
        // Regression test: SupplierPoDispatchMailService issues magic links via
        // issueForPurchaseOrderDispatch(), which leaves invitationId null and sets
        // purchaseOrderId instead. exchange() must branch on purchaseOrderId and must
        // NOT call invitationRepository.findById(null) (Spring Data's findById rejects
        // a null id with an IllegalArgumentException, which is not the graceful
        // "invalid link" BusinessException every other failure mode in this method
        // produces).
        Fixture fixture = fixture();
        SupplierMagicLink link = magicLink(fixture, "po-hash");
        link.setInvitationId(null);
        UUID purchaseOrderId = UUID.randomUUID();
        link.setPurchaseOrderId(purchaseOrderId);
        link.setReturnPath("/supplier-portal/purchase-orders/" + purchaseOrderId);

        com.enterprise.mm.entity.PurchaseOrder purchaseOrder = new com.enterprise.mm.entity.PurchaseOrder();
        purchaseOrder.setId(purchaseOrderId);
        purchaseOrder.setTenantId(TENANT);
        purchaseOrder.setCompanyId(COMPANY_ID);
        purchaseOrder.setSupplierId(fixture.account().getSupplierId());

        when(linkRepository.lockUsable(any(), eq(NOW))).thenReturn(Optional.of(link));
        when(accountRepository.findByIdAndIsDeletedFalse(fixture.account().getId()))
            .thenReturn(Optional.of(fixture.account()));
        when(purchaseOrderRepository.findById(purchaseOrderId)).thenReturn(Optional.of(purchaseOrder));
        when(supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, fixture.account().getSupplierId()))
            .thenReturn(Optional.of(fixture.supplier()));
        when(companyRepository.findByTenantIdAndIdAndIsDeletedFalse(TENANT, COMPANY_ID))
            .thenReturn(Optional.of(fixture.company()));
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(any()))
            .thenReturn(Optional.of(fixture.account()));

        var response = service.exchange("po-raw-token");

        assertThat(response.token()).isNotBlank();
        assertThat(response.returnPath()).isEqualTo(link.getReturnPath());
        assertThat(fixture.account().getPortalTokenHash()).isNotBlank();
        verify(accountRepository).saveAndFlush(fixture.account());
        verify(invitationRepository, never()).findById(any());
    }

    @Test
    void withdrawnInvitationFailsWithSameSafeExchangeError() {
        Fixture fixture = fixture();
        SupplierMagicLink link = magicLink(fixture, "hash");
        fixture.invitation().setStatus("WITHDRAWN");
        when(linkRepository.lockUsable(any(), eq(NOW))).thenReturn(Optional.of(link));
        when(accountRepository.findByIdAndIsDeletedFalse(fixture.account().getId()))
            .thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findById(fixture.invitation().getId())).thenReturn(Optional.of(fixture.invitation()));

        assertThatThrownBy(() -> service.exchange("raw-token"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier magic link is invalid or expired");
    }

    @Test
    void crossCompanyAndSuspendedAccountsUseTheSameSafeExchangeError() {
        Fixture fixture = fixture();
        SupplierMagicLink link = magicLink(fixture, "hash");
        when(linkRepository.lockUsable(any(), eq(NOW))).thenReturn(Optional.of(link));
        fixture.account().setCompanyId(UUID.randomUUID());
        when(accountRepository.findByIdAndIsDeletedFalse(fixture.account().getId()))
            .thenReturn(Optional.of(fixture.account()));

        assertThatThrownBy(() -> service.exchange("cross-company-token"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier magic link is invalid or expired");

        fixture.account().setCompanyId(COMPANY_ID);
        fixture.account().setStatus("SUSPENDED");
        assertThatThrownBy(() -> service.exchange("suspended-token"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier magic link is invalid or expired");
    }

    @Test
    void renewalResponseIsNeutralForKnownAndUnknownEmails() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("UNKNOWN"))
            .thenReturn(List.of());
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.account().getLoginEmail()
        )).thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of(fixture.invitation()));
        when(deliveryRepository.findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, fixture.invitation().getId()
        )).thenReturn(List.of(fixture.delivery()));
        stubNoPoDispatch(fixture);
        when(deliveryServiceProvider.getObject()).thenReturn(deliveryService);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MagicLinkRequestResponse known = service.requestLink(
            "PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1"
        );
        MagicLinkRequestResponse unknown = service.requestLink(
            "UNKNOWN", "unknown@example.uz", "203.0.113.2"
        );

        assertThat(known).isEqualTo(unknown);
        assertThat(known.message()).isEqualTo("If the account is eligible, a login link has been sent.");
        verify(deliveryService).queueRenewal(fixture.delivery().getId(), "SELF_SERVICE_LOGIN_RENEWAL");
        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository, times(2)).save(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
            .allSatisfy(request -> {
                assertThat(request.getNormalizedEmailHash()).hasSize(64);
                assertThat(request.getRequestIpHash()).hasSize(64);
                assertThat(request.getNormalizedEmailHash()).doesNotContain("@");
                assertThat(request.getRequestIpHash()).doesNotContain("203.0.113");
            });
        assertThat(requestCaptor.getAllValues())
            .extracting(SupplierMagicLinkRequest::getReasonCode)
            .containsExactly(
                SupplierMagicLinkRequestReason.MATCHED_QUEUED,
                SupplierMagicLinkRequestReason.COMPANY_NOT_FOUND
            );
        assertThat(requestCaptor.getAllValues().get(1).isUnresolvedCompany()).isTrue();
    }

    @Test
    void knownSupplierRequestCanBeQueuedMultipleTimesWithinRateLimit() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.account().getLoginEmail()
        )).thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of(fixture.invitation()));
        when(deliveryRepository.findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, fixture.invitation().getId()
        )).thenReturn(List.of(fixture.delivery()));
        stubNoPoDispatch(fixture);
        when(deliveryServiceProvider.getObject()).thenReturn(deliveryService);
        when(requestRepository.countRecentForEmail(eq(TENANT), eq(COMPANY_ID), any(), any())).thenReturn(0L, 0L);
        when(requestRepository.countRecentForIp(eq(TENANT), any(), any())).thenReturn(0L, 0L);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.requestLink("PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1");
        service.requestLink("PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1");

        verify(deliveryService, times(2)).queueRenewal(fixture.delivery().getId(), "SELF_SERVICE_LOGIN_RENEWAL");
    }

    @Test
    void selfServiceRequestQueuesActivePoDispatchWithoutRfqDelivery() {
        Fixture fixture = fixture();
        fixture.account().setStatus("ACTIVE");
        fixture.account().setLoginEmail("portal-login@supplier.test");
        SupplierPoDispatch dispatch = poDispatch(fixture);
        dispatch.setRecipientEmail("old-recipient@supplier.test");
        dispatch.setDispatchStatus("FAILED");
        dispatch.setQueuedAt(NOW.minusDays(1));
        dispatch.setLastDeliveryError("smtp timeout");

        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.account().getLoginEmail()
        )).thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of());
        when(poDispatchRepository.findCurrentPortalCandidatesForSupplierNewest(
            eq(TENANT), eq(COMPANY_ID), eq(fixture.account().getSupplierId()), any(), any()
        )).thenReturn(List.of(dispatch));
        when(poDispatchRepository.findByIdAndIsDeletedFalse(dispatch.getId())).thenReturn(Optional.of(dispatch));
        when(poDispatchRepository.save(any(SupplierPoDispatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestRepository.save(any(SupplierMagicLinkRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MagicLinkRequestResponse response = service.requestLink(
            "PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1"
        );

        assertThat(response.message()).isEqualTo("If the account is eligible, a login link has been sent.");
        assertThat(dispatch.getRecipientEmail()).isEqualTo(fixture.account().getLoginEmail());
        assertThat(dispatch.getDispatchStatus()).isEqualTo("QUEUED");
        assertThat(dispatch.getQueuedAt()).isEqualTo(NOW);
        assertThat(dispatch.getLastDeliveryError()).isNull();
        verify(poDispatchRepository).save(dispatch);
        verify(deliveryServiceProvider, never()).getObject();

        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().isMatchedAccount()).isTrue();
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.MATCHED_QUEUED);
    }

    @Test
    void rateLimitedRenewalStillReturnsNeutralResponseAndDoesNotQueue() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(requestRepository.countRecentForEmail(eq(TENANT), eq(COMPANY_ID), any(), any())).thenReturn(5L);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MagicLinkRequestResponse response = service.requestLink(
            "PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1"
        );

        assertThat(response.message()).contains("eligible");
        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.RATE_LIMITED);
        verify(deliveryServiceProvider, never()).getObject();
    }

    @Test
    void ipRateLimitAlsoReturnsNeutralResponseAndDoesNotQueue() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(requestRepository.countRecentForIp(eq(TENANT), any(), any())).thenReturn(10L);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MagicLinkRequestResponse response = service.requestLink(
            "PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1"
        );

        assertThat(response.message()).contains("eligible");
        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.RATE_LIMITED);
        verify(deliveryServiceProvider, never()).getObject();
    }

    @Test
    void matchedSupplierWithoutActiveInvitationPersistsReasonAndDoesNotQueue() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.account().getLoginEmail()
        )).thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of());
        stubNoPoDispatch(fixture);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.requestLink("PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1");

        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().isMatchedAccount()).isTrue();
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.NO_ACTIVE_PORTAL_DOCUMENT);
        verify(deliveryServiceProvider, never()).getObject();
    }

    @Test
    void matchedSupplierWithoutDeliveryPersistsReasonAndDoesNotQueue() {
        Fixture fixture = fixture();
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("PLANT-01"))
            .thenReturn(List.of(fixture.company()));
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, COMPANY_ID, fixture.account().getLoginEmail()
        )).thenReturn(Optional.of(fixture.account()));
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of(fixture.invitation()));
        when(deliveryRepository.findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT, COMPANY_ID, fixture.invitation().getId()
        )).thenReturn(List.of());
        stubNoPoDispatch(fixture);
        when(requestRepository.save(any(SupplierMagicLinkRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.requestLink("PLANT-01", fixture.account().getLoginEmail(), "203.0.113.1");

        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().isMatchedAccount()).isTrue();
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.NO_ACTIVE_PORTAL_DOCUMENT);
        verify(deliveryServiceProvider, never()).getObject();
    }

    @Test
    void unknownCompanyReturnsNeutralResponseAndDoesNotQueueAnyEmail() {
        when(companyRepository.findByCodeIgnoreCaseAndIsDeletedFalse("UNKNOWN"))
            .thenReturn(List.of());
        when(requestRepository.save(any(SupplierMagicLinkRequest.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        MagicLinkRequestResponse response = service.requestLink(
            "UNKNOWN", "unknown@example.uz", "203.0.113.10"
        );

        assertThat(response.message()).isEqualTo("If the account is eligible, a login link has been sent.");
        verify(deliveryServiceProvider, never()).getObject();
        verify(poDispatchRepository, never()).save(any(SupplierPoDispatch.class));

        ArgumentCaptor<SupplierMagicLinkRequest> requestCaptor =
            ArgumentCaptor.forClass(SupplierMagicLinkRequest.class);
        verify(requestRepository).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().isMatchedAccount()).isFalse();
        assertThat(requestCaptor.getValue().getReasonCode())
            .isEqualTo(SupplierMagicLinkRequestReason.COMPANY_NOT_FOUND);
    }

    @Test
    void administrativeReinviteWithoutActiveRfqReturnsStableError() {
        Fixture fixture = fixture();
        when(invitationRepository.findActiveForSupplierNewest(
            TENANT, COMPANY_ID, fixture.account().getSupplierId()
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service.queueLatestForAccount(
            fixture.account(), "ADMIN_REINVITE: recovery"
        ))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier has no active RFQ invitation; a login link will be sent when an RFQ is published.");

        verify(deliveryServiceProvider, never()).getObject();
    }

    private Fixture fixture() {
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(COMPANY_ID);
        supplier.setName("Supplier LLC");
        supplier.setStatus("ACTIVE");

        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setId(UUID.randomUUID());
        account.setTenantId(TENANT);
        account.setCompanyId(COMPANY_ID);
        account.setSupplierId(supplier.getId());
        account.setLoginEmail("sales@supplier.test");
        account.setStatus("INVITED");

        RfqSupplierInvitation invitation = new RfqSupplierInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setTenantId(TENANT);
        invitation.setCompanyId(COMPANY_ID);
        invitation.setSupplierId(supplier.getId());
        invitation.setRfqId(UUID.randomUUID());
        invitation.setStatus("INVITED");

        RfqPublishedVersion version = new RfqPublishedVersion();
        version.setId(UUID.randomUUID());
        version.setRfqId(invitation.getRfqId());

        RfqInvitationDelivery delivery = new RfqInvitationDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setTenantId(TENANT);
        delivery.setCompanyId(COMPANY_ID);
        delivery.setSupplierId(supplier.getId());
        delivery.setInvitationId(invitation.getId());
        delivery.setRecipientEmail(account.getLoginEmail());
        delivery.setPublishedVersion(version);

        Company company = new Company();
        company.setId(COMPANY_ID);
        company.setTenantId(TENANT);
        company.setCode("PLANT-01");
        company.setName("Plant 01");
        return new Fixture(account, supplier, invitation, version, delivery, company);
    }

    private SupplierMagicLink magicLink(Fixture fixture, String hash) {
        SupplierMagicLink link = new SupplierMagicLink();
        link.setId(UUID.randomUUID());
        link.setTenantId(TENANT);
        link.setCompanyId(COMPANY_ID);
        link.setPortalAccountId(fixture.account().getId());
        link.setSupplierId(fixture.account().getSupplierId());
        link.setInvitationId(fixture.invitation().getId());
        link.setTokenHash(hash);
        link.setStatus("ISSUED");
        link.setExpiresAt(NOW.plusHours(1));
        return link;
    }

    private SupplierPoDispatch poDispatch(Fixture fixture) {
        SupplierPoDispatch dispatch = new SupplierPoDispatch();
        dispatch.setId(UUID.randomUUID());
        dispatch.setTenantId(TENANT);
        dispatch.setCompanyId(COMPANY_ID);
        dispatch.setCode("PO-DISPATCH-001");
        dispatch.setName("PO dispatch");
        dispatch.setSupplierId(fixture.account().getSupplierId());
        dispatch.setPortalAccountId(fixture.account().getId());
        dispatch.setPurchaseOrderId(UUID.randomUUID());
        dispatch.setPurchaseOrderVersion(1L);
        dispatch.setCurrent(true);
        dispatch.setDispatchStatus("SENT");
        dispatch.setQueuedAt(NOW.minusHours(2));
        dispatch.setSnapshotJson("{}");
        dispatch.setSnapshotHash("snapshot-hash");
        return dispatch;
    }

    private void stubNoPoDispatch(Fixture fixture) {
        when(poDispatchRepository.findCurrentPortalCandidatesForSupplierNewest(
            eq(TENANT), eq(COMPANY_ID), eq(fixture.account().getSupplierId()), any(), any()
        )).thenReturn(List.of());
    }

    private record Fixture(
        SupplierPortalAccount account,
        Supplier supplier,
        RfqSupplierInvitation invitation,
        RfqPublishedVersion publishedVersion,
        RfqInvitationDelivery delivery,
        Company company
    ) {
    }
}
