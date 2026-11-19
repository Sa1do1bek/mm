package com.enterprise.mm.repository;

import com.enterprise.mm.entity.TransferDocumentLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferDocumentLineRepository extends JpaRepository<TransferDocumentLine, UUID> {
    List<TransferDocumentLine> findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID transferDocumentId
    );

    Optional<TransferDocumentLine> findByIdAndIsDeletedFalse(UUID id);
}
