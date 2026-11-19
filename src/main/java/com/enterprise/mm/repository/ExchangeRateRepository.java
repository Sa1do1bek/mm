package com.enterprise.mm.repository;
import com.enterprise.mm.entity.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {
    Optional<ExchangeRate> findAnyById(UUID id);
    Optional<ExchangeRate> findByIdAndIsDeletedFalse(UUID id);
    Optional<ExchangeRate> findByTenantIdAndCompanyIdAndCurrency_IdAndRateDateAndSourceAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID currencyId,
        LocalDate rateDate,
        String source
    );

    @Query("""
        select e from ExchangeRate e
        join fetch e.currency c
        where e.tenantId = :tenantId
          and (:companyScoped = false or e.companyId = :companyId)
          and e.isDeleted = false
          and c.id = :currencyId
          and c.isDeleted = false
          and e.rateDate <= :rateDate
        order by e.rateDate desc, e.createdAt desc
        """)
    java.util.List<ExchangeRate> findLatestOnOrBefore(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("companyScoped") boolean companyScoped,
        @Param("currencyId") UUID currencyId,
        @Param("rateDate") LocalDate rateDate,
        Pageable pageable
    );

    @Query(
        value = """
            select e from ExchangeRate e
            join fetch e.currency c
            where e.tenantId = :tenantId
              and (:companyScoped = false or e.companyId = :companyId)
              and e.isDeleted = false
              and c.isDeleted = false
              and (:currencyId is null or c.id = :currencyId)
              and (cast(:dateFrom as date) is null or e.rateDate >= :dateFrom)
              and (cast(:dateTo as date) is null or e.rateDate <= :dateTo)
              and (
                  :queryEmpty = true
                  or lower(e.source) like :query
                  or lower(c.code) like :query
                  or lower(c.isoCode) like :query
                  or lower(c.name) like :query
              )
            """,
        countQuery = """
            select count(e) from ExchangeRate e
            join e.currency c
            where e.tenantId = :tenantId
              and (:companyScoped = false or e.companyId = :companyId)
              and e.isDeleted = false
              and c.isDeleted = false
              and (:currencyId is null or c.id = :currencyId)
              and (cast(:dateFrom as date) is null or e.rateDate >= :dateFrom)
              and (cast(:dateTo as date) is null or e.rateDate <= :dateTo)
              and (
                  :queryEmpty = true
                  or lower(e.source) like :query
                  or lower(c.code) like :query
                  or lower(c.isoCode) like :query
                  or lower(c.name) like :query
              )
            """
    )
    Page<ExchangeRate> history(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("companyScoped") boolean companyScoped,
        @Param("currencyId") UUID currencyId,
        @Param("dateFrom") LocalDate dateFrom,
        @Param("dateTo") LocalDate dateTo,
        @Param("query") String query,
        @Param("queryEmpty") boolean queryEmpty,
        Pageable pageable
    );

    @Query("""
        select e from ExchangeRate e
        join fetch e.currency c
        where e.id = :id
          and e.tenantId = :tenantId
          and (:companyScoped = false or e.companyId = :companyId)
          and e.isDeleted = false
          and c.isDeleted = false
        """)
    Optional<ExchangeRate> findActiveById(
        @Param("id") UUID id,
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("companyScoped") boolean companyScoped
    );
    @Query("SELECT e FROM ExchangeRate e " +
            "WHERE e.currency.id IN :currencyIds " +
            "AND e.rateDate = (SELECT MAX(e2.rateDate) FROM ExchangeRate e2 WHERE e2.currency.id = e.currency.id)")
    List<ExchangeRate> findLatestRatesByCurrencyIds(@Param("currencyIds") List<UUID> currencyIds);
}

