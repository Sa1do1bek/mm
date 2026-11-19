package com.enterprise.mm.service;

import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.payload.SupplierAccreditationApplicationResponse;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.DecisionCommand;
import com.enterprise.mm.payload.SupplierAccreditationCommandDtos.VersionedCommand;
import com.enterprise.mm.payload.SupplierAccreditationDecisionActionRequest;
import com.enterprise.mm.service.approval.ApprovalEngineService;
import com.enterprise.mm.service.approval.SupplierAccreditationApprovalAdapter;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessService;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessDtos.SupplierReadinessSummary;
import com.enterprise.mm.supplier.governance.readiness.SupplierReadinessEnums.StageKey;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupplierAccreditationCommandService {
    private static final String STALE_VERSION_MESSAGE =
        "Supplier accreditation application was changed by another user; refresh and retry";

    private final SupplierAccreditationService accreditationService;
    private final IdempotencyService idempotencyService;
    private final ApprovalEngineService approvalEngineService;
    private final SupplierReadinessService readinessService;
    private final com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;

    @Transactional
    public SupplierAccreditationApplicationResponse submit(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_SUBMIT",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> accreditationService.submit(applicationId)
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse startReview(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_START_REVIEW",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                SupplierAccreditationApplication app =
                    accreditationService.validateApplicationForMutation(applicationId);
                SupplierReadinessSummary readiness = readinessService.getForApplication(app.getSupplierId(), applicationId);
                supplierGovernanceActionPolicy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness);
                supplierGovernanceActionPolicy.requireActionAllowed(app, "START_REVIEW");
                return accreditationService.startReview(applicationId);
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse runComplianceCheck(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_RUN_COMPLIANCE_CHECK",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                SupplierAccreditationApplication app =
                    accreditationService.validateApplicationForMutation(applicationId);
                SupplierReadinessSummary readiness = readinessService.getForApplication(app.getSupplierId(), applicationId);
                supplierGovernanceActionPolicy.requireStagePrerequisitesSatisfied(StageKey.COMPLIANCE_KYC, readiness);
                supplierGovernanceActionPolicy.requireActionAllowed(app, "RUN_COMPLIANCE_CHECK");
                return accreditationService.runComplianceCheck(applicationId);
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse requestClarification(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_REQUEST_CLARIFICATION",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                SupplierAccreditationApplication app =
                    accreditationService.validateApplicationForMutation(applicationId);
                SupplierReadinessSummary readiness = readinessService.getForApplication(app.getSupplierId(), applicationId);
                supplierGovernanceActionPolicy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness);
                supplierGovernanceActionPolicy.requireActionAllowed(app, "REQUEST_CLARIFICATION");
                return accreditationService.requestClarification(applicationId, command.reviewComment());
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse approve(
        UUID applicationId,
        DecisionCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_APPROVE",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                SupplierAccreditationApplication app =
                    accreditationService.validateApplicationForMutation(applicationId);
                SupplierReadinessSummary readiness = readinessService.getForApplication(app.getSupplierId(), applicationId);
                // A decision completes review; requiring a final decision here would deadlock it.
                supplierGovernanceActionPolicy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness);
                supplierGovernanceActionPolicy.requireStageCompleted(StageKey.COMPLIANCE_KYC, StageKey.DECISION, readiness);
                return accreditationService.approve(applicationId, decisionRequest(command));
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse reject(
        UUID applicationId,
        DecisionCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_REJECT",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                // Rejection must remain possible for incomplete evidence. The domain service
                // enforces company, role, status and the prohibition on bypassing an approval instance.
                return accreditationService.reject(applicationId, decisionRequest(command));
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse submitForApproval(
        UUID applicationId,
        DecisionCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_SUBMIT_FOR_APPROVAL",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> {
                SupplierAccreditationApplication application =
                    accreditationService.validateApplicationForMutation(applicationId);
                SupplierReadinessSummary readiness = readinessService.getForApplication(application.getSupplierId(), applicationId);
                // Submission finishes review, so require its inputs (including compliance),
                // not the final decision that the approval engine will produce afterwards.
                supplierGovernanceActionPolicy.requireStagePrerequisitesSatisfied(StageKey.REVIEW, readiness);
                supplierGovernanceActionPolicy.requireStageCompleted(StageKey.COMPLIANCE_KYC, StageKey.DECISION, readiness);
                supplierGovernanceActionPolicy.requireActionAllowed(application, "SUBMIT_FOR_APPROVAL");
                accreditationService.proposeDecision(applicationId, decisionRequest(command));
                // If start() throws (e.g. no active ApprovalFlow configured for this moduleKey),
                // the whole @Transactional method rolls back, including the proposeDecision insert
                // above -- no PROPOSED decision is left behind. Do not catch-and-swallow here.
                approvalEngineService.start(SupplierAccreditationApprovalAdapter.MODULE_KEY, applicationId);
                return accreditationService.get(applicationId);
            }
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse suspend(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_SUSPEND",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> accreditationService.suspend(applicationId, command.reviewComment())
        );
    }

    @Transactional
    public SupplierAccreditationApplicationResponse renew(
        UUID applicationId,
        VersionedCommand command,
        String idempotencyKey
    ) {
        return execute(
            "SUPPLIER_ACCREDITATION_RENEW",
            applicationId,
            command,
            expectedVersion(command),
            idempotencyKey,
            () -> accreditationService.renew(applicationId)
        );
    }

    private SupplierAccreditationApplicationResponse execute(
        String operation,
        UUID applicationId,
        Object command,
        Long expectedVersion,
        String idempotencyKey,
        Supplier<SupplierAccreditationApplicationResponse> action
    ) {
        validate(expectedVersion, idempotencyKey);
        return idempotencyService.execute(
            operation,
            applicationId,
            idempotencyKey,
            command,
            SupplierAccreditationApplicationResponse.class,
            () -> {
                requireVersion(applicationId, expectedVersion);
                return action.get();
            }
        );
    }

    private Long expectedVersion(VersionedCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private Long expectedVersion(DecisionCommand command) {
        return command == null ? null : command.expectedVersion();
    }

    private void validate(Long expectedVersion, String idempotencyKey) {
        if (expectedVersion == null) {
            throw new BadRequestException("expectedVersion is required for supplier accreditation commands");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier accreditation commands");
        }
    }

    private void requireVersion(UUID applicationId, Long expectedVersion) {
        Long actualVersion = accreditationService.get(applicationId).getVersion();
        if (!Objects.equals(actualVersion, expectedVersion)) {
            throw new ConflictException(STALE_VERSION_MESSAGE);
        }
    }

    private SupplierAccreditationDecisionActionRequest decisionRequest(DecisionCommand command) {
        return new SupplierAccreditationDecisionActionRequest(
            command.decision(),
            command.allowedMaterialGroupIds(),
            command.validFrom(),
            command.validTo(),
            command.reviewComment()
        );
    }
}
