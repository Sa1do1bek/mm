package com.enterprise.mm.warehouse.reports.export;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.warehouse.reports.WarehouseReport;
import com.enterprise.mm.warehouse.reports.WarehouseReportExport;
import com.enterprise.mm.warehouse.reports.WarehouseReportService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseExportJobServiceTest {

    private WarehouseExportJobRepository jobRepository;
    private WarehouseReportService reportService;
    private WarehouseReportExport exporter;
    private UserRepository userRepository;
    private WarehouseExportJobService service;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jobRepository = mock(WarehouseExportJobRepository.class);
        reportService = mock(WarehouseReportService.class);
        exporter = mock(WarehouseReportExport.class);
        userRepository = mock(UserRepository.class);

        service = new WarehouseExportJobService(jobRepository, reportService, exporter, userRepository);

        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        when(jobRepository.save(any(WarehouseExportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void submitJobQueuesJobAndValidatesFormat() {
        WarehouseExportJobService.ExportJobRequest invalidFormat = new WarehouseExportJobService.ExportJobRequest(
            WarehouseReport.Type.TURNOVER,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 14),
            null, null, null, null, null, null, null, null, null,
            "pdf", "ru"
        );

        assertThatThrownBy(() -> service.submitJob(invalidFormat))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Supported export formats");

        WarehouseExportJobService.ExportJobRequest validRequest = new WarehouseExportJobService.ExportJobRequest(
            WarehouseReport.Type.TURNOVER,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 14),
            null, null, null, null, null, null, null, null, null,
            "xlsx", "ru"
        );

        WarehouseExportJobService.ExportJobResponse response = service.submitJob(validRequest);

        assertThat(response).isNotNull();
        assertThat(response.reportType()).isEqualTo(WarehouseReport.Type.TURNOVER);
        assertThat(response.format()).isEqualTo("xlsx");
        assertThat(response.status()).isEqualTo(WarehouseExportJobStatus.QUEUED);

        verify(jobRepository, atLeastOnce()).save(any(WarehouseExportJob.class));
    }

    @Test
    void processJobAsyncCompletesSuccessfullyAndWritesFile() throws Exception {
        UUID jobId = UUID.randomUUID();
        WarehouseExportJob job = new WarehouseExportJob();
        job.setId(jobId);
        job.setTenantId(tenantId);
        job.setCompanyId(companyId);
        job.setStatus(WarehouseExportJobStatus.QUEUED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        WarehouseReport dummyReport = new WarehouseReport(
            WarehouseReport.Type.STOCK_BALANCES,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 14),
            LocalDate.of(2026, 1, 1),
            LocalDateTime.now(),
            "Navoiyazot",
            "UZS",
            null, null, List.of(), List.of(), 0, 50, 0, List.of(), null, null
        );

        when(reportService.generate(any(WarehouseReport.Filter.class), eq(true))).thenReturn(dummyReport);
        byte[] mockBytes = "mock,csv,content".getBytes();
        when(exporter.csv(eq(dummyReport), eq("ru"))).thenReturn(mockBytes);

        WarehouseReport.Filter filter = new WarehouseReport.Filter(
            WarehouseReport.Type.STOCK_BALANCES,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 14),
            null, null, null, null, 0, 50, null, null
        );

        service.processJobAsync(jobId, tenantId, companyId, filter, "csv", "ru");

        assertThat(job.getStatus()).isEqualTo(WarehouseExportJobStatus.COMPLETED);
        assertThat(job.getProgressPercent()).isEqualTo(100);
        assertThat(job.getFilePath()).isNotNull();
        assertThat(job.getFileSize()).isEqualTo((long) mockBytes.length);

        Path savedPath = Paths.get(job.getFilePath());
        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.readAllBytes(savedPath)).isEqualTo(mockBytes);

        // cleanup test artifact
        Files.deleteIfExists(savedPath);
    }

    @Test
    void downloadJobFileRejectsIncompleteJob() {
        UUID jobId = UUID.randomUUID();
        WarehouseExportJob job = new WarehouseExportJob();
        job.setId(jobId);
        job.setTenantId(tenantId);
        job.setCompanyId(companyId);
        job.setStatus(WarehouseExportJobStatus.PROCESSING);

        when(jobRepository.findByIdAndTenantIdAndCompanyId(jobId, tenantId, companyId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.downloadJobFile(jobId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Export job is not yet completed");
    }
}
