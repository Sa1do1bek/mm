package com.enterprise.mm.service;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Currency;
import com.enterprise.mm.entity.ExchangeRate;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ExchangeRateMapper;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.ExchangeRateResponse;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExchangeRateService {
    private static final int MAX_PAGE_SIZE = 200;

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateMapper exchangeRateMapper;
    private final AuditService auditService;

    @Transactional
    public ExchangeRateResponse upsert(ExchangeRateRequest request) {
        validateRequest(request);
        Currency currency = findCurrency(request.getCurrencyId());
        String source = normalizeSource(request.getSource());
        ExchangeRate entity = exchangeRateRepository
            .findByTenantIdAndCompanyIdAndCurrency_IdAndRateDateAndSourceAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                currency.getId(),
                request.getRateDate(),
                source
            )
            .orElseGet(ExchangeRate::new);

        boolean created = entity.getId() == null;
        exchangeRateMapper.updateEntity(entity, request, currency, source);
        applyBusinessDefaults(entity, currency);
        ExchangeRate saved = exchangeRateRepository.save(entity);
        auditService.log("ExchangeRate", saved.getId(), created ? "CREATE" : "UPDATE", saved.getCode(), null, null);
        return exchangeRateMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public BigDecimal rateOn(UUID currencyId, LocalDate date) {
        if (currencyId == null) {
            return BigDecimal.ONE;
        }
        Currency currency = findCurrency(currencyId);
        if (currency.isBaseCurrency()) {
            return BigDecimal.ONE;
        }
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return exchangeRateRepository.findLatestOnOrBefore(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                scoped,
                currency.getId(),
                effectiveDate,
                PageRequest.of(0, 1)
            )
            .stream()
            .findFirst()
            .map(ExchangeRate::getRateToBase)
            .orElseThrow(() -> new NotFoundException("Exchange rate not found for currency " + currencyId + " on " + effectiveDate));
    }

    @Transactional(readOnly = true)
    public Page<ExchangeRateResponse> history(Pageable pageable, UUID currencyId, LocalDate dateFrom, LocalDate dateTo, String query) {
        if (pageable.getPageNumber() < 0) {
            throw new BadRequestException("Page must be greater than or equal to 0");
        }
        if (pageable.getPageSize() < 1 || pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadRequestException("Size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String normalizedQuery = normalizeQuery(query);
        Pageable effectivePageable = PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Order.desc("rateDate"), Sort.Order.desc("createdAt"))
        );
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return exchangeRateRepository.history(
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                scoped,
                currencyId,
                dateFrom,
                dateTo,
                normalizedQuery,
                normalizedQuery == null,
                effectivePageable
            )
            .map(exchangeRateMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ExchangeRateResponse getById(UUID id) {
        return exchangeRateMapper.toResponse(findExchangeRate(id));
    }

    private ExchangeRate findExchangeRate(UUID id) {
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return exchangeRateRepository.findActiveById(
                id,
                TenantContext.currentTenant(),
                scoped ? CompanyContext.currentCompany() : null,
                scoped
            )
            .orElseThrow(() -> new NotFoundException("Exchange rate not found: " + id));
    }

    private Currency findCurrency(UUID currencyId) {
        if (currencyId == null) {
            throw new BadRequestException("Currency is required");
        }
        return currencyRepository.findByIdAndIsDeletedFalse(currencyId)
            .filter(currency -> TenantContext.currentTenant().equals(currency.getTenantId()))
            .filter(currency -> !currency.isDeleted())
            .filter(currency -> !com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) || com.enterprise.mm.security.AccessScope.canAccessCompany(currency.getCompanyId()))
            .orElseThrow(() -> new NotFoundException("Currency not found: " + currencyId));
    }

    private void validateRequest(ExchangeRateRequest request) {
        if (request == null) {
            throw new BadRequestException("Exchange rate request is required");
        }
        if (request.getRateDate() == null) {
            throw new BadRequestException("Exchange rate date is required");
        }
        if (request.getRateToBase() == null || request.getRateToBase().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Exchange rate must be greater than zero");
        }
    }

    private void applyBusinessDefaults(ExchangeRate entity, Currency currency) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        entity.setCode("EXRATE-" + currency.getIsoCode() + "-" + entity.getRateDate() + "-" + entity.getSource());
        entity.setName("Exchange rate from " + entity.getSource());
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw new BadRequestException("Exchange rate source is required");
        }
        return source.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeQuery(String query) {
        return query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private boolean companyScoped() {
        return true;
    }
}
