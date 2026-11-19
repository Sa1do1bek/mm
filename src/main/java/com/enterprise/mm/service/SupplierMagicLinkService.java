package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.entity.Company;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.RfqInvitationDelivery;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierMagicLink;
import com.enterprise.mm.entity.SupplierMagicLinkRequest;
import com.enterprise.mm.entity.SupplierPoDispatch;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.enums.SupplierMagicLinkRequestReason;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkExchangeResponse;
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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.enterprise.mm.service.rfq.RfqMetrics;

@Service
@RequiredArgsConstructor
public class SupplierMagicLinkService implements RfqDeliveryMagicLinkIssuer {
    private static final int TOKEN_BYTES = 32;
    private static final int LINK_HOURS = 24;
    private static final int SESSION_HOURS = 24;
    private static final int MAX_EMAIL_REQUESTS_PER_HOUR = 5;
    private static final int MAX_IP_REQUESTS_PER_HOUR = 10;
    private static final List<String> PO_TERMINAL_DISPATCH_STATUSES = List.of("SUPERSEDED", "CANCELLED");
    private static final String INVALID_LINK_MESSAGE = "Supplier magic link is invalid or expired";
    private static final String NEUTRAL_REQUEST_MESSAGE =
        "If the account is eligible, a login link has been sent.";
    private static final String NO_ACTIVE_INVITATION_MESSAGE =
        "Supplier has no active RFQ invitation; a login link will be sent when an RFQ is published.";

    private final SupplierMagicLinkRepository linkRepository;
    private final SupplierMagicLinkRequestRepository requestRepository;
    private final SupplierPortalAccountRepository accountRepository;
    private final SupplierPoDispatchRepository poDispatchRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final RfqInvitationDeliveryRepository deliveryRepository;
    private final RfqPublishedVersionRepository publishedVersionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final CompanyRepository companyRepository;
    private final ObjectProvider<RfqDeliveryService> deliveryServiceProvider;
    private final RfqMailProperties properties;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private RfqMetrics metrics;



    @Autowired
    void setMetrics(RfqMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public IssuedLink issueForDelivery(RfqInvitationDelivery delivery) {
        if (delivery == null || delivery.getInvitationId() == null || delivery.getSupplierId() == null) {
            throw new BusinessException("RFQ delivery cannot issue a supplier magic link");
        }
        String tenantId = delivery.getTenantId();
        UUID companyId = delivery.getCompanyId();
        return withContext(tenantId, companyId, () -> {
            SupplierPortalAccount account = accountRepository
                .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                    tenantId, companyId, delivery.getSupplierId()
                )
                .stream()
                .filter(this::isInvitedOrActive)
                .min(this::portalDeliveryAccountOrder)
                .orElseThrow(() -> new BusinessException("Eligible supplier portal account not found"));
            RfqSupplierInvitation invitation = invitationRepository.findById(delivery.getInvitationId())
                .filter(value -> invitationMatches(value, tenantId, companyId, delivery.getSupplierId()))
                .orElseThrow(() -> new BusinessException("Active RFQ supplier invitation not found"));

            LocalDateTime now = LocalDateTime.now(clock);
            List<SupplierMagicLink> existing = linkRepository
                .findByTenantIdAndCompanyIdAndInvitationIdAndStatusAndIsDeletedFalse(
                    tenantId, companyId, invitation.getId(), "ISSUED"
                );
            for (SupplierMagicLink oldLink : existing) {
                oldLink.setStatus("REVOKED");
                oldLink.setRevokedAt(now);
            }
            if (!existing.isEmpty()) {
                linkRepository.saveAll(existing);
            }

            String rawToken = newToken();
            SupplierMagicLink link = new SupplierMagicLink();
            link.setTenantId(tenantId);
            link.setCompanyId(companyId);
            link.setCode("SUPPLIER-LINK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            link.setName("Supplier RFQ magic link");
            link.setStatus("ISSUED");
            link.setPortalAccountId(account.getId());
            link.setSupplierId(delivery.getSupplierId());
            link.setInvitationId(invitation.getId());
            link.setTokenHash(sha256(rawToken));
            link.setReturnPath("/supplier-portal/rfq/" + requireRfqId(delivery));
            link.setExpiresAt(now.plusHours(LINK_HOURS));
            SupplierMagicLink saved = linkRepository.save(link);
            auditService.log("SupplierMagicLink", saved.getId(), "ISSUED", invitation.getId().toString(), null, null);
            if (metrics != null) {
                metrics.magicLink("ISSUED");
            }
            URI url = URI.create(normalizedPortalBaseUrl() + "/magic-link?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
            return new IssuedLink(saved.getId(), rawToken, url);
        });
    }

    /**
     * PO-scoped counterpart to {@link #issueForDelivery(RfqInvitationDelivery)},
     * mirroring its shape but keyed on {@code purchaseOrderId}/dispatch instead of
     * {@code invitationId}. Additive only -- does not touch RFQ invitation issuance
     * or consumption in any way. Populates {@link SupplierMagicLink#getPurchaseOrderId()}
     * and leaves {@code invitationId} null, exactly the parallel-FK design decided for
     * this phase (see docs/CURRENT_TASK.md).
     */
    @Transactional
    public IssuedLink issueForPurchaseOrderDispatch(SupplierPoDispatch dispatch) {
        if (dispatch == null || dispatch.getPurchaseOrderId() == null || dispatch.getSupplierId() == null) {
            throw new BusinessException("Supplier PO dispatch cannot issue a supplier magic link");
        }
        String tenantId = dispatch.getTenantId();
        UUID companyId = dispatch.getCompanyId();
        return withContext(tenantId, companyId, () -> {
            SupplierPortalAccount account = accountRepository
                .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                    tenantId, companyId, dispatch.getSupplierId()
                )
                .stream()
                .filter(this::isInvitedOrActive)
                .min(this::portalDeliveryAccountOrder)
                .orElseThrow(() -> new BusinessException("Eligible supplier portal account not found"));

            LocalDateTime now = LocalDateTime.now(clock);
            List<SupplierMagicLink> existing = linkRepository
                .findByTenantIdAndCompanyIdAndPurchaseOrderIdAndStatusAndIsDeletedFalse(
                    tenantId, companyId, dispatch.getPurchaseOrderId(), "ISSUED"
                );
            for (SupplierMagicLink oldLink : existing) {
                oldLink.setStatus("REVOKED");
                oldLink.setRevokedAt(now);
            }
            if (!existing.isEmpty()) {
                linkRepository.saveAll(existing);
            }

            String rawToken = newToken();
            SupplierMagicLink link = new SupplierMagicLink();
            link.setTenantId(tenantId);
            link.setCompanyId(companyId);
            link.setCode("SUPPLIER-LINK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            link.setName("Supplier PO magic link");
            link.setStatus("ISSUED");
            link.setPortalAccountId(account.getId());
            link.setSupplierId(dispatch.getSupplierId());
            link.setPurchaseOrderId(dispatch.getPurchaseOrderId());
            link.setTokenHash(sha256(rawToken));
            link.setReturnPath("/supplier-portal/purchase-orders/" + dispatch.getPurchaseOrderId());
            link.setExpiresAt(now.plusHours(LINK_HOURS));
            SupplierMagicLink saved = linkRepository.save(link);
            auditService.log("SupplierMagicLink", saved.getId(), "ISSUED", dispatch.getPurchaseOrderId().toString(), null, null);
            if (metrics != null) {
                metrics.magicLink("ISSUED");
            }
            URI url = URI.create(normalizedPortalBaseUrl() + "/magic-link?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
            return new IssuedLink(saved.getId(), rawToken, url);
        });
    }

    @Transactional
    public IssuedLink issueForPortalAccountActivation(SupplierPortalAccount account) {
        if (account == null || account.getId() == null || account.getSupplierId() == null) {
            throw new BusinessException("Supplier portal account cannot issue an activation link");
        }
        String tenantId = account.getTenantId();
        UUID companyId = account.getCompanyId();
        return withContext(tenantId, companyId, () -> {
            SupplierPortalAccount scopedAccount = accountRepository.findByIdAndIsDeletedFalse(account.getId())
                .filter(value -> Objects.equals(value.getTenantId(), tenantId))
                .filter(value -> Objects.equals(value.getCompanyId(), companyId))
                .filter(value -> Objects.equals(value.getSupplierId(), account.getSupplierId()))
                .filter(this::isInvitedOrActive)
                .orElseThrow(() -> new BusinessException("Eligible supplier portal account not found"));

            LocalDateTime now = LocalDateTime.now(clock);
            List<SupplierMagicLink> existing = linkRepository.findAccountActivationLinks(
                tenantId,
                companyId,
                scopedAccount.getId(),
                "ISSUED"
            );
            for (SupplierMagicLink oldLink : existing) {
                oldLink.setStatus("REVOKED");
                oldLink.setRevokedAt(now);
            }
            if (!existing.isEmpty()) {
                linkRepository.saveAll(existing);
            }

            String rawToken = newToken();
            SupplierMagicLink link = new SupplierMagicLink();
            link.setTenantId(tenantId);
            link.setCompanyId(companyId);
            link.setCode("SUPPLIER-ACTIVATION-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            link.setName("Supplier portal account activation link");
            link.setStatus("ISSUED");
            link.setPortalAccountId(scopedAccount.getId());
            link.setSupplierId(scopedAccount.getSupplierId());
            link.setTokenHash(sha256(rawToken));
            link.setReturnPath("/supplier-portal");
            link.setExpiresAt(now.plusHours(LINK_HOURS));
            SupplierMagicLink saved = linkRepository.save(link);
            auditService.log("SupplierMagicLink", saved.getId(), "ISSUED", scopedAccount.getId().toString(), null, null);
            if (metrics != null) {
                metrics.magicLink("ISSUED");
            }
            URI url = URI.create(normalizedPortalBaseUrl() + "/magic-link?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
            return new IssuedLink(saved.getId(), rawToken, url);
        });
    }

    @Transactional
    public MagicLinkExchangeResponse exchange(String rawToken) {
        String tokenHash = sha256(requireToken(rawToken));
        LocalDateTime now = LocalDateTime.now(clock);
        SupplierMagicLink link = linkRepository.lockUsable(tokenHash, now)
            .orElseThrow(this::invalidLink);
        return withContext(link.getTenantId(), link.getCompanyId(), () -> exchange(link, now));
    }

    @Transactional
    public MagicLinkRequestResponse requestLink(String companyCode, String email, String ipAddress) {
        String normalizedCompanyCode = normalizeCompanyCode(companyCode);
        String normalizedEmail = normalizeEmail(email);
        List<Company> companies = companyRepository
            .findByCodeIgnoreCaseAndIsDeletedFalse(normalizedCompanyCode);
        Company company = companies.size() == 1 ? companies.get(0) : null;
        String tenantId = company == null ? TenantContext.currentTenant() : company.getTenantId();
        UUID companyId = company == null ? null : company.getId();
        String emailHash = sha256(normalizedCompanyCode + "\u0000" + normalizedEmail);
        String ipHash = sha256(magicLinkPepper() + "\u0000" + normalizeIp(ipAddress));
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusHours(1);
        boolean rateLimited = requestRepository.countRecentForEmail(tenantId, companyId, emailHash, since)
            >= MAX_EMAIL_REQUESTS_PER_HOUR
            || requestRepository.countRecentForIp(tenantId, ipHash, since) >= MAX_IP_REQUESTS_PER_HOUR;

        SupplierPortalAccount eligibleAccount = null;
        RfqInvitationDelivery renewalDelivery = null;
        SupplierPoDispatch renewalPoDispatch = null;
        SupplierMagicLinkRequestReason reason = SupplierMagicLinkRequestReason.ACCOUNT_NOT_FOUND;
        if (rateLimited) {
            reason = SupplierMagicLinkRequestReason.RATE_LIMITED;
        } else if (companies.isEmpty()) {
            reason = SupplierMagicLinkRequestReason.COMPANY_NOT_FOUND;
        } else if (companies.size() > 1) {
            reason = SupplierMagicLinkRequestReason.COMPANY_AMBIGUOUS;
        } else {
            Optional<SupplierPortalAccount> account = accountRepository
                .findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
                    tenantId, companyId, normalizedEmail
                );
            if (account.isEmpty()) {
                reason = SupplierMagicLinkRequestReason.ACCOUNT_NOT_FOUND;
            } else if (!isInvitedOrActive(account.get())) {
                reason = SupplierMagicLinkRequestReason.ACCOUNT_NOT_ELIGIBLE;
            } else {
                eligibleAccount = account.get();
                PortalDocumentLookup lookup = latestPortalDocumentLookup(eligibleAccount);
                reason = lookup.reason();
                renewalDelivery = lookup.delivery().orElse(null);
                renewalPoDispatch = lookup.poDispatch().orElse(null);
            }
        }
        persistRequest(tenantId, companyId, emailHash, ipHash, now, eligibleAccount != null, reason);
        if (metrics != null) {
            metrics.magicLink(requestMetricOutcome(reason));
        }
        if (renewalDelivery != null) {
            UUID deliveryId = renewalDelivery.getId();
            withContext(tenantId, companyId, () -> {
                deliveryServiceProvider.getObject().queueRenewal(deliveryId, "SELF_SERVICE_LOGIN_RENEWAL");
                return null;
            });
        }
        if (renewalPoDispatch != null) {
            UUID dispatchId = renewalPoDispatch.getId();
            String loginEmail = eligibleAccount.getLoginEmail();
            withContext(tenantId, companyId, () -> {
                queuePoDispatchRenewal(dispatchId, loginEmail);
                return null;
            });
        }
        return new MagicLinkRequestResponse(NEUTRAL_REQUEST_MESSAGE);
    }

    @Transactional
    public void queueLatestForAccount(SupplierPortalAccount account, String reason) {
        RfqInvitationDelivery delivery = latestDelivery(account)
            .orElseThrow(() -> new BusinessException(NO_ACTIVE_INVITATION_MESSAGE));
        deliveryServiceProvider.getObject().queueRenewal(delivery.getId(), reason);
    }

    private MagicLinkExchangeResponse exchange(SupplierMagicLink link, LocalDateTime now) {
        SupplierPortalAccount account = accountRepository.findByIdAndIsDeletedFalse(link.getPortalAccountId())
            .filter(value -> accountMatches(value, link))
            .filter(this::isInvitedOrActive)
            .orElseThrow(this::invalidLink);
        if (link.getPurchaseOrderId() != null) {
            // PO-scoped link (see issueForPurchaseOrderDispatch): invitationId is
            // intentionally null for this branch, so validate against the PO instead of
            // an RFQ invitation. RFQ links (invitationId set, purchaseOrderId null) are
            // unaffected and keep using the invitation check below.
            purchaseOrderRepository.findById(link.getPurchaseOrderId())
                .filter(value -> purchaseOrderMatches(value, link.getTenantId(), link.getCompanyId(), link.getSupplierId()))
                .orElseThrow(this::invalidLink);
        } else if (link.getInvitationId() != null) {
            invitationRepository.findById(link.getInvitationId())
                .filter(value -> invitationMatches(value, link.getTenantId(), link.getCompanyId(), link.getSupplierId()))
                .orElseThrow(this::invalidLink);
        }
        Supplier supplier = supplierRepository
            .findByTenantIdAndIdAndIsDeletedFalse(link.getTenantId(), link.getSupplierId())
            .filter(value -> Objects.equals(value.getCompanyId(), link.getCompanyId()))
            .orElseThrow(this::invalidLink);
        Company company = companyRepository
            .findByTenantIdAndIdAndIsDeletedFalse(link.getTenantId(), link.getCompanyId())
            .orElseThrow(this::invalidLink);

        String sessionToken = newToken();
        String sessionTokenHash = sha256(sessionToken);
        LocalDateTime sessionExpiresAt = now.plusHours(SESSION_HOURS);
        account.setPortalTokenHash(sessionTokenHash);
        account.setPortalTokenExpiresAt(sessionExpiresAt);
        account.setLastLoginAt(now);
        account.setInviteTokenHash(null);
        account.setInviteTokenExpiresAt(null);
        if (SupplierPortalStatus.INVITED.name().equals(account.getStatus())) {
            account.setStatus(SupplierPortalStatus.ACTIVE.name());
            account.setActivatedAt(now);
        }
        accountRepository.saveAndFlush(account);
        accountRepository.findByPortalTokenHashAndIsDeletedFalse(sessionTokenHash)
            .filter(saved -> Objects.equals(saved.getId(), account.getId()))
            .orElseThrow(() -> new BusinessException("Supplier portal session was not persisted"));
        auditService.log("SupplierMagicLink", link.getId(), "CONSUMED", account.getId().toString(), null, null);
        if (metrics != null) {
            metrics.magicLink("CONSUMED");
        }
        return new MagicLinkExchangeResponse(
            sessionToken,
            sessionExpiresAt,
            account.getId(),
            account.getSupplierId(),
            supplier.getName(),
            company.getCode(),
            account.getStatus(),
            link.getReturnPath()
        );
    }

    private void persistRequest(
        String tenantId,
        UUID companyId,
        String emailHash,
        String ipHash,
        LocalDateTime now,
        boolean matched,
        SupplierMagicLinkRequestReason reason
    ) {
        SupplierMagicLinkRequest request = new SupplierMagicLinkRequest();
        request.setTenantId(tenantId);
        request.setCompanyId(companyId);
        request.setUnresolvedCompany(companyId == null);
        request.setCode("MAGIC-LINK-REQUEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        request.setName("Supplier magic-link request");
        request.setStatus(reason == SupplierMagicLinkRequestReason.RATE_LIMITED ? "RATE_LIMITED" : "ACCEPTED");
        request.setNormalizedEmailHash(emailHash);
        request.setRequestIpHash(ipHash);
        request.setRequestedAt(now);
        request.setMatchedAccount(matched);
        request.setReasonCode(reason);
        requestRepository.save(request);
        requestRepository.flush();
    }

    private String requestMetricOutcome(SupplierMagicLinkRequestReason reason) {
        return switch (reason) {
            case RATE_LIMITED -> "RATE_LIMITED";
            case MATCHED_QUEUED, NO_ACTIVE_INVITATION, NO_DELIVERY_FOUND, NO_ACTIVE_PORTAL_DOCUMENT -> "REQUEST_MATCHED";
            default -> "REQUEST_UNKNOWN";
        };
    }

    private Optional<RfqInvitationDelivery> latestDelivery(SupplierPortalAccount account) {
        return latestDeliveryLookup(account).delivery();
    }

    private DeliveryLookup latestDeliveryLookup(SupplierPortalAccount account) {
        if (account == null || !isInvitedOrActive(account)) {
            return new DeliveryLookup(Optional.empty(), SupplierMagicLinkRequestReason.ACCOUNT_NOT_ELIGIBLE);
        }
        List<RfqSupplierInvitation> invitations = invitationRepository.findActiveForSupplierNewest(
            account.getTenantId(), account.getCompanyId(), account.getSupplierId()
        );
        if (invitations.isEmpty()) {
            return new DeliveryLookup(Optional.empty(), SupplierMagicLinkRequestReason.NO_ACTIVE_INVITATION);
        }
        for (RfqSupplierInvitation invitation : invitations) {
            List<RfqInvitationDelivery> deliveries = deliveryRepository
                .findByTenantIdAndCompanyIdAndInvitationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    account.getTenantId(), account.getCompanyId(), invitation.getId()
                );
            if (!deliveries.isEmpty()) {
                return new DeliveryLookup(
                    Optional.of(deliveries.get(0)),
                    SupplierMagicLinkRequestReason.MATCHED_QUEUED
                );
            }
        }
        return new DeliveryLookup(Optional.empty(), SupplierMagicLinkRequestReason.NO_DELIVERY_FOUND);
    }

    private PortalDocumentLookup latestPortalDocumentLookup(SupplierPortalAccount account) {
        DeliveryLookup deliveryLookup = latestDeliveryLookup(account);
        Optional<SupplierPoDispatch> poDispatch = latestPoDispatch(account);
        if (deliveryLookup.delivery().isPresent() || poDispatch.isPresent()) {
            return new PortalDocumentLookup(
                deliveryLookup.delivery(),
                poDispatch,
                SupplierMagicLinkRequestReason.MATCHED_QUEUED
            );
        }
        return new PortalDocumentLookup(
            Optional.empty(),
            Optional.empty(),
            SupplierMagicLinkRequestReason.NO_ACTIVE_PORTAL_DOCUMENT
        );
    }

    private Optional<SupplierPoDispatch> latestPoDispatch(SupplierPortalAccount account) {
        if (account == null || !isInvitedOrActive(account)) {
            return Optional.empty();
        }
        return poDispatchRepository.findCurrentPortalCandidatesForSupplierNewest(
                account.getTenantId(),
                account.getCompanyId(),
                account.getSupplierId(),
                PO_TERMINAL_DISPATCH_STATUSES,
                PageRequest.of(0, 1)
            ).stream()
            .findFirst();
    }

    private void queuePoDispatchRenewal(UUID dispatchId, String loginEmail) {
        SupplierPoDispatch dispatch = poDispatchRepository.findByIdAndIsDeletedFalse(dispatchId).orElse(null);
        if (dispatch == null || !dispatch.isCurrent() || PO_TERMINAL_DISPATCH_STATUSES.contains(dispatch.getDispatchStatus())) {
            return;
        }
        dispatch.setRecipientEmail(loginEmail);
        dispatch.setDispatchStatus("QUEUED");
        dispatch.setQueuedAt(LocalDateTime.now(clock));
        dispatch.setLastDeliveryError(null);
        poDispatchRepository.save(dispatch);
        auditService.log(
            "PurchaseOrder",
            dispatch.getPurchaseOrderId(),
            "PO_SUPPLIER_DISPATCH_QUEUED",
            "SELF_SERVICE_LOGIN_RENEWAL",
            null,
            null
        );
    }

    private record DeliveryLookup(
        Optional<RfqInvitationDelivery> delivery,
        SupplierMagicLinkRequestReason reason
    ) {
    }

    private record PortalDocumentLookup(
        Optional<RfqInvitationDelivery> delivery,
        Optional<SupplierPoDispatch> poDispatch,
        SupplierMagicLinkRequestReason reason
    ) {
    }

    private UUID requireRfqId(RfqInvitationDelivery delivery) {
        if (delivery.getPublishedVersion() != null && delivery.getPublishedVersion().getRfqId() != null) {
            return delivery.getPublishedVersion().getRfqId();
        }
        if (delivery.getPublishedVersionId() == null) {
            throw new BusinessException("RFQ delivery published version is unavailable");
        }
        return publishedVersionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                delivery.getTenantId(), delivery.getCompanyId(), delivery.getPublishedVersionId()
            )
            .map(value -> {
                if (value.getRfqId() == null) {
                    throw new BusinessException("RFQ delivery published version is unavailable");
                }
                return value.getRfqId();
            })
            .orElseThrow(() -> new BusinessException("RFQ delivery published version is unavailable"));
    }

    private boolean accountMatches(SupplierPortalAccount account, SupplierMagicLink link) {
        return Objects.equals(account.getTenantId(), link.getTenantId())
            && Objects.equals(account.getCompanyId(), link.getCompanyId())
            && Objects.equals(account.getSupplierId(), link.getSupplierId());
    }

    private boolean invitationMatches(
        RfqSupplierInvitation invitation,
        String tenantId,
        UUID companyId,
        UUID supplierId
    ) {
        return Objects.equals(invitation.getTenantId(), tenantId)
            && Objects.equals(invitation.getCompanyId(), companyId)
            && Objects.equals(invitation.getSupplierId(), supplierId)
            && "INVITED".equals(invitation.getStatus())
            && !invitation.isDeleted();
    }

    private boolean purchaseOrderMatches(
        PurchaseOrder purchaseOrder,
        String tenantId,
        UUID companyId,
        UUID supplierId
    ) {
        return Objects.equals(purchaseOrder.getTenantId(), tenantId)
            && Objects.equals(purchaseOrder.getCompanyId(), companyId)
            && Objects.equals(purchaseOrder.getSupplierId(), supplierId)
            && !purchaseOrder.isDeleted();
    }

    private boolean isInvitedOrActive(SupplierPortalAccount account) {
        return SupplierPortalStatus.INVITED.name().equals(account.getStatus())
            || SupplierPortalStatus.ACTIVE.name().equals(account.getStatus());
    }

    private int portalDeliveryAccountOrder(SupplierPortalAccount left, SupplierPortalAccount right) {
        int leftRank = portalDeliveryAccountRank(left);
        int rightRank = portalDeliveryAccountRank(right);
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

    private int portalDeliveryAccountRank(SupplierPortalAccount account) {
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

    private String normalizedPortalBaseUrl() {
        String value = properties.getPortalBaseUrl();
        if (value == null || value.isBlank()) {
            throw new BusinessException("Supplier portal base URL is not configured");
        }
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw invalidLink();
        }
        return value.trim();
    }

    private String normalizeCompanyCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private String magicLinkPepper() {
        String pepper = properties.getMagicLinkPepper();
        if (pepper == null || pepper.trim().length() < 16) {
            throw new BusinessException("Supplier magic-link security pepper is not configured");
        }
        return pepper.trim();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash supplier portal secret", exception);
        }
    }

    private BusinessException invalidLink() {
        if (metrics != null) {
            metrics.magicLink("INVALID");
        }
        return new BusinessException(INVALID_LINK_MESSAGE, "rfq.portal.link_invalid");
    }

    private <T> T withContext(String tenantId, UUID companyId, java.util.function.Supplier<T> action) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
        try {
            return action.get();
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }
}
