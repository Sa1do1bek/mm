package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementMethodCapability;
import com.enterprise.mm.enums.ProcurementMethodCapabilityState;
import com.enterprise.mm.enums.ProcurementMethodType;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ProcurementMethodCapabilityDtos;
import com.enterprise.mm.repository.ProcurementMethodCapabilityRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementMethodCapabilityService {
    /** Stable localization keys returned in Evaluation.blockingReasons. */
    public static final String NOT_CONFIGURED = "procurement.method_capability.not_configured";
    public static final String MISSING_AMOUNT = "procurement.method_capability.amount_required";
    public static final String MISSING_CURRENCY = "procurement.method_capability.currency_required";
    public static final String MISSING_REQUEST_TYPE = "procurement.method_capability.request_type_required";
    public static final String MISSING_CATEGORY = "procurement.method_capability.category_required";
    private final ProcurementMethodCapabilityRepository repository;
    private final ProcurementMethodPolicy methodPolicy;
    // Effective dates are business dates in the configured application timezone.
    private final Clock clock = Clock.system(ZoneId.of("Asia/Tashkent"));

    @Transactional(readOnly = true)
    public List<ProcurementMethodCapabilityDtos.Response> list() {
        return repository.findByTenantIdAndCompanyIdAndIsDeletedFalseOrderByMethodCodeAsc(
                TenantContext.currentTenant(), CompanyContext.currentCompany()).stream().map(this::response).toList();
    }

    /** Returns every canonical method, including unconfigured methods as unavailable defaults. */
    @Transactional(readOnly = true)
    public List<ProcurementMethodCapabilityDtos.Evaluation> availability() {
        return availability(null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<ProcurementMethodCapabilityDtos.Evaluation> availability(BigDecimal amount, String currency,
                                                                          String requestType, String categoryId) {
        return java.util.Arrays.stream(ProcurementMethodType.values())
            .map(method -> evaluate(method.name(), amount, currency, requestType, categoryId))
            .toList();
    }

    @Transactional(readOnly = true)
    public ProcurementMethodCapabilityDtos.Response get(String method) {
        return response(find(method));
    }

    @Transactional(readOnly = true)
    public ProcurementMethodCapabilityDtos.Evaluation evaluate(String method, BigDecimal amount, String currency,
                                                                String requestType, String categoryId) {
        return evaluateForCompany(method, amount, currency, requestType, categoryId, CompanyContext.currentCompany());
    }

    public ProcurementMethodCapabilityDtos.Evaluation evaluateForCompany(String method, BigDecimal amount, String currency,
                                                                          String requestType, String categoryId, UUID companyId) {
        String normalized = normalizeMethod(method);
        ProcurementMethodCapability capability = repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(
                TenantContext.currentTenant(), companyId, normalized).orElse(null);
        if (capability == null) return unavailable(normalized, NOT_CONFIGURED, null);
        List<String> reasons = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        ProcurementMethodCapabilityState state = capability.state();
        if (state == ProcurementMethodCapabilityState.DISABLED || state == ProcurementMethodCapabilityState.LEGACY
            || state == ProcurementMethodCapabilityState.DEPRECATED) reasons.add(reason(state.name().toLowerCase(Locale.ROOT)));
        if (capability.getEffectiveFrom() != null && today.isBefore(capability.getEffectiveFrom())) reasons.add(reason("not_yet_effective"));
        if (capability.getEffectiveTo() != null && today.isAfter(capability.getEffectiveTo())) reasons.add(reason("expired"));
        if ((capability.getMinAmount() != null || capability.getMaxAmount() != null) && amount == null) reasons.add(MISSING_AMOUNT);
        if (amount != null && capability.getMinAmount() != null && amount.compareTo(capability.getMinAmount()) < 0) reasons.add(reason("amount_below_minimum"));
        if (amount != null && capability.getMaxAmount() != null && amount.compareTo(capability.getMaxAmount()) > 0) reasons.add(reason("amount_above_maximum"));
        if (capability.getCurrencyCode() != null && currency == null) reasons.add(MISSING_CURRENCY);
        if (currency != null && capability.getCurrencyCode() != null && !capability.getCurrencyCode().equalsIgnoreCase(currency)) reasons.add(reason("currency_not_allowed"));
        Set<String> requestTypes = capability.getAllowedRequestTypes() == null ? Set.of() : capability.getAllowedRequestTypes();
        Set<String> categoryIds = capability.getAllowedCategoryIds() == null ? Set.of() : capability.getAllowedCategoryIds();
        if (!requestTypes.isEmpty() && requestType == null) reasons.add(MISSING_REQUEST_TYPE);
        if (requestType != null && !requestTypes.isEmpty() && !containsIgnoreCase(requestTypes, requestType)) reasons.add(reason("request_type_not_allowed"));
        if (!categoryIds.isEmpty() && categoryId == null) reasons.add(MISSING_CATEGORY);
        if (categoryId != null && !categoryIds.isEmpty() && !categoryIds.contains(categoryId)) reasons.add(reason("category_not_allowed"));
        return new ProcurementMethodCapabilityDtos.Evaluation(normalized, reasons.isEmpty(), state, List.copyOf(reasons), metadata(capability, reasons.isEmpty()));
    }

    public ProcurementMethodCapabilityDtos.Evaluation check(String method, BigDecimal amount, String currency, String requestType, String categoryId) {
        return evaluate(method, amount, currency, requestType, categoryId);
    }

    /** Enforce capability at a state-changing boundary, preserving stable localization keys. */
    public ProcurementMethodCapabilityDtos.Evaluation requireAvailable(String method, BigDecimal amount, String currency,
                                                                        String requestType, String categoryId) {
        ProcurementMethodCapabilityDtos.Evaluation evaluation = evaluate(method, amount, currency, requestType, categoryId);
        if (!evaluation.available()) {
            String key = evaluation.blockingReasons().isEmpty() ? NOT_CONFIGURED : evaluation.blockingReasons().get(0);
            throw new BusinessException(String.join(", ", evaluation.blockingReasons()), key, evaluation.blockingReasons().toArray());
        }
        return evaluation;
    }

    public ProcurementMethodCapabilityDtos.Evaluation requireAvailableForCompany(String method, BigDecimal amount, String currency,
                                                                                   String requestType, String categoryId, UUID companyId) {
        ProcurementMethodCapabilityDtos.Evaluation evaluation = evaluateForCompany(method, amount, currency, requestType, categoryId, companyId);
        if (!evaluation.available()) {
            String key = evaluation.blockingReasons().isEmpty() ? NOT_CONFIGURED : evaluation.blockingReasons().get(0);
            throw new BusinessException(String.join(", ", evaluation.blockingReasons()), key, evaluation.blockingReasons().toArray());
        }
        return evaluation;
    }

    @Transactional
    public ProcurementMethodCapabilityDtos.Response create(ProcurementMethodCapabilityDtos.UpsertRequest request) {
        String method = normalizeMethod(request.methodCode());
        validate(request);
        if (repository.existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), method)) {
            throw new BusinessException("An active capability already exists for method " + method);
        }
        return response(repository.save(apply(new ProcurementMethodCapability(), request, method)));
    }

    @Transactional
    public ProcurementMethodCapabilityDtos.Response update(UUID id, ProcurementMethodCapabilityDtos.UpsertRequest request) {
        ProcurementMethodCapability capability = find(id);
        String method = normalizeMethod(request.methodCode());
        if (!method.equals(capability.getMethodCode()) && repository.existsByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), method)) {
            throw new BusinessException("An active capability already exists for method " + method);
        }
        validate(request);
        return response(repository.save(apply(capability, request, method)));
    }

    private ProcurementMethodCapability find(String method) {
        return repository.findByTenantIdAndCompanyIdAndMethodCodeAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany(), normalizeMethod(method))
            .orElseThrow(() -> new NotFoundException("Procurement method capability not found: " + method));
    }
    private ProcurementMethodCapability find(UUID id) {
        return repository.findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Procurement method capability not found: " + id));
    }
    private String normalizeMethod(String value) { return methodPolicy.normalize(value); }
    private void validate(ProcurementMethodCapabilityDtos.UpsertRequest request) {
        normalizeMethod(request.methodCode());
        if (request.effectiveFrom() != null && request.effectiveTo() != null && request.effectiveFrom().isAfter(request.effectiveTo())) throw new BusinessException("effectiveFrom must be before or equal to effectiveTo");
        if ((request.minAmount() != null && request.minAmount().signum() < 0) || (request.maxAmount() != null && request.maxAmount().signum() < 0)) throw new BusinessException("Capability amount thresholds cannot be negative");
        if (request.minAmount() != null && request.maxAmount() != null && request.minAmount().compareTo(request.maxAmount()) > 0) throw new BusinessException("minAmount must be less than or equal to maxAmount");
        if (request.currencyCode() != null && !request.currencyCode().matches("[A-Za-z]{3}")) throw new BusinessException("currencyCode must be a three-letter ISO code");
        if (request.configurationVersion() != null && request.configurationVersion() < 1) throw new BusinessException("configurationVersion must be positive");
    }
    private ProcurementMethodCapability apply(ProcurementMethodCapability c, ProcurementMethodCapabilityDtos.UpsertRequest r, String method) {
        if (c.getTenantId() == null) c.setTenantId(TenantContext.currentTenant());
        if (c.getCompanyId() == null) c.setCompanyId(CompanyContext.currentCompany());
        c.setMethodCode(method); c.setName(method + " capability"); c.setCode("PM-CAP-" + method); c.setState(r.state()); c.setEffectiveFrom(r.effectiveFrom()); c.setEffectiveTo(r.effectiveTo()); c.setMinAmount(r.minAmount()); c.setMaxAmount(r.maxAmount()); c.setCurrencyCode(r.currencyCode() == null ? null : r.currencyCode().toUpperCase(Locale.ROOT)); c.setAllowedRequestTypes(normalizeRestrictions(r.allowedRequestTypes())); c.setAllowedCategoryIds(normalizeRestrictions(r.allowedCategoryIds())); c.setJustificationRequired(r.justificationRequired()); c.setSupplierRequired(r.supplierRequired()); c.setApprovalRequired(r.approvalRequired()); c.setConfigurationVersion(r.configurationVersion() == null ? (c.getConfigurationVersion() == null ? 1 : c.getConfigurationVersion() + 1) : r.configurationVersion()); c.setChangeReason(r.reason()); c.setConfiguredAt(java.time.LocalDateTime.now(clock)); c.setConfiguredBy(actor()); return c;
    }
    private ProcurementMethodCapabilityDtos.Response response(ProcurementMethodCapability c) { return new ProcurementMethodCapabilityDtos.Response(c.getId(), c.getTenantId(), c.getCompanyId(), c.getMethodCode(), c.state(), c.getEffectiveFrom(), c.getEffectiveTo(), c.getMinAmount(), c.getMaxAmount(), c.getCurrencyCode(), c.getAllowedRequestTypes(), c.getAllowedCategoryIds(), c.isJustificationRequired(), c.isSupplierRequired(), c.isApprovalRequired(), c.getConfigurationVersion(), c.getChangeReason(), c.getConfiguredAt(), c.getConfiguredBy()); }
    private ProcurementMethodCapabilityDtos.Evaluation unavailable(String method, String reason, ProcurementMethodCapability c) { return new ProcurementMethodCapabilityDtos.Evaluation(method, false, c == null ? null : c.state(), List.of(reason), metadata(c, false)); }
    private ProcurementMethodCapabilityDtos.FrontendMetadata metadata(ProcurementMethodCapability c, boolean available) { return new ProcurementMethodCapabilityDtos.FrontendMetadata(c != null && available, c != null && c.state() == ProcurementMethodCapabilityState.PILOT, c != null && c.state() == ProcurementMethodCapabilityState.LEGACY, c != null && c.state() == ProcurementMethodCapabilityState.DEPRECATED, c != null && c.isJustificationRequired(), c != null && c.isSupplierRequired(), c != null && c.isApprovalRequired(), c == null ? null : c.getConfigurationVersion()); }
    private boolean containsIgnoreCase(Set<String> values, String value) { return values.stream().filter(java.util.Objects::nonNull).anyMatch(v -> v.equalsIgnoreCase(value)); }
    private Set<String> normalizeRestrictions(Set<String> values) { return values == null ? new LinkedHashSet<>() : values.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)); }
    private String reason(String suffix) { return "procurement.method_capability." + suffix; }
    private String actor() { Authentication authentication = SecurityContextHolder.getContext().getAuthentication(); return authentication == null || !authentication.isAuthenticated() || authentication.getName() == null ? "system" : authentication.getName(); }
}
