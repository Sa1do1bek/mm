package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.InventoryAiDraft;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.InventoryAiDraftRepository;
import com.enterprise.mm.service.inventory.InventoryAccessService;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class InventoryAiDraftService {
    public static final String ENTITY_TYPE = "INVENTORY_AI_DRAFT";
    private static final int MAX_FILES = 10;
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final List<AiAnalysisJobStatus> ACTIVE = List.of(AiAnalysisJobStatus.QUEUED, AiAnalysisJobStatus.RUNNING);
    private final InventoryAiDraftRepository drafts;
    private final AttachmentRepository attachments;
    private final AiAnalysisJobRepository jobs;
    private final AttachmentService attachmentService;
    private final InventoryAccessService inventoryAccess;
    private final InventoryOrderAnalysisScopeService orderScopes;

    public record DraftResponse(UUID id, LocalDateTime expiresAt) {}
    public record FileSnapshot(UUID fileId, String fileName, String contentType, String sha256) {}
    public record AnalysisRequest(UUID draftId, List<FileSnapshot> documents, int commissionAnalysisVersion, int orderAnalysisVersion,
        InventoryOrderAnalysisScopeService.Scope scope) {
        public AnalysisRequest(UUID draftId, List<FileSnapshot> documents, int commissionAnalysisVersion, int orderAnalysisVersion) {
            this(draftId, documents, commissionAnalysisVersion, orderAnalysisVersion, null);
        }
        public AnalysisRequest(UUID draftId, List<FileSnapshot> documents) {
            this(draftId, documents, 0, 0, null);
        }
        public AnalysisRequest(UUID draftId, List<FileSnapshot> documents, int commissionAnalysisVersion) {
            this(draftId, documents, commissionAnalysisVersion, 0, null);
        }
    }
    public record SourceFile(FileSnapshot snapshot, byte[] bytes) {}

    @Transactional
    public DraftResponse create() {
        InventoryAiDraft draft = new InventoryAiDraft();
        draft.setId(UUID.randomUUID());
        draft.setTenantId(TenantContext.currentTenant());
        draft.setCompanyId(CompanyContext.currentCompany());
        draft.setOwnerUsername(inventoryAccess.username());
        draft.setCreatedAt(LocalDateTime.now());
        draft.setExpiresAt(LocalDateTime.now().plusDays(1));
        drafts.save(draft);
        return new DraftResponse(draft.getId(), draft.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public InventoryAiDraft requireOwner(UUID id, String username) {
        return drafts.findByIdAndTenantIdAndCompanyIdAndOwnerUsername(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
            .orElseThrow(() -> new NotFoundException("Inventory AI draft not found"));
    }

    @Transactional(readOnly = true)
    public InventoryAiDraft requireOpen(UUID id, String username) {
        InventoryAiDraft draft = requireOwner(id, username);
        if (!"OPEN".equals(draft.getStatus()) || !draft.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new NotFoundException("Inventory AI draft not found");
        }
        return draft;
    }

    private InventoryAiDraft lockOpen(UUID id, String username) {
        InventoryAiDraft draft = drafts.findForUpdateByIdAndTenantIdAndCompanyIdAndOwnerUsername(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany(), username)
            .orElseThrow(() -> new NotFoundException("Inventory AI draft not found"));
        if (!"OPEN".equals(draft.getStatus()) || !draft.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new NotFoundException("Inventory AI draft not found");
        }
        return draft;
    }

    @Transactional(readOnly = true)
    public List<AttachmentDto> files(UUID id) {
        requireOpen(id, inventoryAccess.username());
        return attachmentService.list(ENTITY_TYPE, id);
    }

    @Transactional
    public AttachmentDto upload(UUID id, MultipartFile file) {
        lockOpen(id, inventoryAccess.username());
        if (activeJob(id)) throw new BadRequestException("Wait for the current inventory document analysis to finish");
        validateFile(file);
        List<Attachment> current = storedFiles(id);
        if (current.size() >= MAX_FILES || current.stream().mapToLong(Attachment::getSizeBytes).sum() + file.getSize() > MAX_TOTAL_BYTES) {
            throw new BadRequestException("Inventory draft file count or total size limit exceeded");
        }
        String checksum = checksum(file);
        if (current.stream().anyMatch(existing -> checksum.equals(existing.getChecksumSha256()))) {
            throw new BadRequestException("This file is already attached to the inventory draft");
        }
        return attachmentService.uploadDraftSource(id, file);
    }

    @Transactional
    public void removeFile(UUID id, UUID fileId) {
        lockOpen(id, inventoryAccess.username());
        if (activeJob(id)) throw new BadRequestException("Wait for the current inventory document analysis to finish");
        Attachment file = storedFiles(id).stream().filter(item -> fileId.equals(item.getId())).findFirst()
            .orElseThrow(() -> new NotFoundException("Inventory draft file not found"));
        attachmentService.removeDraftSource(id, file.getId());
    }

    @Transactional
    public AnalysisRequest snapshot(UUID id, String username) {
        return snapshot(id, username, false);
    }

    @Transactional
    public AnalysisRequest snapshot(UUID id, String username, boolean orderAnalysis) {
        lockOpen(id, username);
        List<FileSnapshot> files = storedFiles(id).stream()
            .map(file -> new FileSnapshot(file.getId(), file.getOriginalFileName(), file.getContentType(), file.getChecksumSha256()))
            .toList();
        if (files.isEmpty()) throw new BadRequestException("Upload at least one document for analysis");
        return new AnalysisRequest(id, files, 1, orderAnalysis ? 1 : 0);
    }

    @Transactional
    public AnalysisRequest snapshotScopedOrder(UUID id, String username) {
        var scope = orderScopes.current();
        lockOpen(id, username);
        List<FileSnapshot> files = storedFiles(id).stream()
            .map(file -> new FileSnapshot(file.getId(), file.getOriginalFileName(), file.getContentType(), file.getChecksumSha256()))
            .toList();
        if (files.isEmpty()) throw new BadRequestException("Upload at least one document for analysis");
        return new AnalysisRequest(id, files, 2, 2, scope);
    }

    @Transactional(readOnly = true)
    public List<SourceFile> readSnapshot(AnalysisRequest request, String username) {
        requireOpen(request.draftId(), username);
        List<Attachment> current = storedFiles(request.draftId());
        if (current.size() != request.documents().size()) throw new BusinessException("Inventory draft files changed during analysis");
        return request.documents().stream().map(expected -> {
            Attachment actual = current.stream().filter(file -> file.getId().equals(expected.fileId())).findFirst()
                .orElseThrow(() -> new BusinessException("Inventory draft files changed during analysis"));
            if (!actual.getChecksumSha256().equals(expected.sha256())) throw new BusinessException("Inventory draft files changed during analysis");
            return new SourceFile(expected, attachmentService.readDraftBytes(request.draftId(), expected.fileId()));
        }).toList();
    }

    @Transactional
    public void bindToInventory(UUID id, UUID inventoryId) {
        InventoryAiDraft draft = drafts.findForUpdateByIdAndTenantIdAndCompanyIdAndOwnerUsername(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany(), inventoryAccess.username())
            .orElseThrow(() -> new NotFoundException("Inventory AI draft not found"));
        if (!"OPEN".equals(draft.getStatus()) || !draft.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BadRequestException("Inventory AI draft is no longer available");
        }
        if (activeJob(id)) throw new BadRequestException("Wait for the current inventory document analysis to finish");
        if (storedFiles(id).isEmpty()) throw new BadRequestException("Inventory AI draft has no files");
        attachmentService.bindDraftFiles(id, inventoryId);
        draft.setStatus("BOUND");
        draft.setInventoryDocumentId(inventoryId);
        drafts.save(draft);
    }

    @Transactional
    public void bindToOrder(UUID id, UUID orderId) {
        InventoryAiDraft draft = lockOpen(id, inventoryAccess.username());
        if (activeJob(id)) throw new BadRequestException("Wait for the current inventory document analysis to finish");
        if (storedFiles(id).isEmpty()) throw new BadRequestException("Inventory order has no source files");
        attachmentService.bindDraftFiles(id, orderId, "INVENTORY_ORDER");
        draft.setStatus("BOUND");
        draft.setInventoryOrderId(orderId);
        drafts.save(draft);
    }

    @Transactional
    public void expire(UUID id) {
        InventoryAiDraft draft = drafts.findById(id).orElse(null);
        if (draft == null || !"OPEN".equals(draft.getStatus()) || draft.getExpiresAt().isAfter(LocalDateTime.now())) return;
        TenantContext.setTenant(draft.getTenantId());
        CompanyContext.setCompany(draft.getCompanyId());
        try {
            if (activeJob(id)) {
                draft.setExpiresAt(LocalDateTime.now().plusHours(1));
            } else {
                attachmentService.purgeDraftFiles(id);
                draft.setStatus("EXPIRED");
            }
            drafts.save(draft);
        } finally {
            TenantContext.clear();
            CompanyContext.clear();
        }
    }

    private boolean activeJob(UUID id) {
        return jobs.existsByTenantIdAndCompanyIdAndAnalysisTypeAndEntityTypeAndEntityIdAndStatusIn(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION,
            ENTITY_TYPE, id, ACTIVE);
    }

    private List<Attachment> storedFiles(UUID id) {
        return attachments.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), ENTITY_TYPE, id);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Document file is required");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!List.of("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png", "image/jpeg", "image/webp").contains(type)) throw new BadRequestException("Unsupported inventory document type");
        try (InputStream stream = file.getInputStream()) {
            byte[] head = stream.readNBytes(12);
            boolean valid = switch (type) {
                case "application/pdf" -> starts(head, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> starts(head, new byte[] {0x50, 0x4b});
                case "image/png" -> starts(head, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
                case "image/jpeg" -> starts(head, new byte[] {(byte) 0xff, (byte) 0xd8});
                case "image/webp" -> starts(head, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) &&
                    head.length >= 12 && Arrays.equals(Arrays.copyOfRange(head, 8, 12), "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                default -> false;
            };
            if (!valid) throw new BadRequestException("Document content does not match its file type");
        } catch (java.io.IOException ex) {
            throw new BadRequestException("Cannot read inventory document");
        }
    }

    private boolean starts(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes, prefix.length), prefix);
    }

    private String checksum(MultipartFile file) {
        try (InputStream stream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException("Cannot calculate inventory document checksum");
        }
    }
}
