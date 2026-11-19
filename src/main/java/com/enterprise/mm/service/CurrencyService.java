package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.payload.CurrencyResponse;
import com.enterprise.mm.payload.CurrencyUpdateRequest;
import com.enterprise.mm.payload.CurrencyCreateRequest;
import com.enterprise.mm.payload.CurrencySearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.CurrencyMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.repository.CurrencyRepository;

import java.util.*;
import java.util.stream.Collectors;

import com.enterprise.mm.repository.ExchangeRateRepository;
import com.enterprise.mm.security.AccessScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CurrencyService {
    private final CurrencyRepository repository;
    private final CurrencyMapper mapper;
    private final String entityName = "Currency";
    private final AuditService auditService;
    private final ExchangeRateRepository exchangeRateRepository;

    protected void beforeCreate(Currency entity, CurrencyCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        normalizeCurrency(entity);
        if (entity.getName() == null || entity.getName().isBlank()) {
            entity.setName(entity.getIsoCode());
        }
        validateCurrency(entity);
    }
    protected void beforeUpdate(Currency entity, CurrencyUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        normalizeCurrency(entity);
        validateCurrency(entity);
    }
    private void normalizeCurrency(Currency entity) {
        String isoCode = firstNonBlank(entity.getIsoCode(), entity.getCode());
        if (isoCode == null) {
            throw new BadRequestException("ISO currency code is required");
        }
        isoCode = isoCode.trim().toUpperCase(Locale.ROOT);
        if (!isoCode.matches("[A-Z]{3}")) {
            throw new BadRequestException("ISO currency code must contain exactly three letters");
        }
        try {
            java.util.Currency.getInstance(isoCode);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown ISO currency code: " + isoCode);
        }
        entity.setIsoCode(isoCode);
        entity.setCode(isoCode);
        if (entity.getMinorUnit() == null) {
            entity.setMinorUnit(2);
        }
        if (entity.getMinorUnit() < 0 || entity.getMinorUnit() > 4) {
            throw new BadRequestException("Currency minor unit must be between 0 and 4");
        }
        if (entity.getNumericCode() != null) {
            String numericCode = entity.getNumericCode().trim();
            if (!numericCode.isEmpty() && !numericCode.matches("[0-9]{3}")) {
                throw new BadRequestException("ISO numeric currency code must contain exactly three digits");
            }
            entity.setNumericCode(numericCode.isEmpty() ? null : numericCode);
        }
    }

    private void validateCurrency(Currency entity) {
        repository.findByTenantIdAndIsoCodeIgnoreCaseAndIsDeletedFalse(
            TenantContext.currentTenant(), entity.getIsoCode())
            .filter(existing -> !Objects.equals(existing.getId(), entity.getId()))
            .ifPresent(existing -> {
                throw new BadRequestException("Currency ISO code already exists: " + entity.getIsoCode());
            });
        if (!entity.isBaseCurrency()) {
            return;
        }
        boolean anotherBase = entity.getId() == null
            ? repository.existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalse(TenantContext.currentTenant())
            : repository.existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalseAndIdNot(
                TenantContext.currentTenant(), entity.getId());
        if (anotherBase) {
            throw new BusinessException("Only one base currency is allowed per tenant");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
    @Transactional(readOnly = true)
    public Page<CurrencyResponse> search(CurrencySearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
         Page<Currency> currencyPage = repository.search(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                filter.includeDeleted(),
                filter.normalizedSearch(),
                filter.fromDateTime(),
                filter.toDateTime(),
                filter.status(),
                !filter.hasStatus(),
                pageable
        );

        if (currencyPage.isEmpty()) {
            return currencyPage.map(mapper::toResponse);
        }
         List<UUID> currencyIds = currencyPage.getContent().stream()
                .map(Currency::getId)
                .toList();
        List<ExchangeRate> latestRates = exchangeRateRepository.findLatestRatesByCurrencyIds(currencyIds);
        Map<UUID, ExchangeRate> ratesMap = latestRates.stream()
                .collect(Collectors.toMap(
                        rate -> rate.getCurrency().getId(),
                        rate -> rate,
                        (existing, replacement) -> existing
                ));
 return currencyPage.map(currency -> {
            CurrencyResponse response = mapper.toResponse(currency);

            ExchangeRate latestRate = ratesMap.get(currency.getId());
            if (latestRate != null) {
                response.setRate(latestRate.getRateToBase());
                response.setRateDate(latestRate.getRateDate());
            }

            return response;
        });
    }
    @Transactional(readOnly = true)
    public CurrencyResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public CurrencyResponse create(CurrencyCreateRequest dto) {
        Currency entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        Currency saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public CurrencyResponse update(UUID id, CurrencyUpdateRequest dto) {
        Currency entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        Currency saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        Currency entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public CurrencyResponse restore(UUID id) {
        Currency entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        if (!entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        normalizeCurrency(entity);
        validateCurrency(entity);
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        Currency saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected Currency findEntity(UUID id) {
        Currency entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        validateScope(entity);
        return entity;
    }

    private void validateScope(Currency entity) {
        if (!TenantContext.currentTenant().equals(entity.getTenantId())
            || (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped())
                && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId()))) {
            throw new NotFoundException(entityName + " not found: " + entity.getId());
        }
    }
    protected boolean companyScoped() {
        return false;
    }
}
