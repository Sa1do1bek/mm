package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqPublishedVersion;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RfqAmendmentService {
    private static final Set<String> AMENDABLE_STATUSES = Set.of("OPEN");

    private final RFQRepository rfqRepository;
    private final RfqPublishedVersionRepository versionRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;



    @Transactional
    public AmendmentDraft start(UUID rfqId, StartAmendmentRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("Expected RFQ version is required", "rfq.proposal.revision_conflict");
        }
        String reason = meaningfulReason(request.reason());
        RFQ rfq = lock(rfqId);
        requireVersion(rfq, request.expectedVersion());
        if (!AMENDABLE_STATUSES.contains(rfq.getStatus())) {
            throw new BusinessException("Only an open RFQ can be amended", "rfq.amendment.not_open");
        }
        RfqPublishedVersion activeVersion = activeVersion(rfq);
        restoreEditableFields(rfq, snapshot(activeVersion));
        rfq.setPendingAmendmentReason(reason);
        rfq.setStatus("AMENDMENT_DRAFT");
        rfqRepository.saveAndFlush(rfq);
        auditService.log("RFQ", rfqId, "AMENDMENT_STARTED", reason, null, null);
        return view(rfq, activeVersion.getVersionNumber() + 1, reason);
    }

    @Transactional
    public AmendmentDraft cancel(UUID rfqId, CancelAmendmentRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw new BusinessException("Expected RFQ version is required", "rfq.proposal.revision_conflict");
        }
        RFQ rfq = lock(rfqId);
        requireVersion(rfq, request.expectedVersion());
        if (!"AMENDMENT_DRAFT".equals(rfq.getStatus())) {
            throw new BusinessException("RFQ does not have an amendment draft", "rfq.amendment.not_draft");
        }
        RfqPublishedVersion activeVersion = activeVersion(rfq);
        String reason = rfq.getPendingAmendmentReason();
        restoreEditableFields(rfq, snapshot(activeVersion));
        rfq.setPendingAmendmentReason(null);
        rfq.setStatus("OPEN");
        rfqRepository.saveAndFlush(rfq);
        auditService.log("RFQ", rfqId, "AMENDMENT_CANCELLED", reason, null, null);
        return view(rfq, activeVersion.getVersionNumber(), null);
    }

    private RFQ lock(UUID rfqId) {
        return rfqRepository.lockScopedById(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfqId
        ).orElseThrow(() -> new NotFoundException("RFQ not found"));
    }

    private RfqPublishedVersion activeVersion(RFQ rfq) {
        if (rfq.getActivePublishedVersionId() == null) {
            throw new BusinessException("RFQ has no active published version", "rfq.amendment.no_published_version");
        }
        return versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), rfq.getActivePublishedVersionId()
        ).orElseThrow(() -> new BusinessException(
            "Active RFQ published version not found", "rfq.amendment.no_published_version"
        ));
    }

    private void requireVersion(RFQ rfq, long expectedVersion) {
        long actualVersion = rfq.getVersion() == null ? 0L : rfq.getVersion();
        if (actualVersion != expectedVersion) {
            throw new BusinessException(
                "RFQ changed; refresh and try again", "rfq.proposal.revision_conflict"
            );
        }
    }

    private String meaningfulReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.length() < 5) {
            throw new BusinessException("Amendment reason is required", "rfq.amendment.reason_required");
        }
        return reason;
    }

    private RfqPackageSnapshot snapshot(RfqPublishedVersion version) {
        try {
            JsonNode tree = objectMapper.readTree(version.getSnapshotJson());
            String json = tree.isTextual() ? tree.textValue() : tree.toString();
            return objectMapper.readValue(json, RfqPackageSnapshot.class);
        } catch (Exception exception) {
            throw new BusinessException("Cannot restore the active RFQ version", "rfq.amendment.snapshot_invalid");
        }
    }

    private void restoreEditableFields(RFQ rfq, RfqPackageSnapshot snapshot) {
        rfq.setName(snapshot.title());
        rfq.setDescription(snapshot.description());
        rfq.setDueDate(snapshot.dueDate());
        rfq.setPackageLanguage(snapshot.language());
        rfq.setResponseInstructions(snapshot.responseInstructions());
    }

    private AmendmentDraft view(RFQ rfq, int nextPublishedVersionNumber, String reason) {
        return new AmendmentDraft(
            rfq.getId(),
            rfq.getVersion() == null ? 0L : rfq.getVersion(),
            nextPublishedVersionNumber,
            reason,
            rfq.getStatus()
        );
    }

    public record StartAmendmentRequest(String reason, Long expectedVersion) {
    }

    public record CancelAmendmentRequest(Long expectedVersion) {
    }

    public record AmendmentDraft(
        UUID rfqId,
        long version,
        int nextPublishedVersionNumber,
        String reason,
        String status
    ) {
    }
}
