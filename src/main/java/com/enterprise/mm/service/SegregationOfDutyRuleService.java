package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.payload.SegregationOfDutyDtos.SegregationOfDutyCheckRequest;
import com.enterprise.mm.payload.SegregationOfDutyDtos.SegregationOfDutyCheckResult;
import com.enterprise.mm.payload.SegregationOfDutyDtos.SegregationOfDutyRuleResult;
import com.enterprise.mm.payload.SegregationOfDutyRuleResponse;
import com.enterprise.mm.payload.SegregationOfDutyRuleUpdateRequest;
import com.enterprise.mm.payload.SegregationOfDutyRuleCreateRequest;
import com.enterprise.mm.payload.SegregationOfDutyRuleSearchRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.SegregationOfDutyRuleMapper;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SegregationOfDutyRule;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.SegregationOfDutyRuleRepository;
import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SegregationOfDutyRuleService {
    private final SegregationOfDutyRuleMapper mapper;
    private final String entityName = "SegregationOfDutyRule";
    private static final String CREATOR_CANNOT_APPROVE = "CREATOR_CANNOT_APPROVE";
    private static final String SAME_USER_FIELD = "SAME_USER_FIELD";
    private static final String ACTION_ACTOR_CANNOT_PERFORM = "ACTION_ACTOR_CANNOT_PERFORM";
    private static final String BLOCK = "BLOCK";
    private static final String WARN = "WARN";
    private final SegregationOfDutyRuleRepository repository;
    private final AuditLogRepository auditLogRepository;
    private final SegregationOfDutyViolationService violationService;
    private final AuditService auditService;
    protected void beforeCreate(SegregationOfDutyRule entity, SegregationOfDutyRuleCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        ensureUnique(entity);
    }
    protected void beforeUpdate(SegregationOfDutyRule entity, SegregationOfDutyRuleUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalize(entity);
        ensureUnique(entity);
    }
    public SegregationOfDutyCheckResult evaluate(String entityType, String actionCode, BusinessEntity entity) {
        String normalizedEntityType = normalizeToken(entityType);
        String normalizedAction = normalizeToken(actionCode);
        var rules = repository.findByTenantIdAndCompanyIdAndEntityTypeAndActionCodeAndActiveFlagTrueAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizedEntityType,
            normalizedAction
        );
        if (rules.isEmpty()) {
            return new SegregationOfDutyCheckResult(true, "ALLOW", List.of());
        }
        if (rules.isEmpty()) {
            return new SegregationOfDutyCheckResult(true, "ALLOW", List.of());
        }
        String currentUser = currentUsername();
        Set<String> currentRoles = currentRoles();
        List<SegregationOfDutyRuleResult> results = new ArrayList<>();
        boolean blocked = false;
        for (SegregationOfDutyRule rule : rules) {
            RuleMatch match = match(rule, normalizedEntityType, normalizedAction, entity, currentUser, currentRoles);
            if (!match.matched()) {
                continue;
            }
            if (rule.isAllowAdminOverride() && currentUserHasRole("ROLE_ADMIN")) {
                record(rule, entity, normalizedEntityType, normalizedAction, currentUser, match.matchedPrincipal(), "OVERRIDDEN", match.message());
                auditService.log("SegregationOfDutyRule", rule.getId(), "ADMIN_OVERRIDE", normalizedEntityType + ":" + normalizedAction + ":" + entity.getId(), null, null);
                results.add(toResult(rule, "OVERRIDDEN", match.matchedPrincipal(), match.message()));
                continue;
            }
            String decision = WARN.equals(rule.getSeverity()) ? "WARNED" : "BLOCKED";
            record(rule, entity, normalizedEntityType, normalizedAction, currentUser, match.matchedPrincipal(), decision, match.message());
            results.add(toResult(rule, decision, match.matchedPrincipal(), match.message()));
            if (BLOCK.equals(rule.getSeverity())) {
                blocked = true;
            }
        }
        return new SegregationOfDutyCheckResult(!blocked, blocked ? "BLOCK" : results.isEmpty() ? "ALLOW" : "WARN", results);
    }
    public void validate(String entityType, String actionCode, BusinessEntity entity) {
        SegregationOfDutyCheckResult result = evaluate(entityType, actionCode, entity);
        if (!result.allowed()) {
            String message = result.rules().stream()
                .filter(rule -> "BLOCKED".equals(rule.decision()))
                .map(SegregationOfDutyRuleResult::message)
                .findFirst()
                .orElse("Segregation of Duties violation");
            throw new BusinessException(message);
        }
    }

    /**
     * Shared maker-checker gate for approval adapters.  The creator check is
     * enforced even when a tenant has not yet provisioned a configurable rule,
     * so a missing rule cannot silently disable segregation of duties.
     * Administrator bypass is available only when an active rule explicitly
     * grants it; the normal UI has no path to request that bypass.
     *
     * TEMPORARY (testing): maker-checker creator==approver block is disabled so
     * the same user can approve documents they created. Re-enable by restoring
     * the block below when SoD must be enforced again.
     */
    public void validateApproval(String entityType, BusinessEntity entity) {
        validateApproval(entityType, entity, null);
    }

    public void validateApproval(String entityType, BusinessEntity entity, String overrideReason) {
        validate(entityType, "APPROVE", entity);
        // TEMPORARY: allow document creator to approve (re-enable maker-checker later).
        // Previous behavior blocked when current user == entity.createdBy unless
        // ROLE_ADMIN + allowAdminOverride rule + non-blank overrideReason.
    }
    public SegregationOfDutyCheckResult check(SegregationOfDutyCheckRequest request) {
        if (request == null || request.entityType() == null || request.actionCode() == null) {
            throw new BusinessException("entityType and actionCode are required");
        }
        ManualSubject subject = new ManualSubject();
        subject.setId(request.entityId() == null ? UUID.randomUUID() : request.entityId());
        subject.setName(request.entityType());
        subject.setStatus(request.currentStatus());
        subject.setCreatedBy(request.createdBy());
        subject.setUpdatedBy(request.updatedBy());
        subject.setOwnerUsername(request.ownerUsername());
        subject.setRequestedBy(request.requestedBy());
        return evaluate(request.entityType(), request.actionCode(), subject);
    }
    @Transactional
    public SegregationOfDutyRuleResponse ensureDefaultMakerCheckerRule() {
        return ensureRule("PR maker-checker", "PurchaseRequest", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "APPROVAL_PENDING", "BLOCK", "Создатель заявки не может сам ее согласовать");
    }
    @Transactional
    public List<SegregationOfDutyRuleResponse> ensureDefaultMakerCheckerRules() {
        return List.of(
            ensureRule("PR maker-checker", "PurchaseRequest", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "APPROVAL_PENDING", "BLOCK", "Создатель заявки не может сам ее согласовать"),
            ensureRule("PR submitter-checker", "PurchaseRequest", "APPROVE", ACTION_ACTOR_CANNOT_PERFORM, null, "SUBMIT", "APPROVAL_PENDING", "BLOCK", "Пользователь, отправивший заявку, не может сам ее согласовать"),
            ensureRule("PMD maker-checker", "ProcurementMethodDecision", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "UNDER_REVIEW", "BLOCK", "Создатель решения по способу закупки не может сам его согласовать"),
            ensureRule("Direct award maker-checker", "ProcurementDirectAward", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "APPROVAL_PENDING", "BLOCK", "Создатель прямого присуждения не может сам его согласовать"),
            ensureRule("Purchase order maker-checker", "PurchaseOrder", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "APPROVAL_PENDING", "BLOCK", "Создатель заказа не может сам его согласовать"),
            ensureRule("Demand plan maker-checker", "DemandPlan", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "SUBMITTED", "BLOCK", "Создатель плана потребности не может сам его согласовать"),
            ensureRule("Demand item maker-checker", "DemandPlanItem", "APPROVE", CREATOR_CANNOT_APPROVE, "CREATED_BY", null, "DRAFT,SUBMITTED", "BLOCK", "Создатель строки потребности не может сам ее согласовать"),
            ensureRule("Procurement plan maker-checker", "ProcurementPlan", "APPROVE", ACTION_ACTOR_CANNOT_PERFORM, null, "SUBMIT_BUDGET", "BUDGET_REVIEW", "BLOCK", "Пользователь, отправивший план закупок на бюджет, не может сам его согласовать"),
            ensureRule("Category strategy maker-checker", "ProcurementCategoryStrategy", "APPROVE", ACTION_ACTOR_CANNOT_PERFORM, null, "SUBMIT", "UNDER_REVIEW", "BLOCK", "Пользователь, отправивший категорийную стратегию, не может сам ее согласовать")
        );
    }
    private SegregationOfDutyRuleResponse ensureRule(
        String name,
        String entityType,
        String actionCode,
        String ruleType,
        String subjectField,
        String sourceActionCode,
        String statusScope,
        String severity,
        String message
    ) {
        String normalizedEntityType = normalizeToken(entityType);
        String normalizedAction = normalizeToken(actionCode);
        String normalizedRuleType = normalizeToken(ruleType);
        return repository.findByTenantIdAndCompanyIdAndEntityTypeAndActionCodeAndRuleTypeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                normalizedEntityType,
                normalizedAction,
                normalizedRuleType
            )
            .map(mapper::toResponse)
            .orElseGet(() -> {
                SegregationOfDutyRuleCreateRequest dto = new SegregationOfDutyRuleCreateRequest();
                dto.setName(name);
                dto.setEntityType(entityType);
                dto.setActionCode(actionCode);
                dto.setRuleType(ruleType);
                dto.setSubjectField(subjectField);
                dto.setSourceActionCode(sourceActionCode);
                dto.setStatusScope(statusScope);
                dto.setActiveFlag(true);
                dto.setAllowAdminOverride(true);
                dto.setSeverity(severity);
                dto.setMessage(message);
                return create(dto);
            });
    }
    private void normalize(SegregationOfDutyRule entity) {
        entity.setEntityType(normalizeToken(entity.getEntityType()));
        entity.setActionCode(normalizeToken(entity.getActionCode()));
        entity.setRuleType(normalizeToken(entity.getRuleType() == null || entity.getRuleType().isBlank() ? CREATOR_CANNOT_APPROVE : entity.getRuleType()));
        if (entity.getSubjectField() != null && !entity.getSubjectField().isBlank()) {
            entity.setSubjectField(normalizeToken(entity.getSubjectField()));
        }
        if (entity.getSourceActionCode() != null && !entity.getSourceActionCode().isBlank()) {
            entity.setSourceActionCode(normalizeToken(entity.getSourceActionCode()));
        }
        entity.setStatusScope(normalizeCsv(entity.getStatusScope()));
        entity.setAppliesToRoles(normalizeCsv(entity.getAppliesToRoles()));
        entity.setExemptRoles(normalizeCsv(entity.getExemptRoles()));
        entity.setSeverity(normalizeToken(entity.getSeverity() == null || entity.getSeverity().isBlank() ? BLOCK : entity.getSeverity()));
        if (!Set.of(BLOCK, WARN).contains(entity.getSeverity())) {
            throw new BusinessException("Unsupported SoD severity: " + entity.getSeverity());
        }
        if (entity.getMessage() == null || entity.getMessage().isBlank()) {
            entity.setMessage("Segregation of Duties rule violation");
        }
        if (!Set.of(CREATOR_CANNOT_APPROVE, SAME_USER_FIELD, ACTION_ACTOR_CANNOT_PERFORM).contains(entity.getRuleType())) {
            throw new BusinessException("Unsupported SoD rule type: " + entity.getRuleType());
        }
        if (CREATOR_CANNOT_APPROVE.equals(entity.getRuleType()) && (entity.getSubjectField() == null || entity.getSubjectField().isBlank())) {
            entity.setSubjectField("CREATED_BY");
        }
        if (SAME_USER_FIELD.equals(entity.getRuleType()) && (entity.getSubjectField() == null || entity.getSubjectField().isBlank())) {
            throw new BusinessException("subjectField is required for SAME_USER_FIELD SoD rule");
        }
        if (ACTION_ACTOR_CANNOT_PERFORM.equals(entity.getRuleType()) && (entity.getSourceActionCode() == null || entity.getSourceActionCode().isBlank())) {
            throw new BusinessException("sourceActionCode is required for ACTION_ACTOR_CANNOT_PERFORM SoD rule");
        }
    }
    private void ensureUnique(SegregationOfDutyRule entity) {
        repository.findByTenantIdAndCompanyIdAndEntityTypeAndActionCodeAndRuleTypeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                entity.getEntityType(),
                entity.getActionCode(),
                entity.getRuleType()
            )
            .filter(existing -> !existing.getId().equals(entity.getId()))
            .ifPresent(existing -> {
                throw new BusinessException("Segregation of Duties rule already exists for entity/action/rule type");
            });
    }
    private String normalizeToken(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("SoD rule token is required");
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }
    private String normalizeCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .map(this::normalizeToken)
            .distinct()
            .collect(Collectors.joining(","));
    }
    private RuleMatch match(SegregationOfDutyRule rule, String entityType, String actionCode, BusinessEntity entity, String currentUser, Set<String> currentRoles) {
        if (!roleScopeMatches(rule, currentRoles) || !statusScopeMatches(rule, entity.getStatus())) {
            return RuleMatch.no();
        }
        if (CREATOR_CANNOT_APPROVE.equals(rule.getRuleType()) || SAME_USER_FIELD.equals(rule.getRuleType())) {
            String principal = readSubject(entity, CREATOR_CANNOT_APPROVE.equals(rule.getRuleType()) ? "CREATED_BY" : rule.getSubjectField());
            return same(currentUser, principal) ? RuleMatch.yes(principal, message(rule)) : RuleMatch.no();
        }
        if (ACTION_ACTOR_CANNOT_PERFORM.equals(rule.getRuleType())) {
            boolean matched = auditLogRepository.existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndActionAndUsernameAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                canonicalEntityType(entityType),
                entity.getId(),
                rule.getSourceActionCode(),
                currentUser
            );
            return matched ? RuleMatch.yes(currentUser, message(rule)) : RuleMatch.no();
        }
        return RuleMatch.no();
    }
    private boolean roleScopeMatches(SegregationOfDutyRule rule, Set<String> currentRoles) {
        Set<String> exemptRoles = csvSet(rule.getExemptRoles());
        if (!exemptRoles.isEmpty() && currentRoles.stream().anyMatch(exemptRoles::contains)) {
            return false;
        }
        Set<String> appliesToRoles = csvSet(rule.getAppliesToRoles());
        return appliesToRoles.isEmpty() || currentRoles.stream().anyMatch(appliesToRoles::contains);
    }
    private boolean statusScopeMatches(SegregationOfDutyRule rule, String status) {
        Set<String> statuses = csvSet(rule.getStatusScope());
        return statuses.isEmpty() || statuses.contains(normalizeNullable(status));
    }
    private Set<String> csvSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .map(this::normalizeNullable)
            .collect(Collectors.toSet());
    }
    private String readSubject(BusinessEntity entity, String subjectField) {
        String normalized = normalizeToken(subjectField == null || subjectField.isBlank() ? "CREATED_BY" : subjectField);
        if ("CREATED_BY".equals(normalized)) {
            return entity.getCreatedBy();
        }
        if ("UPDATED_BY".equals(normalized)) {
            return entity.getUpdatedBy();
        }
        return readBeanProperty(entity, toPropertyName(normalized));
    }
    private String readBeanProperty(Object entity, String propertyName) {
        try {
            String getter = "get" + propertyName.substring(0, 1).toUpperCase(Locale.ROOT) + propertyName.substring(1);
            Method method = entity.getClass().getMethod(getter);
            Object value = method.invoke(entity);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
    private String toPropertyName(String normalized) {
        String[] parts = normalized.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder builder = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            builder.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
        }
        return Introspector.decapitalize(builder.toString());
    }
    private String canonicalEntityType(String normalizedEntityType) {
        return switch (normalizedEntityType) {
            case "PURCHASEREQUEST" -> "PurchaseRequest";
            case "DEMANDPLAN" -> "DemandPlan";
            case "DEMANDPLANITEM" -> "DemandPlanItem";
            case "PROCUREMENTPLAN" -> "ProcurementPlan";
            case "PROCUREMENTCATEGORYSTRATEGY" -> "ProcurementCategoryStrategy";
            default -> normalizedEntityType;
        };
    }
    private String message(SegregationOfDutyRule rule) {
        return rule.getMessage() == null || rule.getMessage().isBlank()
            ? "Segregation of Duties violation"
            : rule.getMessage();
    }
    private void record(SegregationOfDutyRule rule, BusinessEntity entity, String entityType, String actionCode, String username, String matchedPrincipal, String decision, String message) {
        violationService.record(rule, entity.getId(), entityType, actionCode, username, matchedPrincipal, decision, message);
    }
    private SegregationOfDutyRuleResult toResult(SegregationOfDutyRule rule, String decision, String matchedPrincipal, String message) {
        return new SegregationOfDutyRuleResult(rule.getId(), rule.getRuleType(), rule.getSeverity(), decision, matchedPrincipal, message);
    }
    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "system" : authentication.getName();
    }
    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .map(this::normalizeNullable)
            .collect(Collectors.toSet());
    }
    private boolean currentUserHasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(authority -> role.equals(authority.getAuthority()));
    }
    private boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "").replaceAll("[^A-Z0-9_]+", "_");
    }
    private record RuleMatch(boolean matched, String matchedPrincipal, String message) {
        private static RuleMatch yes(String matchedPrincipal, String message) {
            return new RuleMatch(true, matchedPrincipal, message);
        }
        private static RuleMatch no() {
            return new RuleMatch(false, null, null);
        }
    }
    @Getter
    @Setter
    public static final class ManualSubject extends BusinessEntity {
        private String ownerUsername;
        private String requestedBy;
    }
    @Transactional(readOnly = true)
    public Page<SegregationOfDutyRuleResponse> search(SegregationOfDutyRuleSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public SegregationOfDutyRuleResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public SegregationOfDutyRuleResponse create(SegregationOfDutyRuleCreateRequest dto) {
        SegregationOfDutyRule entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        SegregationOfDutyRule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public SegregationOfDutyRuleResponse update(UUID id, SegregationOfDutyRuleUpdateRequest dto) {
        SegregationOfDutyRule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        SegregationOfDutyRule saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        SegregationOfDutyRule entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public SegregationOfDutyRuleResponse restore(UUID id) {
        SegregationOfDutyRule entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        SegregationOfDutyRule saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected SegregationOfDutyRule findEntity(UUID id) {
        SegregationOfDutyRule entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}
