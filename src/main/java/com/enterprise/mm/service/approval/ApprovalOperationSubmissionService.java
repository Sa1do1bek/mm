package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ApprovalOperationRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalOperationResponse;
import com.enterprise.mm.repository.ApprovalOperationRequestRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalOperationSubmissionService {
    private final ApprovalOperationRequestRepository repository;
    private final ApprovalOperationRegistry operations;
    private final ApprovalEngineService engine;
    private final ApprovalOperationLifecycleService lifecycle;
    private final ApprovalOperationExecutionService execution;

    @Transactional
    public ApprovalOperationResponse submit(
        String moduleKey, UUID targetId, Long expectedVersion, String payloadJson, String idempotencyKey
    ) {
        if (targetId == null || expectedVersion == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("Target, expected version and Idempotency-Key are required");
        }
        String actor = currentActor();
        String normalizedKey = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalOperationHandler handler = operations.handler(normalizedKey);
        UUID companyId = handler.companyId(targetId);
        if (!CompanyContext.currentCompany().equals(companyId)) {
            throw new BusinessException("Operation target is outside the current company");
        }
        String payload = payloadJson == null ? "{}" : payloadJson;
        String hash = sha256(normalizedKey + ":" + targetId + ":" + expectedVersion + ":" + payload);
        var existing = repository.findByTenantIdAndCompanyIdAndRequesterUsernameAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, actor, idempotencyKey
        );
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().getPayloadHash())) {
                throw new BusinessException("Idempotency-Key was already used for a different operation");
            }
            return response(existing.get());
        }
        handler.validate(targetId, expectedVersion, payload);
        if (repository.existsByTenantIdAndCompanyIdAndModuleKeyAndTargetEntityIdAndStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(), companyId, normalizedKey, targetId,
            List.of("DRAFT", "PENDING", "RETURNED", "APPROVED_NOT_APPLIED", "APPLY_FAILED")
        )) {
            throw new BusinessException("An approval request is already pending for this operation and target");
        }
        ApprovalOperationRequest request = new ApprovalOperationRequest();
        request.setTenantId(TenantContext.currentTenant());
        request.setCompanyId(companyId);
        request.setModuleKey(normalizedKey);
        request.setTargetEntityId(targetId);
        request.setTargetVersion(expectedVersion);
        request.setRequesterUsername(actor);
        request.setPayloadJson(payload);
        request.setPayloadHash(hash);
        request.setIdempotencyKey(idempotencyKey);
        request.setStatus("DRAFT");
        request.setName(normalizedKey + " — " + handler.displayName(targetId));
        ApprovalOperationRequest saved = repository.saveAndFlush(request);
        var approval = engine.start(normalizedKey, saved.getId());
        saved.setApprovalInstanceId(approval.id());
        return response(repository.save(saved));
    }

    private ApprovalOperationResponse response(ApprovalOperationRequest request) {
        return new ApprovalOperationResponse(request.getId(), request.getModuleKey(), request.getTargetEntityId(),
            request.getStatus(), request.getApprovalInstanceId(), request.getRequesterUsername(), request.getFailureReason());
    }

    @Transactional(readOnly = true)
    public Optional<ApprovalOperationResponse> latest(String moduleKey, UUID targetId) {
        String normalizedKey = ApprovalModuleRegistry.normalize(moduleKey);
        ApprovalOperationHandler handler = operations.handler(normalizedKey);
        UUID companyId = handler.companyId(targetId);
        if (!CompanyContext.currentCompany().equals(companyId)) {
            throw new BusinessException("Operation target is outside the current company");
        }
        return repository.findFirstByTenantIdAndCompanyIdAndModuleKeyAndTargetEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), companyId, normalizedKey, targetId
        ).map(this::response);
    }

    public ApprovalOperationResponse retryApply(UUID requestId) {
        ApprovalOperationRequest request = lifecycle.require(requestId);
        if (!"APPROVED_NOT_APPLIED".equals(request.getStatus()) && !"APPLY_FAILED".equals(request.getStatus())) {
            throw new BusinessException("Only an approved, unapplied operation can be retried");
        }
        operations.handler(request.getModuleKey()).validate(
            request.getTargetEntityId(), request.getTargetVersion(), request.getPayloadJson()
        );
        try {
            execution.apply(requestId);
        } catch (RuntimeException failure) {
            execution.recordFailure(requestId, failure instanceof BusinessException
                ? failure.getMessage() : "Operation could not be applied; contact support");
        }
        return response(lifecycle.require(requestId));
    }

    @Transactional
    public ApprovalOperationResponse resubmit(UUID requestId) {
        ApprovalOperationRequest request = lifecycle.require(requestId);
        if (!"RETURNED".equals(request.getStatus())) {
            throw new BusinessException("Only a returned operation can be resubmitted");
        }
        if (!request.getRequesterUsername().equalsIgnoreCase(currentActor())) {
            throw new BusinessException("Only the requester can resubmit this operation");
        }
        operations.handler(request.getModuleKey()).validate(
            request.getTargetEntityId(), request.getTargetVersion(), request.getPayloadJson()
        );
        var approval = engine.start(request.getModuleKey(), requestId);
        request.setApprovalInstanceId(approval.id());
        return response(repository.save(request));
    }

    @Transactional
    public ApprovalOperationResponse cancel(UUID requestId) {
        ApprovalOperationRequest request = lifecycle.require(requestId);
        if (!request.getRequesterUsername().equalsIgnoreCase(currentActor())) {
            throw new BusinessException("Only the requester can cancel this operation");
        }
        engine.cancelOperation(request.getModuleKey(), requestId);
        return response(repository.save(request));
    }

    public ApprovalOperationResponse abandonFailed(UUID requestId) {
        lifecycle.require(requestId);
        execution.abandonFailed(requestId, currentActor());
        return response(lifecycle.require(requestId));
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            throw new BusinessException("Authentication is required to submit an operation");
        }
        return auth.getName();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
