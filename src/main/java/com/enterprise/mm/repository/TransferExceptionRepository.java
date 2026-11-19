package com.enterprise.mm.repository;
import com.enterprise.mm.entity.TransferException;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TransferExceptionRepository extends JpaRepository<TransferException,UUID> {
    List<TransferException> findByTenantIdAndCompanyIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID transferId);
    Optional<TransferException> findByTenantIdAndCompanyIdAndReverseTransferIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID reverseTransferId);
    List<TransferException> findByTenantIdAndCompanyIdAndAllocationIdAndIsDeletedFalseOrderByCreatedAtAscIdAsc(String tenantId, UUID companyId, UUID allocationId);
}
