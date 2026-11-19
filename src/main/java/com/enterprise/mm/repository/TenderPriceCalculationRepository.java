package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TenderPriceCalculation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TenderPriceCalculationRepository extends JpaRepository<TenderPriceCalculation, UUID>, JpaSpecificationExecutor<TenderPriceCalculation> {
    Optional<TenderPriceCalculation> findByIdAndIsDeletedFalse(UUID id);
    Optional<TenderPriceCalculation> findByTenantIdAndCompanyIdAndProcurementProcedureIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID procurementProcedureId
    );
    Optional<TenderPriceCalculation> findByTenantIdAndCompanyIdAndPriceDiscoveryRfqIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID priceDiscoveryRfqId
    );
}
