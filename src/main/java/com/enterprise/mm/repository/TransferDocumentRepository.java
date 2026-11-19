package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransferDocumentRepository extends JpaRepository<TransferDocument, UUID>, JpaSpecificationExecutor<TransferDocument> {
    Optional<TransferDocument> findByIdAndIsDeletedFalse(UUID id);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from TransferDocument t where t.id = :id and t.tenantId = :tenantId and t.companyId = :companyId and t.isDeleted = false")
    Optional<TransferDocument> findScopedForUpdate(UUID id, String tenantId, UUID companyId);
}
