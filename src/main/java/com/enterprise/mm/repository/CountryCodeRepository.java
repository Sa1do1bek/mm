package com.enterprise.mm.repository;

import com.enterprise.mm.entity.CountryCode;
import com.enterprise.mm.payload.CountryCodeProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CountryCodeRepository extends JpaRepository<CountryCode, Long> {
    Optional<CountryCode> findAnyById(Long id);
    Optional<CountryCode> findByCode(String code);

    Optional<CountryCode> findByCodeIgnoreCase(String code);
    @Query("""
            select c from CountryCode c
            where (:query is null
                or coalesce(c.longName, '') ilike concat('%', :query, '%')
                or coalesce(c.shortName, '') ilike concat('%', :query, '%')
                or coalesce(c.alpha2, '') ilike concat('%', :query, '%')
                or coalesce(c.alpha3, '') ilike concat('%', :query, '%')
             )
            order by lower(coalesce(c.shortName, ''))
            """)
    List<CountryCode> findAllByFilter(String query);

    @Query(
        value = """
            SELECT
                c.id            AS id,
                c.code          AS code,
                c.short_name    AS shortName,
                c.long_name     AS longName,
                c.alpha2        AS alpha2,
                c.alpha3        AS alpha3
            FROM country_code c
            WHERE (:search IS NULL
                OR LOWER(c.code) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.short_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.long_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha2) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha3) LIKE LOWER(CONCAT('%', :search, '%'))
            )
            ORDER BY LOWER(COALESCE(c.short_name, ''))
        """,
        countQuery = """
            SELECT COUNT(*)
            FROM country_code c
            WHERE (:search IS NULL
                OR LOWER(c.code) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.short_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.long_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha2) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha3) LIKE LOWER(CONCAT('%', :search, '%'))
            )
        """,
        nativeQuery = true
    )
    Page<CountryCodeProjection> getAll(
            @Param("search") String search,
            Pageable pageable
    );

    @Query(
        value = """
            SELECT
                c.id            AS id,
                c.code          AS code,
                c.short_name    AS shortName,
                c.long_name     AS longName,
                c.alpha2        AS alpha2,
                c.alpha3        AS alpha3
            FROM country_code c
            WHERE (:search IS NULL
                OR LOWER(c.code) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.short_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.long_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha2) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha3) LIKE LOWER(CONCAT('%', :search, '%'))
            )
            AND c.deleted = false
            ORDER BY LOWER(COALESCE(c.short_name, ''))
        """,
        countQuery = """
            SELECT COUNT(*)
            FROM country_code c
            WHERE (:search IS NULL
                OR LOWER(c.code) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.short_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.long_name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha2) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.alpha3) LIKE LOWER(CONCAT('%', :search, '%'))
            )
            AND c.deleted = false
        """,
        nativeQuery = true
    )
    Page<CountryCodeProjection> getAllAndDeletedFalse(
            @Param("search") String search,
            Pageable pageable
    );
}
