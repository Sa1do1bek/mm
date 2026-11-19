package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.BusinessActionDtos.TenderBidOpeningRequest;
import com.enterprise.mm.payload.BusinessActionDtos.TenderEvaluationProtocolRequest;
import com.enterprise.mm.payload.TenderBidOpeningProtocolResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolResponse;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.TenderBidOpeningProtocolMapper;
import com.enterprise.mm.mapper.TenderEvaluationProtocolMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.SupplierQuotation;
import com.enterprise.mm.entity.SupplierRelatedParty;
import com.enterprise.mm.entity.TenderBidOpeningLine;
import com.enterprise.mm.entity.TenderBidOpeningProtocol;
import com.enterprise.mm.entity.TenderCommission;
import com.enterprise.mm.entity.TenderCommissionMemberSetting;
import com.enterprise.mm.entity.TenderCommissionMember;
import com.enterprise.mm.entity.TenderConflictCheck;
import com.enterprise.mm.entity.TenderEvaluationProtocol;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.SupplierRelatedPartyRepository;
import com.enterprise.mm.repository.TenderBidOpeningLineRepository;
import com.enterprise.mm.repository.TenderBidOpeningProtocolRepository;
import com.enterprise.mm.repository.TenderCommissionMemberSettingRepository;
import com.enterprise.mm.repository.TenderCommissionMemberRepository;
import com.enterprise.mm.repository.TenderCommissionRepository;
import com.enterprise.mm.repository.TenderConflictCheckRepository;
import com.enterprise.mm.repository.TenderEvaluationProtocolRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TenderProcessService {
    private final RFQRepository rfqRepository;
    private final RfqSupplierInvitationRepository invitationRepository;
    private final SupplierQuotationRepository quotationRepository;
    private final SupplierRelatedPartyRepository relatedPartyRepository;
    private final TenderCommissionRepository tenderCommissionRepository;
    private final TenderCommissionMemberSettingRepository tenderCommissionMemberSettingRepository;
    private final TenderCommissionMemberRepository commissionMemberRepository;
    private final TenderBidOpeningProtocolRepository openingProtocolRepository;
    private final TenderBidOpeningLineRepository openingLineRepository;
    private final TenderConflictCheckRepository conflictCheckRepository;
    private final TenderEvaluationProtocolRepository evaluationProtocolRepository;
    private final TenderBidOpeningProtocolMapper openingProtocolMapper;
    private final TenderEvaluationProtocolMapper evaluationProtocolMapper;
    private final IntegrationEventService integrationEventService;
    private final AuditService auditService;

    @Transactional
    public TenderBidOpeningProtocolResponse openBids(UUID rfqId, TenderBidOpeningRequest request) {
        RFQ rfq = loadRfq(rfqId);
        List<SupplierQuotation> quotations = quotations(rfqId);
        if (!responseDeadlinePassed(rfq) && !expectedQuotationsReceived(rfq, quotations)) {
            throw new BusinessException("Supplier proposals can be opened only after the response deadline or once all expected quotations are received");
        }
        int activeSignedMemberCount = activeSignedMemberCount(rfqId);
        int quorumRequired = quorumRequired(rfq);
        int quorumPresent = request == null || request.quorumPresent() == null ? activeSignedMemberCount : request.quorumPresent();
        if (quorumPresent < quorumRequired) {
            throw new BusinessException("Tender commission quorum is not met: required "
                + quorumRequired + ", present " + quorumPresent);
        }
        if (quotations.isEmpty()) {
            throw new BusinessException("RFQ has no supplier quotations to open");
        }
        TenderBidOpeningProtocol protocol = new TenderBidOpeningProtocol();
        protocol.setCode("TBOP-" + shortId());
        protocol.setName(BusinessNameGenerator.workflowDocumentName("TenderBidOpeningProtocol", rfq.getName()));
        protocol.setStatus("POSTED");
        protocol.setRfqId(rfq.getId());
        protocol.setProtocolNumber(protocol.getCode());
        protocol.setOpenedAt(LocalDateTime.now());
        protocol.setOpenedBy(request == null ? null : request.openedBy());
        protocol.setQuorumRequired(quorumRequired);
        protocol.setQuorumPresent(quorumPresent);
        protocol.setQuorumConfirmed(true);
        protocol.setQuotationCount(quotations.size());
        protocol.setMinutes(request == null ? null : request.notes());
        protocol.setPostedAt(LocalDateTime.now());
        protocol = openingProtocolRepository.save(protocol);
        int accepted = 0;
        int rejected = 0;
        for (SupplierQuotation quotation : quotations) {
            boolean acceptedForEvaluation = isSubmitted(quotation) && !isLate(rfq, quotation);
            TenderBidOpeningLine line = new TenderBidOpeningLine();
            line.setCode("TBOL-" + shortId());
            line.setName(BusinessNameGenerator.workflowDocumentName("TenderBidOpeningLine", quotation.getName()));
            line.setStatus(acceptedForEvaluation ? "ACCEPTED" : "REJECTED");
            line.setProtocolId(protocol.getId());
            line.setRfqId(rfq.getId());
            line.setQuotationId(quotation.getId());
            line.setSupplierId(quotation.getSupplierId());
            line.setSubmittedAt(quotation.getSubmittedAt());
            line.setTotalAmount(amount(quotation.getTotalAmount()));
            line.setCurrencyCode(currency(quotation.getCurrencyCode()));
            line.setAcceptedForEvaluation(acceptedForEvaluation);
            line.setRejectionReason(acceptedForEvaluation ? null : rejectionReason(rfq, quotation));
            openingLineRepository.save(line);
            if (acceptedForEvaluation) {
                accepted++;
            } else {
                rejected++;
            }
        }
        protocol.setAcceptedQuotationCount(accepted);
        protocol.setRejectedQuotationCount(rejected);
        openingProtocolRepository.save(protocol);
        rfq.setStatus("BIDS_OPENED");
        rfqRepository.save(rfq);
        integrationEventService.emit("RFQ_BIDS_OPENED", "RFQ", rfqId, Map.of("rfqId", rfqId, "accepted", accepted, "rejected", rejected));
        auditService.log("RFQ", rfqId, "OPEN_BIDS", protocol.getProtocolNumber(), null, null);
        return openingProtocolMapper.toResponse(protocol);
    }
    @Transactional
    public List<UUID> runConflictChecks(UUID rfqId) {
        RFQ rfq = loadRfq(rfqId);
        requireOpeningProtocol(rfq);
        List<SupplierQuotation> quotations = quotations(rfqId);
        requireActiveSignedCommission(rfq);
        List<UUID> checkIds = new ArrayList<>();
        for (SupplierQuotation quotation : quotations) {
            List<SupplierRelatedParty> parties = relatedPartyRepository.findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                quotation.getSupplierId()
            );
            if (parties.isEmpty()) {
                checkIds.add(saveClearCheck(rfqId, quotation));
                continue;
            }
            for (SupplierRelatedParty party : parties) {
                if (party.isConflictOfInterestFlag() || party.isPepFlag() || party.isSanctionsMatch()) {
                    TenderConflictCheck check = conflictCheck(rfqId, quotation, party);
                    checkIds.add(conflictCheckRepository.save(check).getId());
                }
            }
            boolean hasConflict = parties.stream().anyMatch(party -> party.isConflictOfInterestFlag() || party.isPepFlag() || party.isSanctionsMatch());
            if (!hasConflict) {
                checkIds.add(saveClearCheck(rfqId, quotation));
            }
        }
        rfq.setStatus("CONFLICT_CHECKED");
        rfqRepository.save(rfq);
        integrationEventService.emit("RFQ_CONFLICT_CHECKED", "RFQ", rfqId, Map.of("rfqId", rfqId, "checkCount", checkIds.size()));
        auditService.log("RFQ", rfqId, "RUN_CONFLICT_CHECKS", "checks=" + checkIds.size(), null, null);
        return checkIds;
    }
    @Transactional
    public TenderEvaluationProtocolResponse createEvaluationProtocol(UUID rfqId, TenderEvaluationProtocolRequest request) {
        RFQ rfq = loadRfq(rfqId);
        TenderBidOpeningProtocol opening = requireOpeningProtocol(rfq);
        requireConflictChecksRun(rfq.getId());
        requireNoUnresolvedConflicts(rfq.getId());
        UUID recommendedQuotationId = request == null ? null : request.recommendedQuotationId();
        if (recommendedQuotationId == null) {
            throw new BusinessException("Recommended quotation is required");
        }
        SupplierQuotation quotation = quotationRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), recommendedQuotationId)
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .filter(item -> rfqId.equals(item.getRfqId()))
            .orElseThrow(() -> new BusinessException("Recommended quotation not found in RFQ"));
        requireAcceptedInOpeningProtocol(opening, quotation.getId(), "Recommended quotation");
        validateRecommendation(rfq, quotation, opening);
        TenderEvaluationProtocol protocol = new TenderEvaluationProtocol();
        protocol.setCode("TEP-" + shortId());
        protocol.setName(BusinessNameGenerator.workflowDocumentName("TenderEvaluationProtocol", rfq.getName()));
        protocol.setStatus("APPROVED");
        protocol.setRfqId(rfq.getId());
        protocol.setProtocolNumber(protocol.getCode());
        protocol.setEvaluatedAt(LocalDateTime.now());
        protocol.setSelectionRule(selectionRule(rfq));
        protocol.setRecommendedQuotationId(quotation.getId());
        protocol.setRecommendedSupplierId(quotation.getSupplierId());
        protocol.setRecommendationReason(request == null ? null : request.reason());
        protocol.setScoringSummary(request == null ? null : request.scoringSummary());
        protocol.setConflictSummary("Unresolved conflicts: 0");
        protocol.setApprovedBy(request == null ? null : request.approvedBy());
        protocol.setApprovedAt(LocalDateTime.now());
        protocol.setPostedAt(LocalDateTime.now());
        protocol = evaluationProtocolRepository.save(protocol);
        rfq.setStatus("EVALUATED");
        rfqRepository.save(rfq);
        integrationEventService.emit("RFQ_EVALUATION_PROTOCOL_APPROVED", "RFQ", rfqId, Map.of(
            "rfqId", rfqId,
            "recommendedQuotationId", quotation.getId(),
            "recommendedSupplierId", quotation.getSupplierId()
        ));
        auditService.log("RFQ", rfqId, "CREATE_EVALUATION_PROTOCOL", protocol.getProtocolNumber(), null, null);
        return evaluationProtocolMapper.toResponse(protocol);
    }
    @Transactional(readOnly = true, noRollbackFor = BusinessException.class)
    public void validateReadyForSelection(RFQ rfq, SupplierQuotation selected) {
        if (singleSupplier(rfq)) {
            return;
        }
        TenderBidOpeningProtocol opening = requireOpeningProtocol(rfq);
        if (opening.getAcceptedQuotationCount() == null || opening.getAcceptedQuotationCount() < minQuotationsRequired(rfq)) {
            throw new BusinessException("Tender opening protocol does not contain enough accepted quotations");
        }
        requireAcceptedInOpeningProtocol(opening, selected.getId(), "Selected quotation");
        requireNoUnresolvedConflicts(rfq.getId());
        TenderEvaluationProtocol evaluation = latestEvaluationProtocol(rfq.getId());
        if (!selected.getId().equals(evaluation.getRecommendedQuotationId())) {
            throw new BusinessException("Selected quotation must match approved tender evaluation protocol");
        }
    }
    private TenderBidOpeningProtocol requireOpeningProtocol(RFQ rfq) {
        return openingProtocolRepository.findByTenantIdAndCompanyIdAndRfq_IdAndIsDeletedFalseOrderByOpenedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getId()
            ).stream()
            .filter(protocol -> "POSTED".equals(protocol.getStatus()))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Posted tender bid opening protocol is required"));
    }
    private TenderEvaluationProtocol latestEvaluationProtocol(UUID rfqId) {
        return evaluationProtocolRepository.findByTenantIdAndCompanyIdAndRfq_IdAndIsDeletedFalseOrderByEvaluatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfqId
            ).stream()
            .filter(protocol -> "APPROVED".equals(protocol.getStatus()) || "POSTED".equals(protocol.getStatus()))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Approved tender evaluation protocol is required"));
    }
    private void requireNoUnresolvedConflicts(UUID rfqId) {
        if (conflictCheckRepository.existsByTenantIdAndCompanyIdAndRfq_IdAndConflictFoundTrueAndResolvedFalseAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        )) {
            throw new BusinessException("RFQ has unresolved conflict-of-interest checks");
        }
    }
    private void requireConflictChecksRun(UUID rfqId) {
        if (!conflictCheckRepository.existsByTenantIdAndCompanyIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        )) {
            throw new BusinessException("Conflict-of-interest checks must be run before tender evaluation");
        }
    }
    private void validateRecommendation(RFQ rfq, SupplierQuotation selected, TenderBidOpeningProtocol opening) {
        List<UUID> acceptedQuotationIds = acceptedQuotationIds(opening);
        List<SupplierQuotation> quotations = quotations(rfq.getId()).stream()
            .filter(item -> acceptedQuotationIds.contains(item.getId()))
            .toList();
        if ("LOWEST_PRICE".equals(selectionRule(rfq))) {
            BigDecimal lowest = quotations.stream().map(item -> amount(item.getTotalAmount())).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalAmount()).compareTo(lowest) != 0) {
                throw new BusinessException("Evaluation protocol recommendation must follow LOWEST_PRICE");
            }
        }
        if ("BEST_SCORE".equals(selectionRule(rfq))) {
            BigDecimal best = quotations.stream().map(item -> amount(item.getTotalScore())).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            if (amount(selected.getTotalScore()).compareTo(best) != 0) {
                throw new BusinessException("Evaluation protocol recommendation must follow BEST_SCORE");
            }
        }
    }
    private void requireAcceptedInOpeningProtocol(TenderBidOpeningProtocol opening, UUID quotationId, String subject) {
        if (!acceptedQuotationIds(opening).contains(quotationId)) {
            throw new BusinessException(subject + " must be accepted in posted tender opening protocol");
        }
    }
    private List<UUID> acceptedQuotationIds(TenderBidOpeningProtocol opening) {
        return openingLineRepository.findByTenantIdAndCompanyIdAndProtocol_IdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                opening.getId()
            ).stream()
            .filter(TenderBidOpeningLine::isAcceptedForEvaluation)
            .map(TenderBidOpeningLine::getQuotationId)
            .toList();
    }
    private RFQ loadRfq(UUID rfqId) {
        return rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(item -> !item.isDeleted() && TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new BusinessException("RFQ not found"));
    }
    private List<SupplierQuotation> quotations(UUID rfqId) {
        List<String> statuses = List.of("ACTIVE", "SUBMITTED", "SELECTED", "REJECTED");
        List<SupplierQuotation> current = quotationRepository.findCurrentSubmittedByRfq(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId,
            statuses
        );
        if (current.isEmpty()) {
            current = quotationRepository
                .findByTenantIdAndRfq_IdAndIsDeletedFalseOrderByTotalAmountAsc(
                    TenantContext.currentTenant(), rfqId
                ).stream()
                .filter(value -> value.getThreadId() == null)
                .filter(value -> value.getStatus() == null || statuses.contains(value.getStatus()))
                .toList();
        }
        return current.stream()
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .toList();
    }

    private boolean responseDeadlinePassed(RFQ rfq) {
        return rfq.getDueDate() != null && rfq.getDueDate().isBefore(LocalDate.now());
    }

    private boolean expectedQuotationsReceived(RFQ rfq, List<SupplierQuotation> quotations) {
        int received = (int) quotations.stream()
            .filter(this::isSubmitted)
            .map(SupplierQuotation::getSupplierId)
            .filter(value -> value != null)
            .distinct()
            .count();
        return received >= expectedQuotationCount(rfq);
    }

    private int expectedQuotationCount(RFQ rfq) {
        int invited = (int) invitationRepository.findByTenantIdAndCompanyIdAndRfqIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getId()
            ).stream()
            .filter(invitation -> "INVITED".equals(invitation.getStatus()))
            .map(invitation -> invitation.getSupplierId())
            .filter(value -> value != null)
            .distinct()
            .count();
        return Math.max(1, invited > 0 ? invited : minQuotationsRequired(rfq));
    }

    private int activeSignedMemberCount(UUID rfqId) {
        List<TenderCommission> tenderCommissions = universalTenderCommissions(rfqId);
        if (!tenderCommissions.isEmpty()) {
            return (int) tenderCommissions.stream()
                .flatMap(commission -> tenderCommissionMemberSettingRepository
                    .findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commission.getId())
                    .stream())
                .filter(TenderCommissionMemberSetting::isActive)
                .filter(TenderCommissionMemberSetting::isConflictDeclarationSigned)
                .count();
        }
        return (int) commissionMembers(rfqId).stream()
            .filter(TenderCommissionMember::isActive)
            .filter(TenderCommissionMember::isConflictDeclarationSigned)
            .count();
    }

    private void requireActiveSignedCommission(RFQ rfq) {
        if (singleSupplier(rfq)) {
            return;
        }
        List<TenderCommission> tenderCommissions = universalTenderCommissions(rfq.getId());
        if (!tenderCommissions.isEmpty()) {
            List<TenderCommissionMemberSetting> settings = tenderCommissions.stream()
                .flatMap(commission -> tenderCommissionMemberSettingRepository
                    .findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commission.getId())
                    .stream())
                .filter(TenderCommissionMemberSetting::isActive)
                .toList();
            if (settings.isEmpty()) {
                throw new BusinessException("Tender commission has no active members");
            }
            if (settings.stream().noneMatch(TenderCommissionMemberSetting::isConflictDeclarationSigned)) {
                throw new BusinessException("Tender commission conflict declarations must be signed before conflict checks");
            }
            return;
        }
        List<TenderCommissionMember> members = commissionMembers(rfq.getId());
        if (members.isEmpty()) {
            throw new BusinessException("Tender commission is required before conflict checks");
        }
        List<TenderCommissionMember> activeMembers = members.stream()
            .filter(TenderCommissionMember::isActive)
            .toList();
        if (activeMembers.isEmpty()) {
            throw new BusinessException("Tender commission has no active members");
        }
        if (activeMembers.stream().noneMatch(TenderCommissionMember::isConflictDeclarationSigned)) {
            throw new BusinessException("Tender commission conflict declarations must be signed before conflict checks");
        }
    }
    private List<TenderCommissionMember> commissionMembers(UUID rfqId) {
        return commissionMemberRepository.findByTenantIdAndCompanyIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        );
    }

    private List<TenderCommission> universalTenderCommissions(UUID rfqId) {
        return tenderCommissionRepository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            rfqId
        );
    }
    private TenderConflictCheck conflictCheck(UUID rfqId, SupplierQuotation quotation, SupplierRelatedParty party) {
        TenderConflictCheck check = new TenderConflictCheck();
        check.setCode("TCI-" + shortId());
        check.setName(BusinessNameGenerator.workflowDocumentName("TenderConflictCheck", quotation.getName()));
        check.setStatus("UNRESOLVED");
        check.setRfqId(rfqId);
        check.setQuotationId(quotation.getId());
        check.setSupplierId(quotation.getSupplierId());
        check.setRelatedPartyId(party.getId());
        check.setConflictFound(true);
        check.setConflictType(party.isSanctionsMatch() ? "SANCTIONS" : party.isPepFlag() ? "PEP" : "RELATED_PARTY");
        check.setSeverity(party.isSanctionsMatch() ? "CRITICAL" : "HIGH");
        check.setDetails("Related party requires commission review: " + (party.getFullName() == null ? party.getLegalName() : party.getFullName()));
        check.setResolved(false);
        check.setCheckedAt(LocalDateTime.now());
        return check;
    }
    private UUID saveClearCheck(UUID rfqId, SupplierQuotation quotation) {
        TenderConflictCheck check = new TenderConflictCheck();
        check.setCode("TCI-" + shortId());
        check.setName(BusinessNameGenerator.workflowDocumentName("TenderConflictCheck", quotation.getName()));
        check.setStatus("CLEAR");
        check.setRfqId(rfqId);
        check.setQuotationId(quotation.getId());
        check.setSupplierId(quotation.getSupplierId());
        check.setConflictFound(false);
        check.setConflictType("NONE");
        check.setSeverity("LOW");
        check.setDetails("No conflict-of-interest indicators found");
        check.setResolved(true);
        check.setCheckedAt(LocalDateTime.now());
        return conflictCheckRepository.save(check).getId();
    }
    private boolean isSubmitted(SupplierQuotation quotation) {
        return "SUBMITTED".equals(quotation.getStatus()) || "ACTIVE".equals(quotation.getStatus()) || "SELECTED".equals(quotation.getStatus());
    }
    private boolean isLate(RFQ rfq, SupplierQuotation quotation) {
        return rfq.getDueDate() != null
            && quotation.getSubmittedAt() != null
            && quotation.getSubmittedAt().isAfter(rfq.getDueDate().atTime(LocalTime.MAX));
    }
    private String rejectionReason(RFQ rfq, SupplierQuotation quotation) {
        if (!isSubmitted(quotation)) {
            return "Quotation is not submitted";
        }
        return isLate(rfq, quotation) ? "Quotation submitted after due date" : "Not accepted";
    }
    // Competition-grade procedures need a real field of bidders. Since V202 migrated
    // rfqs.tender_procedure_type onto the ProcurementMethodType vocabulary, the tender codes
    // carry the same meaning as the legacy competition codes and must share the quorum.
    private static final Set<String> COMPETITION_PROCEDURES = Set.of(
        "OPEN_COMPETITION", "CLOSED_COMPETITION", "OPEN_TENDER", "CLOSED_TENDER"
    );

    private int quorumRequired(RFQ rfq) {
        return universalTenderCommissions(rfq.getId()).stream()
            .map(TenderCommission::getQuorumRequired)
            .filter(value -> value != null)
            .mapToInt(value -> Math.max(0, value))
            .max()
            .orElse(defaultQuorumRequired(rfq));
    }

    private int defaultQuorumRequired(RFQ rfq) {
        return COMPETITION_PROCEDURES.contains(procedure(rfq)) ? 3 : 1;
    }
    private int minQuotationsRequired(RFQ rfq) {
        return rfq.getMinQuotationsRequired() == null ? (singleSupplier(rfq) ? 1 : 2) : rfq.getMinQuotationsRequired();
    }
    // V202 renamed the single-party procedure SINGLE_SUPPLIER -> SINGLE_SOURCE and added
    // DIRECT_PURCHASE; both are the "no competition" class (ProcurementMethodType
    // .requiresExceptionReason). SINGLE_SUPPLIER stays accepted for un-migrated rows, and
    // remains a valid *selection rule* code in its own right.
    private static final Set<String> SINGLE_PARTY_PROCEDURES = Set.of(
        "SINGLE_SUPPLIER", "SINGLE_SOURCE", "DIRECT_PURCHASE"
    );

    private boolean singleSupplier(RFQ rfq) {
        return SINGLE_PARTY_PROCEDURES.contains(procedure(rfq)) || "SINGLE_SUPPLIER".equals(selectionRule(rfq));
    }
    private String procedure(RFQ rfq) {
        return rfq.getTenderProcedureType() == null ? "REQUEST_FOR_QUOTATION" : rfq.getTenderProcedureType();
    }
    private String selectionRule(RFQ rfq) {
        return rfq.getSelectionRule() == null ? "LOWEST_PRICE" : rfq.getSelectionRule();
    }
    private String currency(String value) {
        return value == null || value.isBlank() ? "UZS" : value;
    }
    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
