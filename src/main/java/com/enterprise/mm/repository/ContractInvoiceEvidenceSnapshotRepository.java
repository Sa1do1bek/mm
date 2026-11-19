package com.enterprise.mm.repository;

import com.enterprise.mm.entity.ContractInvoiceEvidenceSnapshot;
import com.enterprise.mm.enums.ProcurementSourceDocumentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractInvoiceEvidenceSnapshotRepository
    extends JpaRepository<ContractInvoiceEvidenceSnapshot, UUID> {

    Optional<ContractInvoiceEvidenceSnapshot> findByTenantIdAndCompanyIdAndContractInvoiceIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        UUID contractInvoiceId
    );

    boolean existsByTenantIdAndCompanyIdAndSourceDocumentTypeAndSourceDocumentIdAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        ProcurementSourceDocumentType sourceDocumentType,
        UUID sourceDocumentId
    );
}
