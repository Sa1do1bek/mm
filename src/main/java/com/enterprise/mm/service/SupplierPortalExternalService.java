package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.enterprise.mm.config.RfqMailProperties;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalLoginRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalLoginResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalMe;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalTeamInviteRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalTeamMember;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationItemRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationRequest;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalQuotationResponse;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqItem;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqDetail;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalRfqSummary;
import com.enterprise.mm.payload.SupplierPortalPublicDtos.SupplierPortalSubmissionRequest;
import com.enterprise.mm.payload.SupplierMagicLinkDtos.MagicLinkExchangeResponse;
import com.enterprise.mm.payload.SupplierPortalSubmissionResponse;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalDeclineResponse;
import com.enterprise.mm.payload.RfqProposalDtos.PortalProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.PortalReplacementRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalAttachmentView;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalLineRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalView;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.SupplierPortalSubmissionMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.SupplierPortalStatus;
import com.enterprise.mm.enums.SupplierPortalRole;
import com.enterprise.mm.enums.SupplierPortalSubmissionStatus;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.SupplierPortalSubmission;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierQuotationItem;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierPortalSubmissionRepository;
import com.enterprise.mm.repository.SupplierQuotationItemRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.service.rfq.RfqEmailTransport;
import com.enterprise.mm.service.rfq.RfqOutboundEmail;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierPortalExternalService {
    private final SupplierPortalAccountRepository accountRepository;
    private final SupplierPortalSubmissionRepository submissionRepository;
    private final SupplierPortalSubmissionMapper submissionMapper;
    private final SupplierRepository supplierRepository;
    private final RFQRepository rfqRepository;
    private final RFQItemRepository rfqItemRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierQuotationItemRepository quotationItemRepository;
    private final MaterialRepository materialRepository;
    private final AuditService auditService;
    private final SupplierMagicLinkService magicLinkService;
    private final SupplierQuotationRevisionService quotationRevisionService;
    private final IdempotencyService idempotencyService;
    private final RfqSupplierPortalQueryService supplierPortalQueryService;
    private final TenderSupplierAccessService tenderSupplierAccessService;
    private final RfqEmailTransport emailTransport;
    private final RfqMailProperties mailProperties;

    @Transactional
    @Deprecated(forRemoval = false)
    public SupplierPortalLoginResponse login(SupplierPortalLoginRequest request) {
        MagicLinkExchangeResponse response = magicLinkService.exchange(request.inviteToken());
        return new SupplierPortalLoginResponse(
            response.token(),
            response.expiresAt(),
            response.portalAccountId(),
            response.supplierId(),
            response.supplierName(),
            response.status()
        );
    }
    @Transactional(readOnly = true)
    public SupplierPortalMe me(String token) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            com.enterprise.mm.entity.Supplier supplier = supplier(account);
            return new SupplierPortalMe(
                account.getId(),
                account.getSupplierId(),
                supplier.getName(),
                supplier.getLegalName(),
                account.getContactPerson(),
                account.getLoginEmail(),
                account.getPortalRole(),
                account.getPhone(),
                account.getStatus(),
                account.getTermsAcceptedAt(),
                account.getLastLoginAt(),
                account.getLastSubmissionAt()
            );
        });
    }
    @Transactional(readOnly = true)
    public List<SupplierPortalTeamMember> listTeam(String token) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> accountRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                account.getTenantId(),
                account.getCompanyId(),
                account.getSupplierId()
            )
            .stream()
            .map(this::teamMember)
            .toList());
    }
    @Transactional
    public SupplierPortalTeamMember inviteTeamMember(String token, SupplierPortalTeamInviteRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireSupplierAdmin(account);
            String email = normalizeEmail(request.loginEmail());
            accountRepository.findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalse(
                    account.getTenantId(),
                    account.getCompanyId(),
                    email
                )
                .ifPresent(existing -> {
                    if (!Objects.equals(existing.getSupplierId(), account.getSupplierId())) {
                        throw new BusinessException("Supplier portal login email already belongs to another supplier");
                    }
                    throw new BusinessException("Supplier portal team member already exists");
                });
            SupplierPortalAccount member = new SupplierPortalAccount();
            member.setSupplierId(account.getSupplierId());
            member.setLoginEmail(email);
            member.setContactPerson(requireText(request.contactPerson(), "contactPerson is required"));
            member.setPhone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim());
            member.setPortalRole(normalizePortalRole(request.portalRole()));
            member.setCode("SUPPLIER_PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            member.setName(BusinessNameGenerator.workflowDocumentName(
                "SupplierPortalAccount",
                member.getContactPerson()
            ));
            member.setStatus(SupplierPortalStatus.INVITED.name());
            member.setInvitedAt(LocalDateTime.now());
            SupplierPortalAccount saved = accountRepository.save(member);
            var activationLink = magicLinkService.issueForPortalAccountActivation(saved);
            emailTransport.send(teamInviteEmail(saved, activationLink.url()));
            auditService.log("SupplierPortalAccount", saved.getId(), "PORTAL_TEAM_INVITE", saved.getLoginEmail(), null, null);
            return teamMember(saved);
        });
    }
    @Transactional
    public SupplierPortalMe acceptTerms(String token) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            account.setTermsAcceptedAt(LocalDateTime.now());
            accountRepository.save(account);
            auditService.log("SupplierPortalAccount", account.getId(), "PORTAL_ACCEPT_TERMS", account.getLoginEmail(), null, null);
            return me(token);
        });
    }
    @Transactional(readOnly = true)
    public List<SupplierPortalRfqSummary> listRfqs(String token) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            com.enterprise.mm.entity.Supplier supplier = supplier(account);
            if (!isActive(supplier)) {
                return List.of();
            }
            return rfqRepository.findByTenantIdAndCompanyIdAndIsDeletedFalse(
                    account.getTenantId(), account.getCompanyId()).stream()
                .filter(rfq -> tenderSupplierAccessService.canViewTender(rfq, supplier))
                .sorted(Comparator.comparing(RFQ::getDueDate).thenComparing(RFQ::getName))
                .map(rfq -> {
                    var quotation = quotationRepository.findCurrentSubmittedByRfqAndSupplier(
                        account.getTenantId(),
                        account.getCompanyId(),
                        rfq.getId(),
                        account.getSupplierId(),
                        currentQuotationStatuses()
                    ).stream().findFirst();
                    return new SupplierPortalRfqSummary(
                        rfq.getId(),
                        rfq.getCode(),
                        rfq.getName(),
                        rfq.getStatus(),
                        rfq.getDueDate(),
                        rfq.getTenderProcedureType(),
                        rfq.getSelectionRule(),
                        rfq.getMinQuotationsRequired(),
                        quotation.isPresent(),
                        quotation.map(SupplierQuotation::getStatus).orElse(null)
                    );
                })
                .toList();
        });
    }
    @Transactional(readOnly = true)
    public List<SupplierPortalRfqItem> listRfqItems(String token, UUID rfqId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            RFQ rfq = rfq(account, rfqId);
            requirePortalAccess(account, rfq);
            return rfqItemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(account.getTenantId(), rfq.getId()).stream()
                .filter(item -> account.getCompanyId().equals(item.getCompanyId()))
                .map(item -> {
                    Material material = item.getMaterialId() == null
                        ? null
                        : materialRepository.findByIdAndIsDeletedFalse(item.getMaterialId())
                            .filter(value -> !value.isDeleted())
                            .filter(value -> account.getTenantId().equals(value.getTenantId()))
                            .filter(value -> account.getCompanyId().equals(value.getCompanyId()))
                            .orElse(null);
                    return new SupplierPortalRfqItem(
                        item.getId(),
                        item.getMaterialId(),
                        material == null ? null : material.getCode(),
                        material == null ? item.getName() : material.getName(),
                        item.getUnit(),
                        amount(item.getQuantity())
                    );
                })
                .toList();
        });
    }

    @Transactional
    public SupplierPortalRfqDetail rfqDetail(String token, UUID rfqId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            return supplierPortalQueryService.detail(
                account.getTenantId(), account.getCompanyId(), account.getSupplierId(), rfqId
            );
        });
    }

    @Transactional(readOnly = true)
    public AttachmentService.AttachmentDownload downloadRfqArtifact(
        String token,
        UUID rfqId,
        UUID attachmentId
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            return supplierPortalQueryService.download(
                account.getTenantId(),
                account.getCompanyId(),
                account.getSupplierId(),
                rfqId,
                attachmentId
            );
        });
    }
    @Transactional
    public SupplierPortalQuotationResponse createQuotation(String token, UUID rfqId, SupplierPortalQuotationRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            RFQ rfq = rfq(account, rfqId);
            com.enterprise.mm.entity.Supplier supplier = supplier(account);
            requirePortalAccess(account, supplier, rfq);
            if (rfq.getActivePublishedVersionId() == null) {
                return createLegacyQuotation(account, supplier, rfq, request);
            }
            if (!quotationRepository.findCurrentSubmittedByRfqAndSupplier(
                account.getTenantId(), account.getCompanyId(), rfq.getId(), account.getSupplierId(),
                currentQuotationStatuses()
            ).isEmpty()) {
                throw new BusinessException("Supplier already submitted quotation for this RFQ");
            }
            PortalProposalRequest proposal = legacyProposal(request);
            ProposalView draft = quotationRevisionService.createPortalDraft(
                rfqId, account.getSupplierId(), proposal
            );
            ProposalView submitted = quotationRevisionService.submitPortalRevision(
                rfqId, account.getSupplierId(), draft.id()
            );
            account.setLastSubmissionAt(LocalDateTime.now());
            accountRepository.save(account);
            SupplierQuotation quotation = quotationRepository.findByIdAndIsDeletedFalse(submitted.id())
                .orElseThrow(() -> new BusinessException("Submitted supplier proposal not found"));
            return new SupplierPortalQuotationResponse(
                quotation.getId(),
                quotation.getCode(),
                quotation.getStatus(),
                quotation.getTotalAmount(),
                quotation.getCurrencyCode(),
                quotation.getSubmittedAt(),
                submitted.items().size()
            );
        });
    }

    @Transactional
    public ProposalView createProposalDraft(
        String token,
        UUID rfqId,
        PortalProposalRequest request,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            return idempotencyService.execute(
                "SUPPLIER_PORTAL_RFQ_PROPOSAL_DRAFT",
                rfqId,
                idempotencyKey,
                request,
                ProposalView.class,
                () -> quotationRevisionService.createPortalDraft(rfqId, account.getSupplierId(), request)
            );
        });
    }

    @Transactional
    public ProposalView updateProposalDraft(
        String token,
        UUID rfqId,
        UUID revisionId,
        PortalProposalRequest request,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            return idempotencyService.execute(
                "SUPPLIER_PORTAL_RFQ_PROPOSAL_DRAFT_UPDATE",
                revisionId,
                idempotencyKey,
                request,
                ProposalView.class,
                () -> quotationRevisionService.updatePortalDraft(
                    rfqId, account.getSupplierId(), revisionId, request
                )
            );
        });
    }

    @Transactional
    public ProposalView submitProposal(
        String token,
        UUID rfqId,
        UUID revisionId,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            ProposalView response = idempotencyService.execute(
                "SUPPLIER_PORTAL_RFQ_PROPOSAL_SUBMIT",
                revisionId,
                idempotencyKey,
                Map.of("rfqId", rfqId, "revisionId", revisionId),
                ProposalView.class,
                () -> quotationRevisionService.submitPortalRevision(
                    rfqId, account.getSupplierId(), revisionId
                )
            );
            account.setLastSubmissionAt(response.submittedAt());
            accountRepository.save(account);
            return response;
        });
    }

    @Transactional
    public ProposalView replaceProposal(
        String token,
        UUID rfqId,
        UUID revisionId,
        PortalReplacementRequest request,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            ProposalView response = idempotencyService.execute(
                "SUPPLIER_PORTAL_RFQ_PROPOSAL_REPLACE",
                revisionId,
                idempotencyKey,
                request,
                ProposalView.class,
                () -> quotationRevisionService.replacePortalRevision(
                    rfqId, account.getSupplierId(), revisionId, request
                )
            );
            account.setLastSubmissionAt(response.submittedAt());
            accountRepository.save(account);
            return response;
        });
    }

    @Transactional
    public ProposalAttachmentView addProposalAttachment(
        String token,
        UUID rfqId,
        UUID revisionId,
        MultipartFile file
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            Attachment attachment = quotationRevisionService.addPortalDraftAttachment(
                rfqId, account.getSupplierId(), revisionId, file
            );
            return new ProposalAttachmentView(
                attachment.getId(),
                attachment.getOriginalFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getAttachmentType()
            );
        });
    }

    @Transactional
    public void deleteProposalAttachment(
        String token,
        UUID rfqId,
        UUID revisionId,
        UUID attachmentId
    ) {
        SupplierPortalAccount account = resolve(token);
        withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            quotationRevisionService.deletePortalDraftAttachment(
                rfqId, account.getSupplierId(), revisionId, attachmentId
            );
            return null;
        });
    }

    @Transactional
    public PortalDeclineResponse decline(
        String token,
        UUID rfqId,
        PortalDeclineRequest request,
        String idempotencyKey
    ) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireProposalAccess(account, rfqId);
            return idempotencyService.execute(
                "SUPPLIER_PORTAL_RFQ_DECLINE",
                rfqId,
                idempotencyKey,
                request,
                PortalDeclineResponse.class,
                () -> quotationRevisionService.declinePortalInvitation(
                    rfqId, account.getSupplierId(), request
                )
            );
        });
    }
    @Transactional(readOnly = true)
    public List<SupplierPortalSubmissionResponse> listSubmissions(String token) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> submissionRepository.findByTenantIdAndCompanyIdAndPortalAccount_IdAndIsDeletedFalse(account.getTenantId(), account.getCompanyId(), account.getId()).stream()
            .sorted(Comparator.comparing(SupplierPortalSubmission::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(submissionMapper::toResponse)
            .toList());
    }
    @Transactional
    public SupplierPortalSubmissionResponse createSubmission(String token, SupplierPortalSubmissionRequest request) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            requireTerms(account);
            com.enterprise.mm.entity.Supplier supplier = supplier(account);
            SupplierPortalSubmission submission = new SupplierPortalSubmission();
            submission.setCode("SPS-PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            submission.setName(request.submissionType());
            submission.setStatus(SupplierPortalSubmissionStatus.DRAFT.name());
            submission.setSupplier(supplier);
            submission.setPortalAccount(account);
            submission.setSubmissionType(normalizeType(request.submissionType()));
            submission.setReferenceEntityType(normalizeNullableType(request.referenceEntityType()));
            submission.setReferenceId(request.referenceId());
            submission.setPayloadJson(request.payloadJson());
            submission.setAttachmentId(request.attachmentId());
            submission = submissionRepository.save(submission);
            auditService.log("SupplierPortalSubmission", submission.getId(), "PORTAL_CREATE_SUBMISSION", submission.getSubmissionType(), null, null);
            return submissionMapper.toResponse(submission);
        });
    }
    @Transactional
    public SupplierPortalSubmissionResponse submitSubmission(String token, UUID submissionId) {
        SupplierPortalAccount account = resolve(token);
        return withPortalContext(account, () -> {
            SupplierPortalSubmission submission = submissionRepository.findByIdAndIsDeletedFalse(submissionId)
                .filter(item -> !item.isDeleted())
                .filter(item -> account.getTenantId().equals(item.getTenantId()))
                .filter(item -> account.getCompanyId().equals(item.getCompanyId()))
                .filter(item -> account.getId().equals(item.getPortalAccountId()))
                .filter(item -> account.getSupplierId().equals(item.getSupplierId()))
                .orElseThrow(() -> new BusinessException("Supplier portal submission not found"));
            if (!SupplierPortalSubmissionStatus.DRAFT.name().equals(submission.getStatus())) {
                throw new BusinessException("Only DRAFT submission can be submitted");
            }
            submission.setStatus(SupplierPortalSubmissionStatus.SUBMITTED.name());
            submission.setSubmittedAt(LocalDateTime.now());
            account.setLastSubmissionAt(LocalDateTime.now());
            accountRepository.save(account);
            submission = submissionRepository.save(submission);
            auditService.log("SupplierPortalSubmission", submission.getId(), "PORTAL_SUBMIT_SUBMISSION", submission.getSubmissionType(), null, null);
            return submissionMapper.toResponse(submission);
        });
    }
    @Transactional
    public void logout(String token) {
        SupplierPortalAccount account = resolve(token);
        withPortalContext(account, () -> {
            account.setPortalTokenHash(null);
            account.setPortalTokenExpiresAt(null);
            accountRepository.save(account);
            auditService.log("SupplierPortalAccount", account.getId(), "PORTAL_LOGOUT", account.getLoginEmail(), null, null);
            return null;
        });
    }
    private SupplierPortalAccount resolve(String token) {
        String rawToken = requireText(token, "Supplier portal token is required");
        String tokenHash = sha256(rawToken);
        SupplierPortalAccount account = accountRepository.findByPortalTokenHashAndIsDeletedFalse(tokenHash)
            .orElseThrow(() -> {
                logSessionLookupMiss(tokenHash);
                return new BusinessException("Supplier portal session not found");
            });
        if (!SupplierPortalStatus.ACTIVE.name().equals(account.getStatus())) {
            throw new BusinessException("Supplier portal account is not active");
        }
        if (account.getPortalTokenExpiresAt() == null || account.getPortalTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Supplier portal session expired");
        }
        return account;
    }

    private void logSessionLookupMiss(String tokenHash) {
        List<SupplierPortalAccount> accounts = accountRepository.findByPortalTokenHash(tokenHash);
        log.debug(
            "Supplier portal RFQ session lookup miss: tokenHashPrefix={}, tenantContext={}, companyContext={}, countByHash={}, candidates={}",
            tokenHashPrefix(tokenHash),
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            accountRepository.countByPortalTokenHash(tokenHash),
            accounts.stream()
                .map(account -> Map.of(
                    "id", java.util.Objects.toString(account.getId(), ""),
                    "tenantId", java.util.Objects.toString(account.getTenantId(), ""),
                    "companyId", java.util.Objects.toString(account.getCompanyId(), ""),
                    "status", java.util.Objects.toString(account.getStatus(), ""),
                    "portalTokenExpiresAt", java.util.Objects.toString(account.getPortalTokenExpiresAt(), ""),
                    "deleted", account.isDeleted()
                ))
                .toList()
        );
    }

    private String tokenHashPrefix(String tokenHash) {
        return tokenHash == null ? null : tokenHash.substring(0, Math.min(8, tokenHash.length()));
    }

    private com.enterprise.mm.entity.Supplier supplier(SupplierPortalAccount account) {
        return supplierRepository.findByIdAndIsDeletedFalse(account.getSupplierId())
            .filter(item -> !item.isDeleted())
            .filter(item -> account.getTenantId().equals(item.getTenantId()))
            .filter(item -> account.getCompanyId().equals(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("Supplier not found"));
    }
    private RFQ rfq(SupplierPortalAccount account, UUID rfqId) {
        return rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(item -> !item.isDeleted())
            .filter(item -> account.getTenantId().equals(item.getTenantId()))
            .filter(item -> account.getCompanyId().equals(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("RFQ not found"));
    }
    private RFQItem rfqItem(SupplierPortalAccount account, RFQ rfq, UUID rfqItemId) {
        return rfqItemRepository.findByIdAndIsDeletedFalse(rfqItemId)
            .filter(item -> !item.isDeleted())
            .filter(item -> account.getTenantId().equals(item.getTenantId()))
            .filter(item -> account.getCompanyId().equals(item.getCompanyId()))
            .filter(item -> rfq.getId().equals(item.getRfqId()))
            .orElseThrow(() -> new BusinessException("RFQ item not found"));
    }
    private void requirePortalAccess(SupplierPortalAccount account, RFQ rfq) {
        requirePortalAccess(account, supplier(account), rfq);
    }

    private void requirePortalAccess(
        SupplierPortalAccount account,
        com.enterprise.mm.entity.Supplier supplier,
        RFQ rfq
    ) {
        tenderSupplierAccessService.validateViewTender(rfq, supplier);
    }

    private void requireProposalAccess(SupplierPortalAccount account, UUID rfqId) {
        requireTerms(account);
        RFQ rfq = rfq(account, rfqId);
        requirePortalAccess(account, supplier(account), rfq);
    }

    private PortalProposalRequest legacyProposal(SupplierPortalQuotationRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("Quotation items are required");
        }
        return new PortalProposalRequest(
            currency(request.currencyCode()),
            null,
            null,
            null,
            null,
            request.items().stream().map(item -> new ProposalLineRequest(
                item.rfqItemId(),
                item.quantity(),
                item.unitPrice(),
                item.deliveryDays(),
                null,
                false,
                null,
                request.comment()
            )).toList()
        );
    }

    private SupplierPortalQuotationResponse createLegacyQuotation(
        SupplierPortalAccount account,
        com.enterprise.mm.entity.Supplier supplier,
        RFQ rfq,
        SupplierPortalQuotationRequest request
    ) {
        quotationRepository.findByTenantIdAndCompanyIdAndRfq_IdAndSupplier_IdAndIsDeletedFalse(
            account.getTenantId(), account.getCompanyId(), rfq.getId(), account.getSupplierId()
        ).ifPresent(existing -> {
            throw new BusinessException("Supplier already submitted quotation for this RFQ");
        });
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("Quotation items are required");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SupplierPortalQuotationItemRequest item : request.items()) {
            RFQItem rfqItem = rfqItem(account, rfq, item.rfqItemId());
            if (item.materialId() != null && !item.materialId().equals(rfqItem.getMaterialId())) {
                throw new BusinessException("Quotation item material does not match RFQ item");
            }
            total = total.add(
                positive(item.quantity(), "quantity").multiply(positive(item.unitPrice(), "unitPrice"))
            );
        }
        SupplierQuotation quotation = new SupplierQuotation();
        quotation.setCode("SQ-PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        quotation.setName(BusinessNameGenerator.workflowDocumentName("SupplierQuotation", rfq.getName()));
        quotation.setStatus("SUBMITTED");
        quotation.setDescription(request.comment());
        quotation.setRfq(rfq);
        quotation.setSupplier(supplier);
        quotation.setCurrencyCode(currency(request.currencyCode()));
        quotation.setSubmittedAt(LocalDateTime.now());
        quotation.setReceivedAt(quotation.getSubmittedAt());
        quotation.setTotalAmount(total);
        quotation.setCurrentRevision(true);
        quotation = quotationRepository.save(quotation);
        for (SupplierPortalQuotationItemRequest item : request.items()) {
            RFQItem rfqItem = rfqItem(account, rfq, item.rfqItemId());
            SupplierQuotationItem quotationItem = new SupplierQuotationItem();
            quotationItem.setCode("SQI-PORTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            quotationItem.setName("Portal quotation item");
            quotationItem.setStatus("ACTIVE");
            quotationItem.setQuotation(quotation);
            quotationItem.setRfqItem(rfqItem);
            quotationItem.setLineType(rfqItem.getLineType());
            quotationItem.setMaterial(rfqItem.getMaterial());
            quotationItem.setMaterialTypeSnapshot(rfqItem.getMaterialTypeSnapshot());
            quotationItem.setServiceRequestId(rfqItem.getServiceRequestId());
            quotationItem.setServiceDescription(rfqItem.getServiceDescription());
            quotationItem.setServiceCategory(rfqItem.getServiceCategory());
            quotationItem.setUnit(rfqItem.getUnit());
            quotationItem.setPeriod(rfqItem.getPeriod());
            quotationItem.setStage(rfqItem.getStage());
            quotationItem.setBudgetItemId(rfqItem.getBudgetItemId());
            quotationItem.setQuantity(positive(item.quantity(), "quantity"));
            quotationItem.setUnitPrice(positive(item.unitPrice(), "unitPrice"));
            quotationItem.setDeliveryDays(item.deliveryDays() == null ? 0 : Math.max(0, item.deliveryDays()));
            quotationItemRepository.save(quotationItem);
        }
        account.setLastSubmissionAt(LocalDateTime.now());
        accountRepository.save(account);
        auditService.log("SupplierQuotation", quotation.getId(), "PORTAL_SUBMIT_QUOTATION", quotation.getCode(), null, null);
        return new SupplierPortalQuotationResponse(
            quotation.getId(),
            quotation.getCode(),
            quotation.getStatus(),
            quotation.getTotalAmount(),
            quotation.getCurrencyCode(),
            quotation.getSubmittedAt(),
            request.items().size()
        );
    }

    private List<String> currentQuotationStatuses() {
        return List.of("DRAFT", "ACTIVE", "SUBMITTED", "SELECTED", "REJECTED");
    }

    private void requireActive(com.enterprise.mm.entity.Supplier supplier) {
        if ("BLOCKED".equalsIgnoreCase(supplier.getStatus())) {
            throw new BusinessException("Supplier is blocked and cannot access RFQ quotations");
        }
        if (!isActive(supplier)) {
            throw new BusinessException("Supplier is not active and cannot access RFQ quotations");
        }
    }

    private boolean isActive(com.enterprise.mm.entity.Supplier supplier) {
        return List.of("ACTIVE", "LIMITED").contains(supplier.getStatus());
    }
    private void requireSupplierAdmin(SupplierPortalAccount account) {
        if (!SupplierPortalRole.SUPPLIER_ADMIN.name().equals(account.getPortalRole())) {
            throw new BusinessException("Only supplier admins can manage portal team members");
        }
    }
    private SupplierPortalTeamMember teamMember(SupplierPortalAccount account) {
        return new SupplierPortalTeamMember(
            account.getId(),
            account.getContactPerson(),
            account.getLoginEmail(),
            account.getPortalRole(),
            account.getPhone(),
            account.getStatus(),
            account.getInvitedAt(),
            account.getActivatedAt(),
            account.getLastLoginAt(),
            account.getTermsAcceptedAt()
        );
    }
    private RfqOutboundEmail teamInviteEmail(SupplierPortalAccount account, URI activationUrl) {
        String contact = account.getContactPerson() == null || account.getContactPerson().isBlank()
            ? "there"
            : account.getContactPerson().trim();
        String textBody = """
            Hello %s,

            You have been invited to the supplier portal.

            Open this secure link to activate your account:
            %s

            This link expires in 24 hours.
            """.formatted(contact, activationUrl);
        String htmlBody = """
            <p>Hello %s,</p>
            <p>You have been invited to the supplier portal.</p>
            <p><a href="%s">Activate your supplier portal account</a></p>
            <p>This link expires in 24 hours.</p>
            """.formatted(escapeHtml(contact), escapeHtml(activationUrl.toString()));
        return new RfqOutboundEmail(
            mailProperties.getFrom(),
            account.getLoginEmail(),
            mailProperties.getReplyTo(),
            "Supplier portal invitation",
            htmlBody,
            textBody,
            List.of()
        );
    }
    private String normalizePortalRole(String value) {
        String normalized = value == null || value.isBlank()
            ? SupplierPortalRole.SUPPLIER_USER.name()
            : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            SupplierPortalRole role = SupplierPortalRole.valueOf(normalized);
            return role.name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("Unsupported supplier portal role: " + value);
        }
    }
    private String normalizeEmail(String value) {
        return requireText(value, "loginEmail is required").trim().toLowerCase(Locale.ROOT);
    }
    private void requireTerms(SupplierPortalAccount account) {
        if (account.getTermsAcceptedAt() == null) {
            throw new BusinessException("Supplier portal terms must be accepted before submissions");
        }
    }
    private <T> T withPortalContext(SupplierPortalAccount account, Supplier<T> action) {
        String previousTenant = TenantContext.currentTenant();
        UUID previousCompany = CompanyContext.currentCompany();
        var previousAuth = SecurityContextHolder.getContext().getAuthentication();
        TenantContext.setTenant(account.getTenantId());
        CompanyContext.setCompany(account.getCompanyId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("supplier:" + account.getLoginEmail(), null, List.of()));
        try {
            return action.get();
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
            SecurityContextHolder.getContext().setAuthentication(previousAuth);
        }
    }
    private String normalizeType(String value) {
        return requireText(value, "submissionType is required").trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
    private String normalizeNullableType(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
    private String currency(String value) {
        return value == null || value.isBlank() ? "UZS" : value.trim().toUpperCase(Locale.ROOT);
    }
    private BigDecimal positive(BigDecimal value, String field) {
        BigDecimal amount = amount(value);
        if (amount.signum() <= 0) {
            throw new BusinessException(field + " must be positive");
        }
        return amount;
    }
    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value;
    }
    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash supplier portal token", ex);
        }
    }
}
