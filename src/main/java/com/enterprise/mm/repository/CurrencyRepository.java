package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CurrencyRepository extends JpaRepository<Currency, UUID> {
    Optional<Currency> findAnyById(UUID id);
    Optional<Currency> findByIdAndIsDeletedFalse(UUID id);
    Optional<Currency> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    Optional<Currency> findByTenantIdAndIsoCodeAndIsDeletedFalse(String tenantId, String isoCode);
    Optional<Currency> findByTenantIdAndIsoCodeIgnoreCaseAndIsDeletedFalse(String tenantId, String isoCode);
    boolean existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalse(String tenantId);
    boolean existsByTenantIdAndBaseCurrencyTrueAndIsDeletedFalseAndIdNot(String tenantId, UUID id);

    @Query("""
        SELECT e
        FROM Currency e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
        """)
    Page<Currency> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
