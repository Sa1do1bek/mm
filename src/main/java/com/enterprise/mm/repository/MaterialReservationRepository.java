package com.enterprise.mm.repository;
import java.math.BigDecimal;

import java.util.Optional;
import com.enterprise.mm.entity.MaterialReservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface MaterialReservationRepository extends JpaRepository<MaterialReservation, UUID> {
    interface DueScopeProjection {
        String getTenantId();
        UUID getCompanyId();
    }

    Optional<MaterialReservation> findAnyById(UUID id);
    Optional<MaterialReservation> findByIdAndIsDeletedFalse(UUID id);
    List<MaterialReservation> findByTenantIdAndDemandPlan_IdAndIsDeletedFalse(String tenantId, UUID demandPlanId);

    List<MaterialReservation> findByTenantIdAndDemandPlanItem_IdAndIsDeletedFalse(String tenantId, UUID demandPlanItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select reservation
        from MaterialReservation reservation
        where reservation.id = :id
          and reservation.tenantId = :tenantId
          and reservation.companyId = :companyId
          and reservation.isDeleted = false
        """)
    Optional<MaterialReservation> findForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    @Query("""
        select reservation.id
        from MaterialReservation reservation
        where reservation.tenantId = :tenantId
          and reservation.companyId = :companyId
          and reservation.isDeleted = false
          and reservation.status in ('ACTIVE', 'PARTIALLY_RELEASED')
          and reservation.expiresAt is not null
          and reservation.expiresAt <= :asOf
        order by reservation.expiresAt, reservation.id
        """)
    List<UUID> findDueIds(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("asOf") LocalDateTime asOf,
        Pageable pageable
    );

    @Query("""
        select distinct reservation.tenantId as tenantId, reservation.companyId as companyId
        from MaterialReservation reservation
        where reservation.isDeleted = false
          and reservation.status in ('ACTIVE', 'PARTIALLY_RELEASED')
          and reservation.expiresAt is not null
          and reservation.expiresAt <= :asOf
        """)
    List<DueScopeProjection> findDueScopes(@Param("asOf") LocalDateTime asOf);

    @Query("""
        SELECT e
        FROM MaterialReservation e
        LEFT JOIN e.demandPlan plan
        LEFT JOIN e.demandPlanItem planItem
        LEFT JOIN planItem.demandPlan itemPlan
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
            AND (:warehouseId IS NULL OR e.warehouseId = :warehouseId)
            AND (:warehouseIdsEmpty = true OR e.warehouseId IN :warehouseIds)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
            AND (:referenceId IS NULL OR e.referenceId = :referenceId)
            AND (:departmentScoped = false OR COALESCE(plan.department.id, itemPlan.department.id) = :departmentId)
        """)
    Page<MaterialReservation> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("warehouseId") UUID warehouseId,
        @Param("warehouseIds") List<UUID> warehouseIds,
        @Param("warehouseIdsEmpty") boolean warehouseIdsEmpty,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        @Param("referenceId") UUID referenceId,
        @Param("departmentScoped") boolean departmentScoped,
        @Param("departmentId") UUID departmentId,
        Pageable pageable
    );

    interface Warehouse360ReservationProjection {
        UUID getId();
        String getCode();
        String getStatus();
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        BigDecimal getQuantity();
        BigDecimal getReservedQuantity();
        LocalDateTime getExpiresAt();
        String getReservationType();
        String getPriority();
    }

    @Query(
        value = """
            select reservation.id as id,
                   reservation.code as code,
                   reservation.status as status,
                   reservation.materialId as materialId,
                   coalesce(material.code, material.article) as materialCode,
                   coalesce(material.name, material.code, material.article) as materialName,
                   reservation.quantity as quantity,
                   reservation.reservedQuantity as reservedQuantity,
                   reservation.expiresAt as expiresAt,
                   reservation.reservationType as reservationType,
                   reservation.priority as priority
            from MaterialReservation reservation
            left join reservation.material material
            where reservation.isDeleted = false
              and reservation.tenantId = :tenantId
              and reservation.companyId = :companyId
              and reservation.warehouseId = :warehouseId
              and reservation.status in :statuses
            order by reservation.expiresAt asc, reservation.id desc
            """,
        countQuery = """
            select count(reservation)
            from MaterialReservation reservation
            where reservation.isDeleted = false
              and reservation.tenantId = :tenantId
              and reservation.companyId = :companyId
              and reservation.warehouseId = :warehouseId
              and reservation.status in :statuses
            """
    )
    Page<Warehouse360ReservationProjection> findWarehouse360Reservations(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("warehouseId") UUID warehouseId,
        @Param("statuses") List<String> statuses,
        Pageable pageable
    );

}
