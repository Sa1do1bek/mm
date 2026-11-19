package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.ContractSpecification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ContractSpecificationRepository extends JpaRepository<ContractSpecification, UUID>, JpaSpecificationExecutor<ContractSpecification> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContractSpecification s where s.id = :id and s.isDeleted = false")
    Optional<ContractSpecification> findByIdAndIsDeletedFalseForUpdate(@Param("id") UUID id);

    Optional<ContractSpecification> findAnyById(UUID id);
    Optional<ContractSpecification> findByIdAndIsDeletedFalse(UUID id);
    List<ContractSpecification> findByTenantIdAndContractIdAndIsDeletedFalse(String tenantId, UUID contractId);

    @Query("""
        select s from ContractSpecification s where s.tenantId=:tenantId and s.companyId=:companyId
            and s.contractId=:contractId and s.isDeleted=false
            and s.lineType=com.enterprise.mm.enums.ProcurementLineType.MATERIAL
        """)
    Page<ContractSpecification> findReceiptPositions(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("contractId") UUID contractId, Pageable pageable);

    @Query("""
        SELECT e
        FROM ContractSpecification e
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
            AND (:contractId IS NULL OR e.contractId = :contractId)
            AND (:materialIdsEmpty = true OR e.materialId IN :materialIds)
        """)
    Page<ContractSpecification> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("contractId") UUID contractId,
        @Param("materialIds") List<UUID> materialIds,
        @Param("materialIdsEmpty") boolean materialIdsEmpty,
        Pageable pageable
    );

    interface ContractMaterialSpecificationProjection {
        UUID getSpecificationId();
        UUID getContractId();
        UUID getMaterialId();
        String getMaterialCode();
        String getMaterialName();
        String getUnit();
        java.math.BigDecimal getQuantityLimit();
        java.math.BigDecimal getPrice();
        com.enterprise.mm.enums.MaterialType getMaterialTypeSnapshot();
        com.enterprise.mm.enums.SerialTrackingPolicy getSerialTrackingPolicy();
    }

    @Query("""
        SELECT
            cs.id as specificationId,
            cs.contractId as contractId,
            cs.materialId as materialId,
            m.code as materialCode,
            m.name as materialName,
            COALESCE(cs.unit, uom.code, uom.name, '') as unit,
            COALESCE(cs.quantityLimit, 0) as quantityLimit,
            COALESCE(cs.price, 0) as price,
            m.materialType as materialTypeSnapshot,
            m.serialTrackingPolicy as serialTrackingPolicy
        FROM ContractSpecification cs
        JOIN Contract c ON c.id = cs.contractId AND c.companyId = cs.companyId AND c.tenantId = cs.tenantId
        JOIN Material m ON cs.materialId = m.id AND m.companyId = cs.companyId AND m.tenantId = cs.tenantId
        LEFT JOIN m.unitOfMeasure uom
        WHERE cs.tenantId = :tenantId
          AND cs.contractId = :contractId
          AND cs.isDeleted = false
          AND cs.status = 'ACTIVE'
          AND cs.materialGroupId IS NULL
          AND cs.lineType = com.enterprise.mm.enums.ProcurementLineType.MATERIAL
          AND m.isDeleted = false
          AND m.status = 'ACTIVE'
        ORDER BY cs.createdAt ASC, cs.id ASC
        """)
    List<ContractMaterialSpecificationProjection> findActiveMaterialSpecificationsByContract(
        @Param("tenantId") String tenantId,
        @Param("contractId") UUID contractId
    );
}
