package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierAccreditationApplication;
import com.enterprise.mm.entity.SupplierAccreditationDecision;
import com.enterprise.mm.entity.SupplierUnblockRequest;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.SupplierAccreditationStatus;
import com.enterprise.mm.enums.SupplierQualificationStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.SupplierQualificationResponse;
import com.enterprise.mm.payload.SupplierUnblockRequestDto;
import com.enterprise.mm.payload.SupplierUnblockResponse;
import com.enterprise.mm.payload.SupplierUnblockDecisionRequest;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.SupplierAccreditationApplicationRepository;
import com.enterprise.mm.repository.SupplierAccreditationDecisionRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.SupplierUnblockRequestRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
@RequiredArgsConstructor
public class SupplierQualificationService {
    private final SupplierRepository supplierRepository;
    private final SupplierAccreditationApplicationRepository applicationRepository;
    private final SupplierAccreditationDecisionRepository decisionRepository;
    private final SupplierUnblockRequestRepository unblockRequestRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final com.enterprise.mm.supplier.governance.service.SupplierGovernanceActionPolicy supplierGovernanceActionPolicy;
    private final SupplierAdmissionService admissionService;

    private static final List<String> OPEN_UNBLOCK_STATUSES = List.of("PENDING", "UNDER_REVIEW");

    @Transactional(readOnly = true)
    public SupplierQualificationResponse get(UUID supplierId) {
        Supplier supplier = requireSupplier(supplierId);
        List<SupplierAccreditationApplication> applications = applicationRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId
            );
        LocalDate today = LocalDate.now();
        SupplierAccreditationApplication effective = findCurrentlyEligibleApplication(applications, today);
        SupplierAccreditationApplication statusSource = effective != null
            ? effective
            : (applications.isEmpty() ? null : applications.get(0));
        SupplierQualificationStatus status = qualificationStatus(supplier, applications, effective, today);
        List<SupplierQualificationResponse.Restriction> restrictions = restrictions(statusSource);
        LocalDate expiresAt = effective == null
            ? (statusSource == null ? null : resolveValidTo(statusSource))
            : resolveValidTo(effective);
        String blockedReason = status == SupplierQualificationStatus.BLOCKED
            ? firstNonBlank(supplier.getBlockedReason(), statusSource == null ? null : statusSource.getReviewComment())
            : null;
        return new SupplierQualificationResponse(
            supplierId,
            status,
            blockedReason,
            status == SupplierQualificationStatus.BLOCKED ? supplier.getUpdatedBy() : null,
            status == SupplierQualificationStatus.BLOCKED ? supplier.getUpdatedAt() : null,
            expiresAt,
            restrictions
        );
    }

    @Transactional
    public SupplierUnblockResponse requestUnblock(UUID supplierId, SupplierUnblockRequestDto request) {
        return requestUnblock(supplierId, request, null);
    }

    @Transactional
    public SupplierUnblockResponse requestUnblock(
        UUID supplierId,
        SupplierUnblockRequestDto request,
        String idempotencyKey
    ) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SUPPLIER_UNBLOCK_REQUEST", supplierId, normalizedKey, request, SupplierUnblockResponse.class,
            () -> requestUnblockInternal(supplierId, request));
    }

    private SupplierUnblockResponse requestUnblockInternal(UUID supplierId, SupplierUnblockRequestDto request) {
        supplierGovernanceActionPolicy.requireSupplierActionAllowed("REQUEST_UNBLOCK");
        Supplier supplier = requireMutableSupplier(supplierId);
        if (!isBlocked(supplier)) {
            throw new BusinessException("Supplier is not blocked; an unblock request cannot be created");
        }
        if (unblockRequestRepository.existsByTenantIdAndCompanyIdAndSupplierIdAndStatusInAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId, OPEN_UNBLOCK_STATUSES)) {
            throw new ConflictException("Supplier already has an open unblock request");
        }
        if (request.purchaseOrderId() != null) {
            purchaseOrderRepository.findByIdAndIsDeletedFalse(request.purchaseOrderId())
                .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
                .filter(entity -> CompanyContext.currentCompany().equals(entity.getCompanyId()))
                .filter(entity -> supplierId.equals(entity.getSupplierId()))
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + request.purchaseOrderId()));
        }
        User actor = requireCurrentActor();
        SupplierUnblockRequest entity = new SupplierUnblockRequest();
        entity.setCode("SUP-UNBLOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        entity.setName(BusinessNameGenerator.workflowDocumentName(
            "SupplierUnblockRequest",
            unblockParentName(request.purchaseOrderId(), supplier)
        ));
        entity.setStatus("PENDING");
        entity.setSupplierId(supplierId);
        entity.setReason(request.reason().trim());
        entity.setPurchaseOrderId(request.purchaseOrderId());
        entity.setRequestedBy(actor.getId());
        SupplierUnblockRequest saved = unblockRequestRepository.save(entity);
        unblockRequestRepository.flush();
        auditService.logInCurrentTransaction(
            "SupplierUnblockRequest", saved.getId(), "CREATE", saved.getReason(), null, null);
        return response(saved);
    }

    private String unblockParentName(UUID purchaseOrderId, Supplier supplier) {
        if (purchaseOrderId == null) {
            return supplier.getName();
        }
        return purchaseOrderRepository.findByIdAndIsDeletedFalse(purchaseOrderId)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> CompanyContext.currentCompany().equals(entity.getCompanyId()))
            .map(PurchaseOrder::getName)
            .orElse(supplier.getName());
    }

    @Transactional
    public SupplierUnblockResponse review(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request
    ) {
        return review(supplierId, requestId, request, null);
    }

    @Transactional
    public SupplierUnblockResponse review(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request,
        String idempotencyKey
    ) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SUPPLIER_UNBLOCK_REVIEW", requestId, normalizedKey, request, SupplierUnblockResponse.class,
            () -> reviewInternal(supplierId, requestId, request));
    }

    @Transactional
    public SupplierUnblockResponse approve(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request
    ) {
        return approve(supplierId, requestId, request, null);
    }

    @Transactional
    public SupplierUnblockResponse approve(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request,
        String idempotencyKey
    ) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SUPPLIER_UNBLOCK_APPROVE", requestId, normalizedKey, request, SupplierUnblockResponse.class,
            () -> approveInternal(supplierId, requestId, request));
    }

    @Transactional
    public SupplierUnblockResponse reject(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request
    ) {
        return reject(supplierId, requestId, request, null);
    }

    @Transactional
    public SupplierUnblockResponse reject(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest request,
        String idempotencyKey
    ) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        return idempotencyService.execute(
            "SUPPLIER_UNBLOCK_REJECT", requestId, normalizedKey, request, SupplierUnblockResponse.class,
            () -> rejectInternal(supplierId, requestId, request));
    }

    private SupplierUnblockResponse reviewInternal(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest command
    ) {
        requireMutableSupplier(supplierId);
        SupplierUnblockRequest entity = requireUnblockRequest(supplierId, requestId);
        requireStatus(entity, "PENDING");
        requireVersion(entity, command.expectedVersion());
        User actor = requireCurrentActor();
        entity.setStatus("UNDER_REVIEW");
        entity.setReviewedBy(actor.getId());
        entity.setReviewedAt(LocalDateTime.now());
        entity.setReviewComment(command.comment().trim());
        SupplierUnblockRequest saved = unblockRequestRepository.save(entity);
        unblockRequestRepository.flush();
        auditService.logInCurrentTransaction(
            "SupplierUnblockRequest", saved.getId(), "REVIEW", saved.getReviewComment(), null, null);
        return response(saved);
    }

    private SupplierUnblockResponse approveInternal(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest command
    ) {
        Supplier supplier = requireMutableSupplier(supplierId);
        SupplierUnblockRequest entity = requireUnblockRequest(supplierId, requestId);
        requireStatus(entity, "UNDER_REVIEW");
        requireVersion(entity, command.expectedVersion());
        User actor = requireCurrentActor();
        requireIndependentDecisionMaker(entity, actor);
        entity.setStatus("APPROVED");
        entity.setDecidedBy(actor.getId());
        entity.setDecidedAt(LocalDateTime.now());
        entity.setDecisionComment(command.comment().trim());
        SupplierUnblockRequest saved = unblockRequestRepository.save(entity);
        unblockRequestRepository.flush();

        // Explicit approval releases the sticky/manual block. The central coordinator
        // immediately reapplies any still-active compliance or performance restriction.
        supplier.setStatus("NEW");
        supplier.setBlockedReason(null);
        supplierRepository.save(supplier);
        admissionService.recalculate(supplierId);

        auditService.logInCurrentTransaction(
            "SupplierUnblockRequest", saved.getId(), "APPROVE", saved.getDecisionComment(), null, null);
        return response(saved);
    }

    private SupplierUnblockResponse rejectInternal(
        UUID supplierId,
        UUID requestId,
        SupplierUnblockDecisionRequest command
    ) {
        requireMutableSupplier(supplierId);
        SupplierUnblockRequest entity = requireUnblockRequest(supplierId, requestId);
        requireStatus(entity, "UNDER_REVIEW");
        requireVersion(entity, command.expectedVersion());
        User actor = requireCurrentActor();
        requireIndependentDecisionMaker(entity, actor);
        entity.setStatus("REJECTED");
        entity.setDecidedBy(actor.getId());
        entity.setDecidedAt(LocalDateTime.now());
        entity.setDecisionComment(command.comment().trim());
        SupplierUnblockRequest saved = unblockRequestRepository.save(entity);
        unblockRequestRepository.flush();
        auditService.logInCurrentTransaction(
            "SupplierUnblockRequest", saved.getId(), "REJECT", saved.getDecisionComment(), null, null);
        return response(saved);
    }

    private SupplierQualificationStatus qualificationStatus(
        Supplier supplier,
        List<SupplierAccreditationApplication> applications,
        SupplierAccreditationApplication effective,
        LocalDate today
    ) {
        if ("BLOCKED".equalsIgnoreCase(supplier.getStatus()) || hasText(supplier.getBlockedReason())) {
            return SupplierQualificationStatus.BLOCKED;
        }
        if (effective != null) {
            if (SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name().equals(effective.getStatus())) {
                return SupplierQualificationStatus.LIMITED;
            }
            return SupplierQualificationStatus.ACTIVE;
        }
        if (hasExpiredEligibleApplication(applications, today)) {
            return SupplierQualificationStatus.EXPIRED;
        }
        if (applications == null || applications.isEmpty()) {
            return SupplierQualificationStatus.EXPIRED;
        }
        SupplierAccreditationApplication latest = applications.get(0);
        if (List.of(
            SupplierAccreditationStatus.SUSPENDED.name(),
            SupplierAccreditationStatus.REJECTED.name()
        ).contains(latest.getStatus())) {
            return SupplierQualificationStatus.BLOCKED;
        }
        // In-flight accreditation must not look like a global "expired" eligibility on the list.
        return SupplierQualificationStatus.LIMITED;
    }

    private SupplierAccreditationApplication findCurrentlyEligibleApplication(
        List<SupplierAccreditationApplication> applications,
        LocalDate today
    ) {
        if (applications == null || applications.isEmpty()) {
            return null;
        }
        for (SupplierAccreditationApplication application : applications) {
            if (!isEligibleAccreditationStatus(application.getStatus())) {
                continue;
            }
            if (isCurrentlyValid(application, today)) {
                return application;
            }
        }
        return null;
    }

    private boolean hasExpiredEligibleApplication(
        List<SupplierAccreditationApplication> applications,
        LocalDate today
    ) {
        if (applications == null || applications.isEmpty()) {
            return false;
        }
        for (SupplierAccreditationApplication application : applications) {
            if (!isEligibleAccreditationStatus(application.getStatus())) {
                continue;
            }
            LocalDate validTo = resolveValidTo(application);
            if (validTo != null && validTo.isBefore(today)) {
                return true;
            }
            LocalDate decisionTo = resolveDecisionValidTo(application);
            if (decisionTo != null && decisionTo.isBefore(today)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEligibleAccreditationStatus(String status) {
        return SupplierAccreditationStatus.APPROVED.name().equals(status)
            || SupplierAccreditationStatus.CONDITIONALLY_APPROVED.name().equals(status);
    }

    private boolean isCurrentlyValid(SupplierAccreditationApplication application, LocalDate today) {
        LocalDate validFrom = resolveValidFrom(application);
        LocalDate validTo = resolveValidTo(application);
        if (validFrom == null || validTo == null) {
            return false;
        }
        return !today.isBefore(validFrom) && !today.isAfter(validTo);
    }

    private LocalDate resolveValidFrom(SupplierAccreditationApplication application) {
        LocalDate decisionFrom = resolveDecisionValidFrom(application);
        return decisionFrom != null ? decisionFrom : application.getValidFrom();
    }

    private LocalDate resolveValidTo(SupplierAccreditationApplication application) {
        LocalDate decisionTo = resolveDecisionValidTo(application);
        return decisionTo != null ? decisionTo : application.getValidTo();
    }

    private LocalDate resolveDecisionValidFrom(SupplierAccreditationApplication application) {
        return latestRecordedDecision(application)
            .map(SupplierAccreditationDecision::getValidFrom)
            .orElse(null);
    }

    private LocalDate resolveDecisionValidTo(SupplierAccreditationApplication application) {
        return latestRecordedDecision(application)
            .map(SupplierAccreditationDecision::getValidTo)
            .orElse(null);
    }

    private java.util.Optional<SupplierAccreditationDecision> latestRecordedDecision(
        SupplierAccreditationApplication application
    ) {
        return decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
            ).stream()
            .filter(decision -> "RECORDED".equals(decision.getStatus()))
            .filter(decision -> isEligibleAccreditationStatus(decision.getDecision()))
            .findFirst();
    }

    private List<SupplierQualificationResponse.Restriction> restrictions(SupplierAccreditationApplication application) {
        if (application == null) {
            return List.of();
        }
        List<SupplierQualificationResponse.Restriction> result = new ArrayList<>();
        List<SupplierAccreditationDecision> decisions = decisionRepository
            .findByTenantIdAndCompanyIdAndApplicationIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), application.getId()
            );
        if (!decisions.isEmpty()) {
            SupplierAccreditationDecision decision = decisions.get(0);
            if (decision.getAllowedMaterialGroupIds() != null) {
                decision.getAllowedMaterialGroupIds().forEach(groupId -> result.add(
                    new SupplierQualificationResponse.Restriction(
                        "MATERIAL_CATEGORY", groupId.toString(), decision.getConditions()
                    )
                ));
            }
        }
        if (hasText(application.getRequestedScope())) {
            result.add(new SupplierQualificationResponse.Restriction(
                "ORGANIZATION", application.getRequestedScope(), application.getReviewComment()
            ));
        }
        return List.copyOf(result);
    }

    private Supplier requireSupplier(UUID id) {
        return supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + id));
    }

    private Supplier requireMutableSupplier(UUID id) {
        return supplierRepository.findByIdAndIsDeletedFalse(id)
            .filter(entity -> TenantContext.currentTenant().equals(entity.getTenantId()))
            .filter(entity -> CompanyContext.currentCompany().equals(entity.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Supplier not found: " + id));
    }

    private SupplierUnblockRequest requireUnblockRequest(UUID supplierId, UUID requestId) {
        return unblockRequestRepository
            .findByTenantIdAndCompanyIdAndSupplierIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), supplierId, requestId)
            .orElseThrow(() -> new NotFoundException("Supplier unblock request not found: " + requestId));
    }

    private User requireCurrentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()
            || "anonymousUser".equals(authentication.getName())) {
            throw new BusinessException("Authenticated user is required for supplier unblock commands");
        }
        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        String username = authentication.getName();
        return userRepository
            .findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(tenantId, companyId, username)
            .or(() -> userRepository.findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(tenantId, username))
            .orElseThrow(() -> new NotFoundException("Authenticated user not found: " + username));
    }

    private void requireStatus(SupplierUnblockRequest entity, String expected) {
        if (!expected.equals(entity.getStatus())) {
            throw new BusinessException(
                "Supplier unblock request must be " + expected + ", current status is " + entity.getStatus());
        }
    }

    private void requireVersion(SupplierUnblockRequest entity, Long expectedVersion) {
        if (!Objects.equals(entity.getVersion(), expectedVersion)) {
            throw new ConflictException(
                "Supplier unblock request was changed by another user; refresh and retry");
        }
    }

    private void requireIndependentDecisionMaker(SupplierUnblockRequest entity, User actor) {
        if (Objects.equals(entity.getRequestedBy(), actor.getId())) {
            throw new BusinessException(
                "Supplier unblock requester cannot approve or reject their own request");
        }
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key is required for supplier unblock commands");
        }
        return idempotencyKey.trim();
    }

    private boolean isBlocked(Supplier supplier) {
        return "BLOCKED".equalsIgnoreCase(supplier.getStatus()) || hasText(supplier.getBlockedReason());
    }

    private SupplierUnblockResponse response(SupplierUnblockRequest entity) {
        return new SupplierUnblockResponse(
            entity.getId(),
            entity.getSupplierId(),
            entity.getPurchaseOrderId(),
            entity.getReason(),
            entity.getRequestedBy(),
            entity.getStatus(),
            entity.getReviewedBy(),
            entity.getReviewedAt(),
            entity.getReviewComment(),
            entity.getDecidedBy(),
            entity.getDecidedAt(),
            entity.getDecisionComment(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }
}
