package com.enterprise.mm.repository;

import com.enterprise.mm.entity.Attachment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
    Optional<Attachment> findAnyById(UUID id);
    Optional<Attachment> findByIdAndIsDeletedFalse(UUID id);
    List<Attachment> findByTenantIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        String entityType,
        UUID entityId
    );

    List<Attachment> findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String entityType,
        UUID entityId
    );

    List<Attachment> findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdInAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String entityType,
        List<UUID> entityIds
    );

    Optional<Attachment> findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(String tenantId, UUID companyId, UUID id);

    Optional<Attachment> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<Attachment> findFirstByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndChecksumSha256AndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId,
        UUID companyId,
        String entityType,
        UUID entityId,
        String attachmentType,
        String checksumSha256
    );

    boolean existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String entityType,
        UUID entityId,
        String attachmentType
    );
}
