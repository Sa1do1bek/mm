package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.AttachmentMapper;
import com.enterprise.mm.service.attachment.AttachmentStorage.StoredFile;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.config.AttachmentProperties;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.security.AccessScope;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private final AttachmentRepository repository;
    private final AttachmentMapper mapper;
    private final AttachmentProperties properties;
    private final List<AttachmentStorage> storages;
    private final AuditService auditService;
    private final AttachmentEntityAccessPolicy entityAccessPolicy;
    private final IdempotencyService idempotencyService;

    @Transactional
    public AttachmentDto uploadDraftSource(UUID draftId, MultipartFile file) {
        validateUpload("INVENTORY_AI_DRAFT", draftId, file);
        entityAccessPolicy.requireRead("INVENTORY_AI_DRAFT", draftId);
        return mapper.toDto(storeUploadEntity("INVENTORY_AI_DRAFT", draftId, "SOURCE_DOCUMENT",
            safeFileName(file.getOriginalFilename()), safeContentType(file.getContentType()), file));
    }

    @Transactional
    public AttachmentDto uploadResourceVersion(UUID versionId, MultipartFile file) {
        validateUpload("RESOURCE_VERSION", versionId, file);
        Attachment saved = storeUploadEntity("RESOURCE_VERSION", versionId, "ORIGINAL",
            safeFileName(file.getOriginalFilename()), safeContentType(file.getContentType()), file);
        repository.flush();
        return mapper.toDto(saved);
    }

    @Transactional
    public void removeDraftSource(UUID draftId, UUID fileId) {
        entityAccessPolicy.requireRead("INVENTORY_AI_DRAFT", draftId);
        Attachment file = repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), fileId)
            .filter(item -> "INVENTORY_AI_DRAFT".equals(item.getEntityType()) && draftId.equals(item.getEntityId()))
            .orElseThrow(() -> new NotFoundException("Inventory draft file not found"));
        file.softDelete();
        repository.save(file);
        auditService.log("Attachment", file.getId(), "SOFT_DELETE", file.getOriginalFileName(), null, null);
        deleteStoredFileAfterCommit(file);
    }

    @Transactional(readOnly = true)
    public byte[] readDraftBytes(UUID draftId, UUID fileId) {
        Attachment attachment = repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), fileId)
            .filter(file -> "INVENTORY_AI_DRAFT".equals(file.getEntityType()) && draftId.equals(file.getEntityId()))
            .orElseThrow(() -> new NotFoundException("Inventory draft file not found"));
        try (InputStream stream = storage(attachment.getStorageProvider()).read(attachment.getStorageKey()).getInputStream()) {
            return stream.readAllBytes();
        } catch (java.io.IOException ex) {
            throw new BusinessException("Cannot read inventory draft file");
        }
    }

    /** Internal indexing reads use the persisted event scope and require a matching original attachment. */
    @Transactional(readOnly = true)
    public byte[] readResourceBytes(String tenant, UUID company, UUID versionId) {
        Attachment attachment = repository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            tenant, company, "RESOURCE_VERSION", versionId).stream()
            .filter(file -> "ORIGINAL".equals(file.getAttachmentType())).findFirst()
            .orElseThrow(() -> new NotFoundException("Resource original not found"));
        try (InputStream stream = storage(attachment.getStorageProvider()).read(attachment.getStorageKey()).getInputStream()) {
            byte[] content = stream.readNBytes(20 * 1024 * 1024 + 1);
            if (content.length > 20 * 1024 * 1024) throw new BadRequestException("Resource file exceeds AI size limit");
            return content;
        } catch (java.io.IOException ex) {
            throw new BusinessException("Cannot read resource original");
        }
    }

    @Transactional
    public void bindDraftFiles(UUID draftId, UUID inventoryId) {
        bindDraftFiles(draftId, inventoryId, "INVENTORY_DOCUMENT");
    }

    @Transactional
    public void bindDraftFiles(UUID draftId, UUID entityId, String entityType) {
        if (!List.of("INVENTORY_DOCUMENT", "INVENTORY_ORDER").contains(entityType))
            throw new BadRequestException("Unsupported inventory source destination");
        List<Attachment> files = repository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "INVENTORY_AI_DRAFT", draftId);
        for (Attachment file : files) {
            file.setEntityType(entityType);
            file.setEntityId(entityId);
            file.setAttachmentType("SOURCE_DOCUMENT");
            repository.save(file);
            auditService.log("Attachment", file.getId(), "BIND_INVENTORY_SOURCE", entityId.toString(), null, null);
        }
    }

    @Transactional
    public void purgeDraftFiles(UUID draftId) {
        List<Attachment> files = repository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "INVENTORY_AI_DRAFT", draftId);
        for (Attachment file : files) {
            file.softDelete();
            repository.save(file);
            deleteStoredFileAfterCommit(file);
        }
    }

    private void deleteStoredFileAfterCommit(Attachment file) {
        String provider = file.getStorageProvider();
        String key = file.getStorageKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { storage(provider).delete(key); }
            });
        } else {
            storage(provider).delete(key);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentDto> list(String entityType, UUID entityId) {
        String normalizedType = normalizeEntityType(entityType);
        entityAccessPolicy.requireRead(normalizedType, entityId);
        List<Attachment> attachments = AccessScope.companyScoped(true)
            ? repository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                normalizedType,
                entityId
            )
            : repository.findByTenantIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                normalizedType,
                entityId
            );
        return attachments.stream()
            .map(mapper::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasAttachment(String entityType, UUID entityId, String attachmentType, UUID companyId) {
        if (entityId == null || companyId == null) {
            return false;
        }
        return repository.existsByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndIsDeletedFalse(
            TenantContext.currentTenant(),
            companyId,
            normalizeEntityType(entityType),
            entityId,
            normalizeAttachmentType(attachmentType)
        );
    }

    @Transactional
    public AttachmentDto upload(String entityType, UUID entityId, String attachmentType, MultipartFile file) {
        return upload(entityType, entityId, attachmentType, file, null);
    }
    @Transactional
    public AttachmentDto uploadProfilePhoto(UUID userId, MultipartFile file) {
        validateUpload("USER_PROFILE", userId, file);
        entityAccessPolicy.requireRead("USER_PROFILE", userId);
        return storeUpload("USER_PROFILE", userId, "PROFILE_PHOTO",
            safeFileName(file.getOriginalFilename()), safeContentType(file.getContentType()), file);
    }
    @Transactional
    public AttachmentDto upload(
        String entityType,
        UUID entityId,
        String attachmentType,
        MultipartFile file,
        String idempotencyKey
    ) {
        validateUpload(entityType, entityId, file);
        String normalizedType = normalizeEntityType(entityType);
        String normalizedAttachmentType = normalizeAttachmentType(attachmentType);
        entityAccessPolicy.requireUpload(normalizedType, entityId, normalizedAttachmentType);
        String originalName = safeFileName(file.getOriginalFilename());
        String contentType = safeContentType(file.getContentType());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return storeUpload(normalizedType, entityId, normalizedAttachmentType, originalName, contentType, file);
        }
        AttachmentUploadRequest request = new AttachmentUploadRequest(
            normalizedType,
            entityId,
            normalizedAttachmentType,
            originalName,
            contentType,
            file.getSize(),
            sha256(file)
        );
        return idempotencyService.execute(
            "ATTACHMENT_UPLOAD_" + normalizedType,
            entityId,
            idempotencyKey,
            request,
            AttachmentDto.class,
            () -> storeUpload(normalizedType, entityId, normalizedAttachmentType, originalName, contentType, file)
        );
    }
    private AttachmentDto storeUpload(
        String normalizedType,
        UUID entityId,
        String normalizedAttachmentType,
        String originalName,
        String contentType,
        MultipartFile file
    ) {
        return mapper.toDto(storeUploadEntity(
            normalizedType,
            entityId,
            normalizedAttachmentType,
            originalName,
            contentType,
            file
        ));
    }

    @Transactional
    public Attachment uploadForCommand(
        String entityType,
        UUID entityId,
        String attachmentType,
        MultipartFile file
    ) {
        validateUpload(entityType, entityId, file);
        String normalizedType = normalizeEntityType(entityType);
        String normalizedAttachmentType = normalizeAttachmentType(attachmentType);
        entityAccessPolicy.requireUpload(normalizedType, entityId, normalizedAttachmentType);
        return storeUploadEntity(
            normalizedType,
            entityId,
            normalizedAttachmentType,
            safeFileName(file.getOriginalFilename()),
            safeContentType(file.getContentType()),
            file
        );
    }

    private Attachment storeUploadEntity(
        String normalizedType,
        UUID entityId,
        String normalizedAttachmentType,
        String originalName,
        String contentType,
        MultipartFile file
    ) {
        UUID storageId = UUID.randomUUID();
        String storageKey = storageKey(normalizedType, entityId, storageId, originalName);
        String storageProvider = storageProvider();
        StoredFile stored = storage(storageProvider).store(file, storageKey, contentType);
        registerRollbackCleanup(storageProvider, storageKey);
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + storageId.toString().substring(0, 8));
        attachment.setName(originalName);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType(normalizedType);
        attachment.setEntityId(entityId);
        attachment.setAttachmentType(normalizedAttachmentType);
        attachment.setOriginalFileName(originalName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setChecksumSha256(stored.checksumSha256());
        attachment.setStorageProvider(storageProvider);
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setPreviewable(contentType.startsWith("image/") || "application/pdf".equals(contentType));
        attachment.setDownloadable(true);
        Attachment saved = repository.save(attachment);
        auditService.log("Attachment", saved.getId(), "UPLOAD", normalizedType + ":" + entityId, null, null);
        return saved;
    }
    @Transactional
    public AttachmentDto saveBytes(String entityType, UUID entityId, String attachmentType, String fileName, String contentType, byte[] content) {
        validateBytes(entityType, entityId, content);
        String normalizedType = normalizeEntityType(entityType);
        String normalizedAttachmentType = normalizeAttachmentType(attachmentType);
        entityAccessPolicy.requireUpload(normalizedType, entityId, normalizedAttachmentType);
        String originalName = safeFileName(fileName);
        String safeContentType = safeContentType(contentType);
        String checksum = sha256(content);
        var existing = repository.findFirstByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndAttachmentTypeAndChecksumSha256AndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            normalizedType,
            entityId,
            normalizedAttachmentType,
            checksum
        );
        if (existing.isPresent()) {
            return mapper.toDto(existing.get());
        }
        UUID storageId = UUID.randomUUID();
        String storageKey = storageKey(normalizedType, entityId, storageId, originalName);
        String storageProvider = storageProvider();
        StoredFile stored = storage(storageProvider).store(content, storageKey, safeContentType);
        Attachment attachment = new Attachment();
        attachment.setCode("ATT-" + storageId.toString().substring(0, 8));
        attachment.setName(originalName);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType(normalizedType);
        attachment.setEntityId(entityId);
        attachment.setAttachmentType(normalizedAttachmentType);
        attachment.setOriginalFileName(originalName);
        attachment.setContentType(safeContentType);
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setChecksumSha256(stored.checksumSha256());
        attachment.setStorageProvider(storageProvider);
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setPreviewable(safeContentType.startsWith("image/") || "application/pdf".equals(safeContentType));
        attachment.setDownloadable(true);
        Attachment saved = repository.save(attachment);
        auditService.log("Attachment", saved.getId(), "ARCHIVE", normalizedType + ":" + entityId, null, null);
        return mapper.toDto(saved);
    }
    @Transactional(readOnly = true)
    public AttachmentDownload download(UUID id) {
        Attachment attachment = findAttachment(id);
        entityAccessPolicy.requireRead(attachment.getEntityType(), attachment.getEntityId());
        if (!attachment.isDownloadable()) {
            throw new BusinessException("Attachment is not downloadable");
        }
        auditService.log("Attachment", attachment.getId(), "DOWNLOAD", attachment.getOriginalFileName(), null, null);
        return new AttachmentDownload(
            mapper.toDto(attachment),
            storage(attachment.getStorageProvider()).read(attachment.getStorageKey())
        );
    }
    @Transactional
    public void softDelete(UUID id) {
        Attachment attachment = findAttachment(id);
        softDelete(attachment);
    }

    @Transactional
    public void softDeleteForCommand(String entityType, UUID entityId, UUID attachmentId) {
        String normalizedType = normalizeEntityType(entityType);
        Attachment attachment = findAttachment(attachmentId);
        if (!normalizedType.equals(attachment.getEntityType())
            || !entityId.equals(attachment.getEntityId())) {
            throw new NotFoundException("Attachment not found: " + attachmentId);
        }
        softDelete(attachment);
    }

    private void softDelete(Attachment attachment) {
        entityAccessPolicy.requireDelete(attachment.getEntityType(), attachment.getEntityId(), attachment.getCreatedBy());
        attachment.softDelete();
        repository.save(attachment);
        auditService.log("Attachment", attachment.getId(), "SOFT_DELETE", attachment.getOriginalFileName(), null, null);
        if ("USER_PROFILE".equals(attachment.getEntityType())) {
            deleteStoredFileAfterCommit(attachment);
        }
    }
    private Attachment findAttachment(UUID id) {
        return (AccessScope.companyScoped(true)
            ? repository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                id
            )
            : repository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), id))
            .orElseThrow(() -> new NotFoundException("Attachment not found: " + id));
    }
    private void validateUpload(String entityType, UUID entityId, MultipartFile file) {
        normalizeEntityType(entityType);
        if (entityId == null) {
            throw new BadRequestException("entityId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required");
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new BadRequestException("File size exceeds limit");
        }
        String contentType = safeContentType(file.getContentType());
        if (!properties.getAllowedContentTypes().contains(contentType)) {
            throw new BadRequestException("Unsupported file type: " + contentType);
        }
    }
    private void validateBytes(String entityType, UUID entityId, byte[] content) {
        normalizeEntityType(entityType);
        if (entityId == null) {
            throw new BadRequestException("entityId is required");
        }
        if (content == null || content.length == 0) {
            throw new BadRequestException("content is required");
        }
        if (content.length > properties.getMaxSizeBytes()) {
            throw new BadRequestException("File size exceeds limit");
        }
    }
    private String storageKey(String entityType, UUID entityId, UUID attachmentId, String originalName) {
        return TenantContext.currentTenant()
            + "/" + CompanyContext.currentCompany()
            + "/" + entityType
            + "/" + entityId
            + "/" + attachmentId
            + "-" + originalName;
    }
    private String normalizeEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new BadRequestException("entityType is required");
        }
        String normalized = entityType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,79}")) {
            throw new BadRequestException("Invalid entityType");
        }
        return normalized;
    }
    private String normalizeAttachmentType(String attachmentType) {
        if (attachmentType == null || attachmentType.isBlank()) {
            return "DOCUMENT";
        }
        String normalized = attachmentType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,79}")) {
            throw new BadRequestException("Invalid attachmentType");
        }
        return normalized;
    }
    private String safeFileName(String fileName) {
        String raw = fileName == null || fileName.isBlank() ? "attachment.bin" : Path.of(fileName).getFileName().toString();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) {
            return "attachment.bin";
        }
        return safe.length() > 180 ? safe.substring(safe.length() - 180) : safe;
    }
    private String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
    }
    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new BusinessException("Cannot calculate attachment checksum");
        }
    }
    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new BusinessException("Cannot calculate attachment checksum");
        }
    }
    private String storageProvider() {
        String provider = properties.getStorageProvider() == null || properties.getStorageProvider().isBlank() ? "LOCAL" : properties.getStorageProvider();
        return provider.toUpperCase(Locale.ROOT);
    }
    private AttachmentStorage storage(String provider) {
        return storages.stream()
            .filter(storage -> storage.supports(provider))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Unsupported attachment storage provider: " + provider));
    }

    private void registerRollbackCleanup(String storageProvider, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storage(storageProvider).delete(storageKey);
                }
            }
        });
    }
    public record AttachmentDownload(AttachmentDto metadata, InputStreamResource resource) {
    }
    private record AttachmentUploadRequest(
        String entityType,
        UUID entityId,
        String attachmentType,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String checksumSha256
    ) {
    }
}
