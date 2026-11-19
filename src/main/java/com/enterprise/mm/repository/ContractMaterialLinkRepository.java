package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ContractMaterialLink;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Read-only access: inserts are confined to the verified migration transaction. */
public interface ContractMaterialLinkRepository extends Repository<ContractMaterialLink, UUID> {
    List<ContractMaterialLink> findByTenantIdAndCompanyIdAndSpecificationIdOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID specificationId);
    Optional<ContractMaterialLink> findByTenantIdAndCompanyIdAndSpecificationIdAndMaterialId(String tenantId, UUID companyId, UUID specificationId, UUID materialId);
}
