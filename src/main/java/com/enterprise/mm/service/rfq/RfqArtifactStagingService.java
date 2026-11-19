package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.AttachmentProperties;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.attachment.AttachmentStorage;
import com.enterprise.mm.service.attachment.AttachmentStorage.StoredFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RfqArtifactStagingService {
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String EXCEL_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final RfqPdfRenderer pdfRenderer;
    private final RfqExcelRenderer excelRenderer;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentProperties attachmentProperties;
    private final List<AttachmentStorage> storages;
    private final Path stagingRoot;

    public RfqArtifactStagingService(
        RfqPdfRenderer pdfRenderer,
        RfqExcelRenderer excelRenderer,
        AttachmentRepository attachmentRepository,
        AttachmentProperties attachmentProperties,
        List<AttachmentStorage> storages,
        @Value("${mm.rfq-package.staging-root:${java.io.tmpdir}/mm-rfq-staging}") Path stagingRoot
    ) {
        this.pdfRenderer = pdfRenderer;
        this.excelRenderer = excelRenderer;
        this.attachmentRepository = attachmentRepository;
        this.attachmentProperties = attachmentProperties;
        this.storages = List.copyOf(storages);
        this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
    }

    public StagedPackage stage(RfqPackageSnapshot snapshot) {
        try {
            Files.createDirectories(stagingRoot);
            Path directory = Files.createTempDirectory(stagingRoot, "rfq-");
            Path pdf = directory.resolve("request.pdf");
            Path excel = directory.resolve("quotation.xlsx");
            Files.write(pdf, pdfRenderer.render(snapshot));
            Files.write(excel, excelRenderer.render(snapshot));
            return new StagedPackage(
                directory,
                pdf,
                excel,
                sha256(snapshot.toString().getBytes(StandardCharsets.UTF_8)),
                snapshot.rfqCode()
            );
        } catch (Exception exception) {
            throw new BusinessException("Cannot stage RFQ package");
        }
    }

    public PromotedPackage promote(StagedPackage staged, UUID versionId) {
        if (staged == null || versionId == null) {
            throw new BusinessException("RFQ staged package and version are required");
        }
        AttachmentStorage storage = storage();
        String baseName = safeName(staged.rfqCode());
        String pdfName = baseName + ".pdf";
        String excelName = baseName + ".xlsx";
        String pdfKey = storageKey(versionId, pdfName);
        String excelKey = storageKey(versionId, excelName);
        List<String> storedKeys = new ArrayList<>();
        try {
            byte[] pdfBytes = Files.readAllBytes(staged.pdf());
            byte[] excelBytes = Files.readAllBytes(staged.excel());
            StoredFile pdfStored = storage.store(pdfBytes, pdfKey, PDF_CONTENT_TYPE);
            storedKeys.add(pdfKey);
            StoredFile excelStored = storage.store(excelBytes, excelKey, EXCEL_CONTENT_TYPE);
            storedKeys.add(excelKey);
            Attachment pdf = attachment(
                versionId,
                "RFQ_OFFICIAL_PDF",
                pdfName,
                PDF_CONTENT_TYPE,
                pdfKey,
                pdfStored
            );
            Attachment excel = attachment(
                versionId,
                "RFQ_QUOTATION_TEMPLATE",
                excelName,
                EXCEL_CONTENT_TYPE,
                excelKey,
                excelStored
            );
            List<Attachment> saved = attachmentRepository.saveAll(List.of(pdf, excel));
            if (saved.size() != 2 || saved.get(0).getId() == null || saved.get(1).getId() == null) {
                throw new BusinessException("RFQ package attachment persistence failed");
            }
            registerRollbackCleanup(storage, List.copyOf(storedKeys));
            return new PromotedPackage(saved.get(0).getId(), saved.get(1).getId());
        } catch (Exception exception) {
            storedKeys.forEach(storage::delete);
            throw new BusinessException("Cannot promote RFQ package: " + safeMessage(exception));
        }
    }

    public void cleanup(StagedPackage staged) {
        if (staged != null) {
            deleteDirectory(staged.directory());
        }
    }

    @Scheduled(fixedDelayString = "${mm.rfq-package.cleanup-delay-ms:3600000}")
    public void cleanupExpired() {
        if (!Files.isDirectory(stagingRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        try (var directories = Files.list(stagingRoot)) {
            directories
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("rfq-"))
                .filter(path -> lastModified(path).isBefore(cutoff))
                .forEach(this::deleteDirectory);
        } catch (Exception exception) {
            throw new BusinessException("Cannot clean expired RFQ staging files");
        }
    }

    private Attachment attachment(
        UUID versionId,
        String attachmentType,
        String fileName,
        String contentType,
        String storageKey,
        StoredFile stored
    ) {
        Attachment attachment = new Attachment();
        attachment.setTenantId(TenantContext.currentTenant());
        attachment.setCompanyId(CompanyContext.currentCompany());
        attachment.setCode("ATT-" + UUID.randomUUID().toString().substring(0, 8));
        attachment.setName(fileName);
        attachment.setStatus("ACTIVE");
        attachment.setEntityType("RFQ_PUBLISHED_VERSION");
        attachment.setEntityId(versionId);
        attachment.setAttachmentType(attachmentType);
        attachment.setOriginalFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setChecksumSha256(stored.checksumSha256());
        attachment.setStorageProvider(storageProvider());
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(LocalDateTime.now());
        attachment.setPreviewable(PDF_CONTENT_TYPE.equals(contentType));
        attachment.setDownloadable(true);
        return attachment;
    }

    private AttachmentStorage storage() {
        String provider = storageProvider();
        return storages.stream()
            .filter(candidate -> candidate.supports(provider))
            .findFirst()
            .orElseThrow(() -> new BusinessException("Unsupported attachment storage provider: " + provider));
    }

    private String storageProvider() {
        String provider = attachmentProperties.getStorageProvider();
        return provider == null || provider.isBlank() ? "LOCAL" : provider.toUpperCase(Locale.ROOT);
    }

    private String storageKey(UUID versionId, String fileName) {
        return TenantContext.currentTenant() + "/" + CompanyContext.currentCompany()
            + "/RFQ_PUBLISHED_VERSION/" + versionId + "/" + UUID.randomUUID() + "-" + fileName;
    }

    private String safeName(String value) {
        String safe = value == null ? "RFQ" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "RFQ" : safe;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new BusinessException("Cannot calculate RFQ snapshot checksum");
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception exception) {
            return Instant.MAX;
        }
    }

    private void deleteDirectory(Path directory) {
        Path normalized = directory == null ? null : directory.toAbsolutePath().normalize();
        if (normalized == null || !normalized.startsWith(stagingRoot) || normalized.equals(stagingRoot)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // A later cleanup pass will retry inaccessible files.
                }
            });
        } catch (Exception ignored) {
            // A later cleanup pass will retry inaccessible files.
        }
    }

    private void registerRollbackCleanup(AttachmentStorage storage, List<String> storageKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    storageKeys.forEach(key -> {
                        try {
                            storage.delete(key);
                        } catch (Exception ignored) {
                            // The normal attachment cleanup process can retry inaccessible keys.
                        }
                    });
                }
            }
        });
    }

    public record StagedPackage(
        Path directory,
        Path pdf,
        Path excel,
        String snapshotHash,
        String rfqCode
    ) {
    }

    public record PromotedPackage(UUID pdfAttachmentId, UUID excelAttachmentId) {
    }
}
