package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TenderPriceCalculationQuotation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TenderPriceCalculationQuotationRepository extends JpaRepository<TenderPriceCalculationQuotation, UUID>, JpaSpecificationExecutor<TenderPriceCalculationQuotation> {
    List<TenderPriceCalculationQuotation> findByTenantIdAndCompanyIdAndCalculationIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID calculationId
    );
}
