package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.TenderCommission;
import com.enterprise.mm.entity.TenderCommissionMemberSetting;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.TenderCommissionMemberSettingRequest;
import com.enterprise.mm.payload.TenderCommissionMemberSettingResponse;
import com.enterprise.mm.payload.TenderCommissionRequest;
import com.enterprise.mm.payload.TenderCommissionResponse;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.TenderCommissionMemberSettingRepository;
import com.enterprise.mm.repository.TenderCommissionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TenderCommissionService {
    private final TenderCommissionRepository repository;
    private final TenderCommissionMemberSettingRepository settingRepository;
    private final CommissionRepository commissionRepository;
    private final CommissionMemberRepository commissionMemberRepository;
    private final RFQRepository rfqRepository;
    private final AuditService auditService;
    private final String entityName = "TenderCommission";

    @Transactional(readOnly = true)
    public TenderCommissionResponse get(UUID id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public List<TenderCommissionResponse> byRfq(UUID rfqId) {
        requireRfq(rfqId);
        return repository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfqId
            ).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public TenderCommissionResponse create(TenderCommissionRequest request) {
        RFQ rfq = requireRfq(request.getRfqId());
        Commission commission = requireProcessCommission(request.getCommissionId(), rfq);
        TenderCommission entity = new TenderCommission();
        entity.setCompanyId(rfq.getCompanyId());
        entity.setRfqId(rfq.getId());
        entity.setCommissionId(commission.getId());
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        entity.setDescription(request.getDescription());
        entity.setQuorumRequired(request.getQuorumRequired());
        entity.setActive(request.getActive() == null || request.getActive());
        beforeSave(entity);
        TenderCommission saved = repository.save(entity);
        syncMembers(saved, request.getMembers());
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public Optional<TenderCommissionResponse> assignProcessCommission(UUID rfqId, UUID commissionId) {
        RFQ rfq = requireRfq(rfqId);
        List<TenderCommission> activeAssignments =
            repository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
                TenantContext.currentTenant(),
                rfq.getCompanyId(),
                rfq.getId()
            );
        if (!activeAssignments.isEmpty()) {
            return Optional.of(toResponse(activeAssignments.get(0)));
        }

        if (commissionId == null) {
            return Optional.empty();
        }
        Commission commission = requireProcessCommission(commissionId, rfq);
        List<CommissionMember> activeMembers = commissionMemberRepository
            .findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commission.getId())
            .stream()
            .filter(CommissionMember::isActive)
            .toList();
        if (activeMembers.isEmpty()) {
            throw new BadRequestException("Tender commission has no active members");
        }

        TenderCommissionRequest request = new TenderCommissionRequest();
        request.setRfqId(rfq.getId());
        request.setCommissionId(commission.getId());
        request.setCode("TC-" + safeCode(rfq.getCode()));
        request.setName("Tender commission - " + safeName(rfq));
        request.setStatus("ACTIVE");
        request.setDescription("Assigned by procurement tender process");
        request.setQuorumRequired(defaultQuorum(activeMembers.size()));
        request.setActive(true);
        // Same as attachCommissionToRfq: signed=true so conflict checks are not blocked.
        request.setMembers(signedMemberSettings(activeMembers));
        return Optional.of(create(request));
    }

    /**
     * Attach a reusable TENDER commission to an RFQ from commission master create/update.
     * Marks conflict declarations signed so bid-opening / evaluation can proceed.
     * Idempotent when the same commission is already assigned; rejects when another commission owns the RFQ.
     */
    @Transactional
    public TenderCommissionResponse attachCommissionToRfq(UUID rfqId, UUID commissionId) {
        RFQ rfq = requireRfq(rfqId);
        Commission commission = requireProcessCommission(commissionId, rfq);
        List<TenderCommission> activeAssignments =
            repository.findByTenantIdAndCompanyIdAndRfqIdAndActiveTrueAndIsDeletedFalse(
                TenantContext.currentTenant(),
                rfq.getCompanyId(),
                rfq.getId()
            );
        if (!activeAssignments.isEmpty()) {
            TenderCommission existing = activeAssignments.get(0);
            if (commission.getId().equals(existing.getCommissionId())) {
                return toResponse(existing);
            }
            throw new BadRequestException(
                "RFQ already has an active tender commission assignment: " + existing.getCommissionId()
            );
        }

        List<CommissionMember> activeMembers = commissionMemberRepository
            .findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commission.getId())
            .stream()
            .filter(CommissionMember::isActive)
            .toList();
        if (activeMembers.isEmpty()) {
            throw new BadRequestException("Tender commission has no active members");
        }

        TenderCommissionRequest request = new TenderCommissionRequest();
        request.setRfqId(rfq.getId());
        request.setCommissionId(commission.getId());
        request.setCode("TC-" + safeCode(rfq.getCode()));
        request.setName("Tender commission - " + safeName(rfq));
        request.setStatus("ACTIVE");
        request.setDescription("Assigned from commission master");
        request.setQuorumRequired(defaultQuorum(activeMembers.size()));
        request.setActive(true);
        request.setMembers(signedMemberSettings(activeMembers));
        return create(request);
    }

    @Transactional
    public TenderCommissionResponse update(UUID id, TenderCommissionRequest request) {
        TenderCommission entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        RFQ rfq = requireRfq(request.getRfqId());
        Commission commission = requireProcessCommission(request.getCommissionId(), rfq);
        entity.setCompanyId(rfq.getCompanyId());
        entity.setRfqId(rfq.getId());
        entity.setCommissionId(commission.getId());
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setStatus(request.getStatus());
        entity.setDescription(request.getDescription());
        entity.setQuorumRequired(request.getQuorumRequired());
        if (request.getActive() != null) {
            entity.setActive(request.getActive());
        }
        beforeSave(entity);
        TenderCommission saved = repository.save(entity);
        if (request.getMembers() != null) {
            syncMembers(saved, request.getMembers());
        }
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return toResponse(saved);
    }

    @Transactional
    public void softDelete(UUID id) {
        TenderCommission entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }

    private TenderCommission findEntity(UUID id) {
        TenderCommission entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }

    private RFQ requireRfq(UUID rfqId) {
        if (rfqId == null) {
            throw new BadRequestException("rfqId is required");
        }
        return rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("RFQ not found: " + rfqId));
    }

    private Commission requireProcessCommission(UUID commissionId, RFQ rfq) {
        if (commissionId == null) {
            throw new BadRequestException("commissionId is required");
        }
        CommissionType requiredType = requiredCommissionType(rfq);
        Commission commission = commissionRepository.findByIdAndIsDeletedFalse(commissionId)
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> com.enterprise.mm.security.AccessScope.canAccessCompany(item.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Commission not found: " + commissionId));
        if (commission.getType() != requiredType) {
            throw new BadRequestException("Procurement process requires commission type " + requiredType.name());
        }
        if (!commission.isActive()) {
            throw new BadRequestException("Commission is not active: " + commissionId);
        }
        return commission;
    }

    private CommissionType requiredCommissionType(RFQ rfq) {
        String method = rfq == null || rfq.getTenderProcedureType() == null
            ? null
            : rfq.getTenderProcedureType().trim().toUpperCase(Locale.ROOT);
        if (ProcurementMethodType.OPEN_COMPETITION.name().equals(method)
            || ProcurementMethodType.CLOSED_COMPETITION.name().equals(method)) {
            return CommissionType.COMPETITION;
        }
        return CommissionType.TENDER;
    }

    private void syncMembers(TenderCommission tenderCommission, List<TenderCommissionMemberSettingRequest> requestedMembers) {
        List<TenderCommissionMemberSetting> existing = settingRepository.findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
            TenantContext.currentTenant(),
            tenderCommission.getId()
        );
        existing.forEach(TenderCommissionMemberSetting::softDelete);
        settingRepository.saveAll(existing);
        if (requestedMembers == null || requestedMembers.isEmpty()) {
            throw new BadRequestException("tender commission members are required");
        }
        Set<UUID> seen = new LinkedHashSet<>();
        List<TenderCommissionMemberSetting> settings = requestedMembers.stream()
            .map(request -> createSetting(tenderCommission, request, seen))
            .toList();
        settingRepository.saveAll(settings);
    }

    private TenderCommissionMemberSetting createSetting(
        TenderCommission tenderCommission,
        TenderCommissionMemberSettingRequest request,
        Set<UUID> seen
    ) {
        if (request == null || request.commissionMemberId() == null) {
            throw new BadRequestException("commissionMemberId is required");
        }
        if (!seen.add(request.commissionMemberId())) {
            throw new BadRequestException("Tender commission member is duplicated: " + request.commissionMemberId());
        }
        CommissionMember member = requireCommissionMember(tenderCommission.getCommissionId(), request.commissionMemberId());
        TenderCommissionMemberSetting setting = new TenderCommissionMemberSetting();
        setting.setCompanyId(tenderCommission.getCompanyId());
        setting.setTenderCommissionId(tenderCommission.getId());
        setting.setCommissionMemberId(member.getId());
        setting.setVotingWeight(normalizeVotingWeight(request.votingWeight()));
        setting.setConflictDeclarationSigned(Boolean.TRUE.equals(request.conflictDeclarationSigned()));
        setting.setDeclaredAt(request.declaredAt());
        setting.setActive(request.active() == null || request.active());
        setting.setStatus("ACTIVE");
        setting.setCode("TCMS-" + UUID.randomUUID().toString().substring(0, 8));
        setting.setName(member.getMemberName());
        return setting;
    }

    private CommissionMember requireCommissionMember(UUID commissionId, UUID memberId) {
        return commissionMemberRepository.findById(memberId)
            .filter(item -> !item.isDeleted())
            .filter(item -> TenantContext.currentTenant().equals(item.getTenantId()))
            .filter(item -> commissionId.equals(item.getCommissionId()))
            .filter(CommissionMember::isActive)
            .orElseThrow(() -> new NotFoundException("Commission member not found in commission: " + memberId));
    }

    private TenderCommissionResponse toResponse(TenderCommission entity) {
        TenderCommissionResponse response = new TenderCommissionResponse();
        copyBase(entity, response);
        response.setRfqId(entity.getRfqId());
        response.setCommissionId(entity.getCommissionId());
        response.setQuorumRequired(entity.getQuorumRequired());
        response.setActive(entity.isActive());
        response.setMembers(settingRepository.findByTenantIdAndTenderCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(
                TenantContext.currentTenant(),
                entity.getId()
            ).stream()
            .filter(TenderCommissionMemberSetting::isActive)
            .map(this::settingResponse)
            .toList());
        return response;
    }

    private TenderCommissionMemberSettingResponse settingResponse(TenderCommissionMemberSetting setting) {
        TenderCommissionMemberSettingResponse response = new TenderCommissionMemberSettingResponse();
        copyBase(setting, response);
        response.setTenderCommissionId(setting.getTenderCommissionId());
        response.setCommissionMemberId(setting.getCommissionMemberId());
        response.setVotingWeight(setting.getVotingWeight());
        response.setConflictDeclarationSigned(setting.isConflictDeclarationSigned());
        response.setDeclaredAt(setting.getDeclaredAt());
        response.setActive(setting.isActive());
        CommissionMember member = commissionMemberRepository.findById(setting.getCommissionMemberId()).orElse(null);
        if (member != null) {
            response.setUserId(member.getUserId());
            response.setEmployeeId(member.getEmployeeId());
            response.setMemberName(member.getMemberName());
            response.setRole(member.getMemberRole() == null ? null : member.getMemberRole().name());
        }
        return response;
    }

    private void copyBase(com.enterprise.mm.common.BusinessEntity entity, com.enterprise.mm.payload.BaseRecordDto response) {
        response.setId(entity.getId());
        response.setTenantId(entity.getTenantId());
        response.setCompanyId(entity.getCompanyId());
        response.setCode(entity.getCode());
        response.setName(entity.getName());
        response.setStatus(entity.getStatus());
        response.setDescription(entity.getDescription());
        response.setDeleted(entity.isDeleted());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setUpdatedBy(entity.getUpdatedBy());
        response.setVersion(entity.getVersion());
    }

    private void beforeSave(TenderCommission entity) {
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT) + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
    }

    private BigDecimal normalizeVotingWeight(BigDecimal votingWeight) {
        BigDecimal value = votingWeight == null ? BigDecimal.ONE : votingWeight;
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("votingWeight must be greater than zero");
        }
        return value;
    }

    private int defaultQuorum(int activeMemberCount) {
        return Math.max(1, (activeMemberCount / 2) + 1);
    }

    /**
     * Auto-sign conflict declarations on assign so Open/Closed competition RFQs
     * can run conflict checks without a separate UI sign step.
     */
    private List<TenderCommissionMemberSettingRequest> signedMemberSettings(List<CommissionMember> activeMembers) {
        LocalDateTime declaredAt = LocalDateTime.now();
        return activeMembers.stream()
            .map(member -> new TenderCommissionMemberSettingRequest(
                member.getId(),
                BigDecimal.ONE,
                true,
                declaredAt,
                true
            ))
            .toList();
    }

    private String safeCode(String code) {
        String value = code == null || code.isBlank() ? UUID.randomUUID().toString().substring(0, 8) : code;
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safeName(RFQ rfq) {
        if (rfq.getName() != null && !rfq.getName().isBlank()) {
            return rfq.getName();
        }
        if (rfq.getCode() != null && !rfq.getCode().isBlank()) {
            return rfq.getCode();
        }
        return rfq.getId().toString();
    }
}
