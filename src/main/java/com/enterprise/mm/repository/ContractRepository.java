package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Contract;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;

public interface ContractRepository extends JpaRepository<Contract, UUID>, JpaSpecificationExecutor<Contract> {

    @Query("""
        select new com.enterprise.mm.payload.ContractReferenceResponse(
            c.id, c.code, c.name, c.contractNumber, c.supplierId, s.name, c.status)
        from Contract c left join c.supplier s
        where c.tenantId = :tenantId and c.companyId = :companyId and c.isDeleted = false
          and (:supplierId is null or c.supplierId = :supplierId)
          and (:purchaseOrderId is null or exists (
            select po.id from PurchaseOrder po where po.id = :purchaseOrderId
              and po.tenantId = :tenantId and po.companyId = :companyId
              and po.isDeleted = false and po.contractId = c.id))
          and (:search = '' or lower(coalesce(c.code,'')) like lower(concat('%',:search,'%'))
            or lower(coalesce(c.name,'')) like lower(concat('%',:search,'%'))
            or lower(coalesce(c.contractNumber,'')) like lower(concat('%',:search,'%'))
            or lower(coalesce(s.name,'')) like lower(concat('%',:search,'%')))
        """)
    org.springframework.data.domain.Page<com.enterprise.mm.payload.ContractReferenceResponse> referenceOptions(
        @Param("tenantId") String tenantId, @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId, @Param("purchaseOrderId") UUID purchaseOrderId,
        @Param("search") String search, Pageable pageable);
    Optional<Contract> findAnyById(UUID id);
    Optional<Contract> findByIdAndIsDeletedFalse(UUID id);
    List<Contract> findByTenantIdAndCompanyIdAndIdInAndIsDeletedFalse(String tenantId, UUID companyId, List<UUID> ids);
    Optional<Contract> findByTenantIdAndCodeAndIsDeletedFalse(String tenantId, String code);

    Optional<Contract> findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(String tenantId, UUID companyId, String code);

    Optional<Contract> findByTenantIdAndCompanyIdAndContractNumberAndIsDeletedFalse(String tenantId, UUID companyId, String contractNumber);

    @Query("SELECT c FROM Contract c WHERE c.tenantId=:tenantId AND c.companyId=:companyId AND c.isDeleted=false "
        + "AND LOWER(TRIM(c.contractNumber))=LOWER(TRIM(:number))")
    List<Contract> findFakturaContractCandidates(@Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId, @Param("number") String number);

    Optional<Contract> findByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID sourceRfqId);

    Optional<Contract> findByTenantIdAndCompanyIdAndSourceQuotationIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID sourceQuotationId);

    boolean existsByTenantIdAndCompanyIdAndSourceRfqIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID sourceRfqId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Contract e where e.id = :id and e.isDeleted = false")
    Optional<Contract> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT e
        FROM Contract e
        WHERE e.tenantId = :tenantId
          AND e.companyId = :companyId
          AND e.isDeleted = false
          AND e.supplierId = :supplierId
          AND e.status = :status
        ORDER BY e.createdAt DESC
        """)
    List<Contract> findByTenantIdAndCompanyIdAndSupplierIdAndStatusAndIsDeletedFalseOrderByCreatedAtDesc(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("supplierId") UUID supplierId,
        @Param("status") String status
    );

    @Query("""
        SELECT e
        FROM Contract e
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
            AND (:supplierId IS NULL OR e.supplierId = :supplierId)
        """)
    Page<Contract> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("supplierId") UUID supplierId,
        Pageable pageable
    );

    @Query(
        value = """
            SELECT DISTINCT c
            FROM Contract c
            LEFT JOIN c.supplier s
            WHERE c.tenantId = :tenantId
              AND (:companyId IS NULL OR c.companyId = :companyId)
              AND c.isDeleted = false
              AND (:status IS NULL OR c.status = :status)
              AND (:activeOnly = false OR (c.status = 'ACTIVE' AND (c.startDate IS NULL OR c.startDate <= :today) AND (c.endDate IS NULL OR c.endDate >= :today)))
              AND (
                  :search = '' OR
                  LOWER(COALESCE(c.contractNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(c.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.legalName, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.shortName, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.name, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  c.supplierId IN :taxIdSupplierIds OR
                  EXISTS (
                      SELECT 1 FROM ContractSpecification cs
                      JOIN Material m ON cs.materialId = m.id
                      WHERE cs.contractId = c.id
                        AND cs.tenantId = c.tenantId
                        AND cs.isDeleted = false
                        AND cs.lineType = com.enterprise.mm.enums.ProcurementLineType.MATERIAL
                        AND m.isDeleted = false
                        AND (
                            LOWER(COALESCE(m.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                            LOWER(COALESCE(m.name, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                        )
                  )
              )
              AND (:exactCode = '' OR LOWER(COALESCE(c.code, '')) = LOWER(:exactCode))
              AND (:exactContractNumber = '' OR LOWER(COALESCE(c.contractNumber, '')) = LOWER(:exactContractNumber))
              AND (:supplierId IS NULL OR c.supplierId = :supplierId)
            ORDER BY c.createdAt DESC
            """,
        countQuery = """
            SELECT COUNT(DISTINCT c)
            FROM Contract c
            LEFT JOIN c.supplier s
            WHERE c.tenantId = :tenantId
              AND (:companyId IS NULL OR c.companyId = :companyId)
              AND c.isDeleted = false
              AND (:status IS NULL OR c.status = :status)
              AND (:activeOnly = false OR (c.status = 'ACTIVE' AND (c.startDate IS NULL OR c.startDate <= :today) AND (c.endDate IS NULL OR c.endDate >= :today)))
              AND (
                  :search = '' OR
                  LOWER(COALESCE(c.contractNumber, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(c.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(c.name, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.legalName, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.shortName, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.name, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  LOWER(COALESCE(s.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                  c.supplierId IN :taxIdSupplierIds OR
                  EXISTS (
                      SELECT 1 FROM ContractSpecification cs
                      JOIN Material m ON cs.materialId = m.id
                      WHERE cs.contractId = c.id
                        AND cs.tenantId = c.tenantId
                        AND cs.isDeleted = false
                        AND cs.lineType = com.enterprise.mm.enums.ProcurementLineType.MATERIAL
                        AND m.isDeleted = false
                        AND (
                            LOWER(COALESCE(m.code, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                            LOWER(COALESCE(m.name, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                        )
                  )
              )
              AND (:exactCode = '' OR LOWER(COALESCE(c.code, '')) = LOWER(:exactCode))
              AND (:exactContractNumber = '' OR LOWER(COALESCE(c.contractNumber, '')) = LOWER(:exactContractNumber))
              AND (:supplierId IS NULL OR c.supplierId = :supplierId)
            """
    )
    Page<Contract> searchContractsForManualIssue(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("search") String search,
        @Param("exactCode") String exactCode,
        @Param("exactContractNumber") String exactContractNumber,
        @Param("supplierId") UUID supplierId,
        @Param("taxIdSupplierIds") List<UUID> taxIdSupplierIds,
        @Param("status") String status,
        @Param("activeOnly") boolean activeOnly,
        @Param("today") LocalDate today,
        Pageable pageable
    );
}
