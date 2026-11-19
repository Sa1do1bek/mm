package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.CurrencyRateResponse;
import com.enterprise.mm.payload.CurrencyRateUpdateRequest;
import com.enterprise.mm.payload.CurrencyRateCreateRequest;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.CurrencyRateSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CurrencyRateMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.CurrencyRate;
import com.enterprise.mm.repository.CurrencyRateRepository;
import com.enterprise.mm.repository.CurrencyRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrencyRateService {
    private final CurrencyRateRepository repository;
    private final CurrencyRateMapper mapper;
    private final String entityName = "CurrencyRate";
    private final AuditService auditService;
    private static final String DEFAULT_BASE_CURRENCY = "UZS";
    private final CurrencyRateRepository currencyRateRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateService exchangeRateService;

    protected void beforeCreate(CurrencyRate entity, CurrencyRateCreateRequest dto) {
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

        normalizeAndValidate(entity, false);
    }
    protected void beforeUpdate(CurrencyRate entity, CurrencyRateUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeAndValidate(entity, true);
    }
    private void normalizeAndValidate(CurrencyRate entity, boolean update) {
        String tenantId = TenantContext.currentTenant();
        Currency currency = resolveCurrency(entity, tenantId);
        entity.setCurrencyId(currency.getId());
        entity.setCurrencyCode(normalizeCode(Objects.requireNonNullElse(entity.getCurrencyCode(), currency.getIsoCode())));
        entity.setBaseCurrencyCode(normalizeCode(Objects.requireNonNullElse(entity.getBaseCurrencyCode(), DEFAULT_BASE_CURRENCY)));
        if (entity.getRateDate() == null) {
            entity.setRateDate(LocalDate.now());
        }
        if (entity.getNominal() == null) {
            entity.setNominal(BigDecimal.ONE);
        }
        if (entity.getSource() == null || entity.getSource().isBlank()) {
            entity.setSource("MANUAL");
        }
        if (entity.getRate() == null || entity.getRate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Currency rate must be greater than zero");
        }
        if (entity.getNominal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Currency rate nominal must be greater than zero");
        }
        if (entity.getCode() == null || entity.getCode().isBlank() || entity.getCode().startsWith("CURRENCYRATE-")) {
            entity.setCode("RATE-" + entity.getCurrencyCode() + "-" + entity.getBaseCurrencyCode() + "-" + entity.getRateDate());
        }
        if (entity.getName() == null || entity.getName().isBlank() || entity.getName().equals(entity.getCode())) {
            entity.setName(entity.getCurrencyCode() + "/" + entity.getBaseCurrencyCode() + " " + entity.getRateDate());
        }
        repository.findByTenantIdAndCurrencyCodeAndBaseCurrencyCodeAndRateDateAndIsDeletedFalse(
            tenantId,
            entity.getCurrencyCode(),
            entity.getBaseCurrencyCode(),
            entity.getRateDate()
        ).ifPresent(existing -> {
            if (!update || !existing.getId().equals(entity.getId())) {
                throw new BadRequestException("Currency rate already exists for this date and currency pair");
            }
        });
    }
    private Currency resolveCurrency(CurrencyRate entity, String tenantId) {
        if (entity.getCurrencyId() != null) {
            Currency currency = currencyRepository.findByIdAndIsDeletedFalse(entity.getCurrencyId())
                .filter(candidate -> tenantId.equals(candidate.getTenantId()) && !candidate.isDeleted())
                .orElseThrow(() -> new NotFoundException("Currency not found: " + entity.getCurrencyId()));
            entity.setCurrencyCode(currency.getIsoCode());
            return currency;
        }
        String currencyCode = normalizeCode(entity.getCurrencyCode());
        if (currencyCode == null) {
            throw new BadRequestException("Currency is required");
        }
        return currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(tenantId, currencyCode)
            .or(() -> currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse(tenantId, currencyCode))
            .orElseThrow(() -> new NotFoundException("Currency not found: " + currencyCode));
    }
    private void syncExchangeRate(CurrencyRate rate) {
        if (!DEFAULT_BASE_CURRENCY.equals(rate.getBaseCurrencyCode())) {
            return;
        }
        ExchangeRateRequest request = new ExchangeRateRequest();
        request.setCurrencyId(rate.getCurrencyId());
        request.setRateDate(rate.getRateDate());
        request.setRateToBase(rate.getRate().divide(rate.getNominal(), 6, RoundingMode.HALF_UP));
        request.setSource(rate.getSource());
        exchangeRateService.upsert(request);
    }
    private String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }
    @Transactional(readOnly = true)
    public Page<CurrencyRateResponse> search(CurrencyRateSearchRequest request, Pageable pageable) {
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
    public CurrencyRateResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public CurrencyRateResponse create(CurrencyRateCreateRequest dto) {
        CurrencyRate entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        CurrencyRate saved = repository.save(entity);
        syncExchangeRate(saved);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CurrencyRateResponse update(UUID id, CurrencyRateUpdateRequest dto) {
        CurrencyRate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        CurrencyRate saved = repository.save(entity);
        syncExchangeRate(saved);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        CurrencyRate entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public CurrencyRateResponse restore(UUID id) {
        CurrencyRate entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        CurrencyRate saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected CurrencyRate findEntity(UUID id) {
        CurrencyRate entity = repository.findByIdAndIsDeletedFalse(id)
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

