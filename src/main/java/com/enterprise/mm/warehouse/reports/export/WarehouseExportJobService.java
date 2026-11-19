package com.enterprise.mm.warehouse.reports.export;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import com.enterprise.mm.warehouse.reports.WarehouseReportExport;
import com.enterprise.mm.warehouse.reports.WarehouseReportService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class WarehouseExportJobService {

    private final WarehouseExportJobRepository jobRepository;
    private final WarehouseReportService reportService;
    private final WarehouseReportExport exporter;
    private final UserRepository userRepository;
    private final Map<UUID, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public WarehouseExportJobService(
        WarehouseExportJobRepository jobRepository,
        WarehouseReportService reportService,
        WarehouseReportExport exporter,
        UserRepository userRepository
    ) {
        this.jobRepository = jobRepository;
        this.reportService = reportService;
        this.exporter = exporter;
        this.userRepository = userRepository;
    }

    public record ExportJobRequest(
        WarehouseReport.Type reportType,
        java.time.LocalDate from,
        java.time.LocalDate to,
        UUID warehouseId,
        UUID materialId,
        UUID materialGroupId,
        com.enterprise.mm.enums.MaterialSubtype materialSubtype,
        com.enterprise.mm.enums.MaterialType materialType,
        String accountCode,
        String sourceType,
        UUID ownerPartyId,
        String search,
        String format,
        String language,
        LocalDateTime snapshotTimestamp
    ) {
        public ExportJobRequest(
            WarehouseReport.Type reportType,
            LocalDate from,
            LocalDate to,
            UUID warehouseId,
            UUID materialId,
            UUID materialGroupId,
            com.enterprise.mm.enums.MaterialSubtype materialSubtype,
            com.enterprise.mm.enums.MaterialType materialType,
            String accountCode,
            String sourceType,
            UUID ownerPartyId,
            String search,
            String format,
            String language
        ) {
            this(reportType, from, to, warehouseId, materialId, materialGroupId, materialSubtype, materialType, accountCode, sourceType, ownerPartyId, search, format, language, null);
        }
    }

    public record ExportJobResponse(
        UUID id,
        WarehouseReport.Type reportType,
        String format,
        String fileName,
        WarehouseExportJobStatus status,
        int progressPercent,
        Long fileSize,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime completedAt
    ) {
        public static ExportJobResponse from(WarehouseExportJob job) {
            return new ExportJobResponse(
                job.getId(),
                job.getReportType(),
                job.getFormat(),
                job.getFileName(),
                job.getStatus(),
                job.getProgressPercent(),
                job.getFileSize(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
            );
        }
    }

    @Transactional
    public ExportJobResponse submitJob(ExportJobRequest request) {
        if (request.reportType() == null) {
            throw new BusinessException("Report type is required for export job", "EXPORT_JOB_REPORT_TYPE_REQUIRED");
        }
        String format = request.format() == null ? "xlsx" : request.format().toLowerCase(Locale.ROOT).trim();
        if (!"xlsx".equals(format) && !"csv".equals(format)) {
            throw new BusinessException("Supported export formats: xlsx, csv", "EXPORT_JOB_FORMAT_INVALID");
        }
        String language = request.language() == null ? "ru" : request.language().toLowerCase(Locale.ROOT).trim();

        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();
        UUID userId = resolveCurrentUserId(tenantId, companyId);

        WarehouseExportJob job = new WarehouseExportJob();
        job.setId(UUID.randomUUID());
        job.setTenantId(tenantId);
        job.setCompanyId(companyId);
        job.setReportType(request.reportType());
        job.setFormat(format);
        job.setRequestedBy(userId);
        job.setStatus(WarehouseExportJobStatus.QUEUED);
        job.setProgressPercent(0);
        job.setFileName("warehouse-" + request.reportType().name().toLowerCase(Locale.ROOT) + "-" + request.to() + "." + format);
        job.setCreatedAt(LocalDateTime.now());

        WarehouseExportJob saved = jobRepository.save(job);

        WarehouseReport.Filter filter = new WarehouseReport.Filter(
            request.reportType(),
            request.from(),
            request.to(),
            request.warehouseId(),
            request.materialId(),
            request.materialGroupId(),
            request.search(),
            0,
            200,
            request.materialSubtype(),
            request.materialType(),
            request.accountCode(),
            request.sourceType(),
            request.ownerPartyId(),
            request.snapshotTimestamp()
        );

        // Asynchronous processing call
        CompletableFuture<Void> future = processJobAsync(saved.getId(), tenantId, companyId, filter, format, language);
        if (future != null) {
            activeTasks.put(saved.getId(), future);
            future.whenComplete((res, ex) -> activeTasks.remove(saved.getId()));
        }

        return ExportJobResponse.from(saved);
    }

    @Async("warehouseExportJobExecutor")
    public CompletableFuture<Void> processJobAsync(UUID jobId, String tenantId, UUID companyId, WarehouseReport.Filter filter, String format, String language) {
        try {
            TenantContext.setTenant(tenantId);
            CompanyContext.setCompany(companyId);

            if (isJobCancelled(jobId) || Thread.currentThread().isInterrupted()) {
                cleanupJobFile(jobId, format);
                return CompletableFuture.completedFuture(null);
            }

            updateJobProgress(jobId, WarehouseExportJobStatus.PROCESSING, 15, null, null, null);

            log.info("Processing warehouse export job id={} type={} format={}", jobId, filter.type(), format);
            WarehouseReport report = reportService.generate(filter, true);

            if (isJobCancelled(jobId) || Thread.currentThread().isInterrupted()) {
                cleanupJobFile(jobId, format);
                return CompletableFuture.completedFuture(null);
            }

            updateJobProgress(jobId, WarehouseExportJobStatus.PROCESSING, 60, null, null, null);

            byte[] bytes = "csv".equals(format) ? exporter.csv(report, language) : exporter.xlsx(report, language);

            if (isJobCancelled(jobId) || Thread.currentThread().isInterrupted()) {
                cleanupJobFile(jobId, format);
                return CompletableFuture.completedFuture(null);
            }

            updateJobProgress(jobId, WarehouseExportJobStatus.PROCESSING, 85, null, null, null);

            Path exportDir = Paths.get(System.getProperty("java.io.tmpdir"), "mm-warehouse-exports");
            Files.createDirectories(exportDir);
            Path filePath = exportDir.resolve(jobId + "." + format);
            Files.write(filePath, bytes);

            if (isJobCancelled(jobId) || Thread.currentThread().isInterrupted()) {
                cleanupJobFile(jobId, format);
                return CompletableFuture.completedFuture(null);
            }

            updateJobProgress(jobId, WarehouseExportJobStatus.COMPLETED, 100, filePath.toAbsolutePath().toString(), (long) bytes.length, null);
            log.info("Warehouse export job completed id={} size={}", jobId, bytes.length);
        } catch (Exception ex) {
            if (isJobCancelled(jobId) || Thread.currentThread().isInterrupted()) {
                log.info("Warehouse export job was cancelled id={}", jobId);
                cleanupJobFile(jobId, format);
            } else {
                log.error("Warehouse export job failed id={}", jobId, ex);
                updateJobProgress(jobId, WarehouseExportJobStatus.FAILED, 0, null, null, ex.getMessage());
            }
        } finally {
            activeTasks.remove(jobId);
            TenantContext.clear();
            CompanyContext.clear();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void updateJobProgress(UUID jobId, WarehouseExportJobStatus status, int progressPercent, String filePath, Long fileSize, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == WarehouseExportJobStatus.CANCELLED) {
                return;
            }
            job.setStatus(status);
            job.setProgressPercent(progressPercent);
            if (filePath != null) job.setFilePath(filePath);
            if (fileSize != null) job.setFileSize(fileSize);
            if (errorMessage != null) job.setErrorMessage(errorMessage);
            if (status == WarehouseExportJobStatus.COMPLETED || status == WarehouseExportJobStatus.FAILED) {
                job.setCompletedAt(LocalDateTime.now());
            }
            jobRepository.save(job);
        });
    }

    @Transactional
    public ExportJobResponse cancelJob(UUID jobId) {
        WarehouseExportJob job = jobRepository.findByIdAndTenantIdAndCompanyId(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Export job not found: " + jobId));

        if (job.getStatus() == WarehouseExportJobStatus.COMPLETED || job.getStatus() == WarehouseExportJobStatus.FAILED) {
            throw new BusinessException("Export job is already in terminal state: " + job.getStatus(), "EXPORT_JOB_ALREADY_TERMINATED");
        }

        if (job.getStatus() == WarehouseExportJobStatus.CANCELLED) {
            return ExportJobResponse.from(job);
        }

        job.setStatus(WarehouseExportJobStatus.CANCELLED);
        job.setErrorMessage("Cancelled by user");
        job.setCompletedAt(LocalDateTime.now());
        WarehouseExportJob saved = jobRepository.save(job);

        Future<?> future = activeTasks.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }

        if (saved.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(saved.getFilePath()));
            } catch (Exception ignored) {}
        }

        log.info("Warehouse export job cancelled id={}", jobId);
        return ExportJobResponse.from(saved);
    }

    private boolean isJobCancelled(UUID jobId) {
        return jobRepository.findById(jobId)
            .map(j -> j.getStatus() == WarehouseExportJobStatus.CANCELLED)
            .orElse(false);
    }

    private void cleanupJobFile(UUID jobId, String format) {
        try {
            Path exportDir = Paths.get(System.getProperty("java.io.tmpdir"), "mm-warehouse-exports");
            Path filePath = exportDir.resolve(jobId + "." + format);
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {}
    }

    @Transactional(readOnly = true)
    public ExportJobResponse getJob(UUID jobId) {
        WarehouseExportJob job = jobRepository.findByIdAndTenantIdAndCompanyId(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Export job not found: " + jobId));
        return ExportJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public List<ExportJobResponse> listJobs() {
        return jobRepository.findByTenantIdAndCompanyIdOrderByCreatedAtDesc(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream()
            .map(ExportJobResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public byte[] downloadJobFile(UUID jobId) {
        WarehouseExportJob job = jobRepository.findByIdAndTenantIdAndCompanyId(jobId, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Export job not found: " + jobId));

        if (job.getStatus() != WarehouseExportJobStatus.COMPLETED) {
            throw new BusinessException("Export job is not yet completed. Current status: " + job.getStatus(), "EXPORT_JOB_NOT_READY");
        }
        if (job.getFilePath() == null) {
            throw new NotFoundException("Export file path not recorded for job: " + jobId);
        }
        try {
            Path path = Paths.get(job.getFilePath());
            if (!Files.exists(path)) {
                throw new NotFoundException("Export file no longer exists on disk: " + job.getFileName());
            }
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new BusinessException("Failed to read export file: " + ex.getMessage(), "EXPORT_FILE_READ_ERROR");
        }
    }

    private UUID resolveCurrentUserId(String tenantId, UUID companyId) {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
                return userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(tenantId, companyId, auth.getName())
                    .map(User::getId)
                    .orElse(null);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
