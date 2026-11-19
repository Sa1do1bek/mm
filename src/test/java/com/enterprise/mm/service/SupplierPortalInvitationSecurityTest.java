package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SupplierPortalAccountMapper;
import com.enterprise.mm.payload.SupplierPortalAccountResponse;
import com.enterprise.mm.payload.SupplierPortalAccountCreateRequest;
import com.enterprise.mm.payload.SupplierPortalAccountUpdateRequest;
import com.enterprise.mm.mapper.SupplierPortalSubmissionMapper;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.payload.SupplierComplianceDtos.SupplierPortalActionRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalLoginRequest;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkExchangeResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierPortalSubmissionRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqEmailTransport;
import com.enterprise.mm.security.AccessScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class SupplierPortalInvitationSecurityTest {
    private static final String TENANT = "supplier-portal-security";

    @Mock private SupplierPortalAccountRepository accountRepository;
    @Mock private SupplierPortalSubmissionRepository submissionRepository;
    @Mock private SupplierPortalSubmissionMapper submissionMapper;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RFQRepository rfqRepository;
    @Mock private RFQItemRepository rfqItemRepository;
    @Mock private RfqSupplierInvitationRepository invitationRepository;
    @Mock private SupplierQuotationRepository quotationRepository;
    @Mock private SupplierQuotationItemRepository quotationItemRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private SupplierAccreditationDemandResolver accreditationDemandResolver;
    @Mock private SupplierAccreditationService accreditationService;
    @Mock private AuditService auditService;
    @Mock private SupplierComplianceService complianceService;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private SupplierMagicLinkService magicLinkService;
    @Mock private RfqEmailTransport emailTransport;
    @Mock private RfqMailProperties mailProperties;

    @InjectMocks private SupplierPortalExternalService externalService;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        AccessScope.clearRequestedCompanyScope();
        SecurityContextHolder.clearContext();
    }

    @Test
    void responseNeverExposesInviteTokenHash() {
        SupplierPortalAccount account = invitedAccount("secret-token", LocalDateTime.now());

        var response = new SupplierPortalAccountMapper().toResponse(account);

        assertThat(response.getInviteTokenHash()).isNull();
    }

    @Test
    void administrativeCreateReturnsAndPersistsNoRawInviteSecret() {
        Supplier supplier = supplier(UUID.randomUUID());
        SupplierPortalAccountCreateRequest request = new SupplierPortalAccountCreateRequest();
        request.setSupplierId(supplier.getId());
        request.setName("Supplier portal contact");
        request.setLoginEmail("new-contact@example.uz");
        when(complianceService.validateSupplier(supplier.getId())).thenReturn(supplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT, companyId, request.getLoginEmail()
        )).thenReturn(Optional.empty());
        when(accountRepository.save(any(SupplierPortalAccount.class))).thenAnswer(invocation -> {
            SupplierPortalAccount saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        SupplierPortalAccountResponse response = accountService().create(request);

        assertThat(response.getInviteToken()).isNull();
        assertThat(response.getInviteTokenHash()).isNull();
        assertThat(response.getLoginEmail()).isEqualTo(request.getLoginEmail());
        ArgumentCaptor<SupplierPortalAccount> captor = ArgumentCaptor.forClass(SupplierPortalAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getLoginEmail()).isEqualTo(request.getLoginEmail());
        assertThat(captor.getValue().getInviteTokenHash()).isNull();
        assertThat(captor.getValue().getInviteTokenExpiresAt()).isNull();
    }

    @Test
    void genericUpdateCannotChangeUnenforcedMfaOrIpControls() {
        SupplierPortalAccount account = invitedAccount("secret-token", LocalDateTime.now());
        account.setAllowedIpRange("server-owned-range");
        account.setMfaRequired(true);
        SupplierPortalAccountUpdateRequest request = new SupplierPortalAccountUpdateRequest();
        request.setAllowedIpRange("client-supplied-range");
        request.setMfaRequired(false);

        new SupplierPortalAccountMapper().updateEntity(account, request);

        assertThat(account.getAllowedIpRange()).isEqualTo("server-owned-range");
        assertThat(account.isMfaRequired()).isTrue();
    }

    @Test
    void publicInvitationCanBeExchangedOnlyOnce() {
        String inviteToken = "single-use-token";
        SupplierPortalAccount account = invitedAccount(inviteToken, LocalDateTime.now());
        MagicLinkExchangeResponse exchanged = new MagicLinkExchangeResponse(
            "session-token",
            LocalDateTime.now().plusHours(24),
            account.getId(),
            account.getSupplierId(),
            "Supplier",
            "PLANT-01",
            SupplierPortalStatus.ACTIVE.name(),
            "/supplier-portal/rfq/" + UUID.randomUUID()
        );
        when(magicLinkService.exchange(inviteToken))
            .thenReturn(exchanged)
            .thenThrow(new BusinessException("Supplier magic link is invalid or expired"));

        assertThat(externalService.login(
            new SupplierPortalLoginRequest(account.getLoginEmail(), inviteToken)).token()
        ).isEqualTo("session-token");

        assertThatThrownBy(() -> externalService.login(
            new SupplierPortalLoginRequest(account.getLoginEmail(), inviteToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier magic link is invalid or expired");
    }

    @Test
    void expiredInvitationCannotCreatePortalSession() {
        String inviteToken = "expired-token";
        SupplierPortalAccount account = invitedAccount(inviteToken, LocalDateTime.now().minusDays(2));
        when(magicLinkService.exchange(inviteToken))
            .thenThrow(new BusinessException("Supplier magic link is invalid or expired"));

        assertThatThrownBy(() -> externalService.login(
            new SupplierPortalLoginRequest(account.getLoginEmail(), inviteToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("expired");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void rfqPortalRandomTokenReturnsSessionNotFound() {
        String token = "random-session";
        String hash = sha256(token);
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(hash)).thenReturn(Optional.empty());
        when(accountRepository.findByPortalTokenHash(hash)).thenReturn(List.of());
        when(accountRepository.countByPortalTokenHash(hash)).thenReturn(0L);

        assertThatThrownBy(() -> externalService.me(token))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal session not found");
    }

    @Test
    void rfqPortalExpiredTokenReturnsSessionExpired() {
        String token = "expired-session";
        SupplierPortalAccount account = activeSessionAccount(token);
        account.setPortalTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> externalService.me(token))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal session expired");
    }

    @Test
    void rfqPortalInactiveAccountReturnsAccountNotActive() {
        String token = "inactive-session";
        SupplierPortalAccount account = activeSessionAccount(token);
        account.setStatus("SUSPENDED");
        when(accountRepository.findByPortalTokenHashAndIsDeletedFalse(sha256(token))).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> externalService.me(token))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Supplier portal account is not active");
    }

    @Test
    void administrativeActivationFailsClosedAndCannotConsumeInvitation() {
        String inviteToken = "activation-token";
        SupplierPortalAccount account = invitedAccount(inviteToken, LocalDateTime.now());
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));

        SupplierPortalAccountService service = accountService();
        assertThatThrownBy(() -> service.activate(
            account.getId(), new SupplierPortalActionRequest("accepted", inviteToken)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("public invitation exchange");

        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.INVITED.name());
        assertThat(account.getInviteTokenHash()).isEqualTo(sha256(inviteToken));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void globalAdministratorCannotMutatePortalAccountFromAnotherCompany() {
        SupplierPortalAccount account = invitedAccount("cross-company", LocalDateTime.now());
        account.setCompanyId(UUID.randomUUID());
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("global-admin", "n/a");
        authentication.setDetails(new AccessScope.RequestScope(true));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> accountService().softDelete(account.getId()))
            .isInstanceOf(NotFoundException.class);

        assertThat(account.isDeleted()).isFalse();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void cannotSuspendLastActiveSupplierAdmin() {
        SupplierPortalAccount account = activeSupplierAdmin();
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(accountRepository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        )).thenReturn(1L);

        assertThatThrownBy(() -> accountService().suspend(account.getId(), new SupplierPortalActionRequest("risk hold", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot suspend the last active supplier portal admin");

        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.ACTIVE.name());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void cannotRevokeLastActiveSupplierAdmin() {
        SupplierPortalAccount account = activeSupplierAdmin();
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(accountRepository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        )).thenReturn(1L);

        assertThatThrownBy(() -> accountService().revoke(account.getId(), new SupplierPortalActionRequest("offboarded", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot revoke the last active supplier portal admin");

        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.ACTIVE.name());
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void cannotDeleteLastActiveSupplierAdmin() {
        SupplierPortalAccount account = activeSupplierAdmin();
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(accountRepository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        )).thenReturn(1L);

        assertThatThrownBy(() -> accountService().softDelete(account.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot delete the last active supplier portal admin");

        assertThat(account.isDeleted()).isFalse();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void cannotDemoteLastActiveSupplierAdmin() {
        SupplierPortalAccount account = activeSupplierAdmin();
        Supplier supplier = supplier(account.getSupplierId());
        SupplierPortalAccountUpdateRequest request = new SupplierPortalAccountUpdateRequest();
        request.setName(account.getName());
        request.setLoginEmail(account.getLoginEmail());
        request.setContactPerson(account.getContactPerson());
        request.setPortalRole(SupplierPortalRole.SUPPLIER_USER.name());
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(complianceService.validateSupplier(account.getSupplierId())).thenReturn(supplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getLoginEmail()
        )).thenReturn(Optional.of(account));
        when(accountRepository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        )).thenReturn(1L);

        assertThatThrownBy(() -> accountService().update(account.getId(), request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Cannot demote the last active supplier portal admin");

        verify(accountRepository, never()).save(any());
    }

    @Test
    void canDemoteSupplierAdminWhenAnotherActiveAdminRemains() {
        SupplierPortalAccount account = activeSupplierAdmin();
        Supplier supplier = supplier(account.getSupplierId());
        SupplierPortalAccountUpdateRequest request = new SupplierPortalAccountUpdateRequest();
        request.setName(account.getName());
        request.setLoginEmail(account.getLoginEmail());
        request.setPortalRole(SupplierPortalRole.SUPPLIER_USER.name());
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(complianceService.validateSupplier(account.getSupplierId())).thenReturn(supplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getLoginEmail()
        )).thenReturn(Optional.of(account));
        when(accountRepository.countByTenantIdAndCompanyIdAndSupplier_IdAndPortalRoleAndStatusAndIsDeletedFalse(
            TENANT,
            companyId,
            account.getSupplierId(),
            SupplierPortalRole.SUPPLIER_ADMIN.name(),
            SupplierPortalStatus.ACTIVE.name()
        )).thenReturn(2L);
        when(accountRepository.save(account)).thenReturn(account);

        SupplierPortalAccountResponse response = accountService().update(account.getId(), request);

        assertThat(response.getPortalRole()).isEqualTo(SupplierPortalRole.SUPPLIER_USER.name());
        verify(accountRepository).save(account);
    }

    @Test
    void repeatedRfqInvitationForSameSupplierAndEmailReusesExistingPortalAccount() {
        SupplierPortalAccount account = activeSupplierAdmin();
        Supplier supplier = supplier(account.getSupplierId());
        supplier.setEmail(account.getLoginEmail().toUpperCase(Locale.ROOT));
        when(complianceService.validateSupplier(supplier.getId())).thenReturn(supplier);
        when(accountRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
            TENANT,
            companyId,
            supplier.getId()
        )).thenReturn(List.of(account));

        SupplierPortalAccount first = accountService().ensureForRfqInvitation(supplier.getId(), supplier.getEmail());
        SupplierPortalAccount second = accountService().ensureForRfqInvitation(supplier.getId(), supplier.getEmail());

        assertThat(first.getId()).isEqualTo(account.getId());
        assertThat(second.getId()).isEqualTo(account.getId());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void reinviteQueuesFreshLinkWithoutReturningASecret() {
        SupplierPortalAccount account = invitedAccount("old-invite", LocalDateTime.now().minusHours(2));
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        account.setVersion(7L);
        account.setPortalTokenHash(sha256("old-session"));
        account.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(4));
        when(accountRepository.findByIdAndIsDeletedFalse(account.getId()))
            .thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(account)).thenAnswer(invocation -> {
            account.setVersion(8L);
            return account;
        });
        AtomicReference<SupplierPortalAccountResponse> response = new AtomicReference<>();

        assertThatCode(() -> {
            var method = SupplierPortalAccountService.class.getMethod(
                "reinvite", UUID.class, Long.class, String.class);
            response.set((SupplierPortalAccountResponse) method.invoke(
                accountService(), account.getId(), 7L, "Session recovery"));
        }).doesNotThrowAnyException();

        assertThat(response.get()).isNotNull();
        assertThat(response.get().getInviteToken()).isNull();
        assertThat(response.get().getInviteTokenHash()).isNull();
        assertThat(response.get().getVersion()).isEqualTo(8L);
        assertThat(account.getStatus()).isEqualTo(SupplierPortalStatus.INVITED.name());
        assertThat(account.getInviteTokenHash()).isNull();
        assertThat(account.getInviteTokenExpiresAt()).isNull();
        assertThat(account.getPortalTokenHash()).isNull();
        assertThat(account.getPortalTokenExpiresAt()).isNull();
        verify(magicLinkService).queueLatestForAccount(account, "ADMIN_REINVITE: Session recovery");
    }

    @Test
    void idempotentReplayContainsNoRawSecret() {
        AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
        when(idempotencyRecordRepository
            .findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
                eq(TENANT), eq(companyId), eq("SUPPLIER_PORTAL_REINVITE"), any(UUID.class), eq("reinvite-key")
            )
        ).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(idempotencyRecordRepository.save(any(IdempotencyRecord.class)))
            .thenAnswer(invocation -> {
                IdempotencyRecord record = invocation.getArgument(0);
                stored.set(record);
                return record;
            });
        SupplierPortalAccountResponse issued = new SupplierPortalAccountResponse();
        issued.setId(UUID.randomUUID());
        issued.setVersion(8L);
        AtomicInteger rotations = new AtomicInteger();

        IdempotencyService service = new IdempotencyService(
            idempotencyRecordRepository,
            new ObjectMapper().findAndRegisterModules()
        );
        UUID accountId = issued.getId();
        Object command = java.util.Map.of("expectedVersion", 7L);
        java.util.function.Supplier<SupplierPortalAccountResponse> action = () -> {
            rotations.incrementAndGet();
            return issued;
        };
        SupplierPortalAccountResponse first = service.execute(
            "SUPPLIER_PORTAL_REINVITE", accountId, "reinvite-key", command,
            SupplierPortalAccountResponse.class, action
        );
        SupplierPortalAccountResponse replay = service.execute(
            "SUPPLIER_PORTAL_REINVITE", accountId, "reinvite-key", command,
            SupplierPortalAccountResponse.class, action
        );

        assertThat(first.getInviteToken()).isNull();
        assertThat(replay.getInviteToken()).isNull();
        assertThat(rotations).hasValue(1);
    }

    private SupplierPortalAccountService accountService() {
        return new SupplierPortalAccountService(
            accountRepository,
            new SupplierPortalAccountMapper(),
            auditService,
            complianceService,
            magicLinkService
        );
    }

    private SupplierPortalAccount invitedAccount(String token, LocalDateTime invitedAt) {
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setId(UUID.randomUUID());
        account.setTenantId(TENANT);
        account.setCompanyId(companyId);
        account.setSupplierId(UUID.randomUUID());
        account.setCode("PORTAL-" + UUID.randomUUID().toString().substring(0, 8));
        account.setName("Supplier portal account");
        account.setLoginEmail("portal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.uz");
        account.setStatus(SupplierPortalStatus.INVITED.name());
        account.setInvitedAt(invitedAt);
        account.setInviteTokenHash(sha256(token));
        return account;
    }

    private SupplierPortalAccount activeSessionAccount(String token) {
        SupplierPortalAccount account = invitedAccount("invite-" + token, LocalDateTime.now());
        account.setStatus(SupplierPortalStatus.ACTIVE.name());
        account.setPortalTokenHash(sha256(token));
        account.setPortalTokenExpiresAt(LocalDateTime.now().plusHours(1));
        return account;
    }

    private SupplierPortalAccount activeSupplierAdmin() {
        SupplierPortalAccount account = activeSessionAccount("session-" + UUID.randomUUID());
        account.setPortalRole(SupplierPortalRole.SUPPLIER_ADMIN.name());
        return account;
    }

    private Supplier supplier(UUID id) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setTenantId(TENANT);
        supplier.setCompanyId(companyId);
        supplier.setCode("SUP-" + id.toString().substring(0, 8));
        supplier.setName("Supplier");
        supplier.setEmail("supplier-" + id.toString().substring(0, 8) + "@example.uz");
        supplier.setStatus("ACTIVE");
        return supplier;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
