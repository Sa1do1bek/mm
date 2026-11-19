package com.enterprise.mm.service;

import com.enterprise.mm.common.BusinessDisplayNamePolicy;

import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.ProductionEvidenceResponse;
import com.enterprise.mm.payload.ProductionEvidenceUpdateRequest;
import com.enterprise.mm.payload.ProductionEvidenceCreateRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionEvidenceAcceptanceRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionEvidenceSecurityChecklistRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionOpsCheckRequest;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightCheck;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightReport;
import com.enterprise.mm.payload.ProductionEvidenceSearchRequest;
import com.enterprise.mm.common.search.SearchCriteria;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.ProductionEvidenceMapper;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProductionEvidence;
import com.enterprise.mm.repository.ProductionEvidenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductionEvidenceService {
    private final ProductionEvidenceRepository repository;
    private final ProductionEvidenceMapper mapper;
    private final String entityName = ENTITY_NAME;
    private static final String ENTITY_NAME = "ProductionEvidence";
    private static final String ENTITY_TYPE = "PRODUCTION_EVIDENCE";
    private static final List<String> REQUIRED_ALERTS = List.of(
        "MmBackendDown",
        "MmBackendHighLatencyP95",
        "MmBackendHighErrorRate",
        "MmPostgresExporterDown",
        "MmRabbitMqExporterDown",
        "MmRabbitMqQueueBacklog"
    );
    private final ProductionReadinessService productionReadinessService;
    private final HaLoadDrEvidenceService haLoadDrEvidenceService;
    private final AttachmentService attachmentService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient httpClient;
    public ProductionEvidenceService(
        ProductionEvidenceRepository repository,
        ProductionEvidenceMapper mapper,
        AuditService auditService,
        ProductionReadinessService productionReadinessService,
        HaLoadDrEvidenceService haLoadDrEvidenceService,
        AttachmentService attachmentService,
        ObjectMapper objectMapper,
        Environment environment
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.productionReadinessService = productionReadinessService;
        this.haLoadDrEvidenceService = haLoadDrEvidenceService;
        this.attachmentService = attachmentService;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @Transactional
    public ProductionEvidenceResponse capturePreflight(boolean liveCheck) {
        ProductionPreflightReport report = productionReadinessService.report(liveCheck);
        ProductionEvidence evidence = baseEvidence("PREFLIGHT", "Production preflight " + LocalDateTime.now());
        evidence.setEvidenceSource(liveCheck ? "PREFLIGHT_LIVE" : "PREFLIGHT_CONFIG");
        evidence.setPreflightJson(toJson(report));
        evidence.setChecksJson(toJson(report.checks()));
        evidence.setOverallStatus(report.status());
        evidence.setStatus(report.status());
        evidence.setProductionReady(report.productionReady());
        evidence.setBlocksProductionCount(blockingFails(report.checks()));
        evidence.setWarningsCount(warnings(report.checks()));
        evidence.setRunId("preflight-" + System.currentTimeMillis());
        evidence.setName("Production preflight " + evidence.getRunId());
        ProductionEvidence saved = repository.save(evidence);
        AttachmentDto attachment = attachmentService.saveBytes(
            ENTITY_TYPE,
            saved.getId(),
            "PREFLIGHT_REPORT",
            saved.getRunId() + ".json",
            "application/json",
            evidence.getPreflightJson().getBytes(StandardCharsets.UTF_8)
        );
        saved.setEvidenceAttachmentId(attachment.getId());
        saved = repository.save(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "CAPTURE_PREFLIGHT", report.status(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ProductionEvidenceResponse uploadEvidenceJson(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Evidence file is required");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("Cannot read evidence file");
        }
        String json = new String(bytes, StandardCharsets.UTF_8);
        JsonNode root = parseJson(json);
        List<ProductionPreflightCheck> checks = haLoadDrEvidenceService.checks(root, safeFileName(file.getOriginalFilename()));
        ProductionEvidence evidence = baseEvidence("UPLOADED_EVIDENCE", "Uploaded production evidence");
        evidence.setEvidenceSource("UPLOAD");
        evidence.setEvidenceJson(json);
        evidence.setChecksJson(toJson(checks));
        evidence.setRunId(text(root, "runId", "upload-" + System.currentTimeMillis()));
        evidence.setEnvironmentName(text(root, "environment", evidence.getEnvironmentName()));
        evidence.setBaseUrl(text(root, "baseUrl", evidence.getBaseUrl()));
        evidence.setName("Production evidence " + evidence.getRunId());
        applyCheckSummary(evidence, checks);
        applyOperationalFields(evidence, root);
        ProductionEvidence saved = repository.save(evidence);
        AttachmentDto attachment = attachmentService.saveBytes(
            ENTITY_TYPE,
            saved.getId(),
            "HA_LOAD_MONITORING_DR_EVIDENCE",
            safeFileName(file.getOriginalFilename()),
            "application/json",
            bytes
        );
        saved.setEvidenceAttachmentId(attachment.getId());
        saved = repository.save(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "UPLOAD_EVIDENCE", saved.getOverallStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ProductionEvidenceResponse runOperationsCheck(ProductionOpsCheckRequest request) {
        ProductionOpsCheckRequest input = request == null ? new ProductionOpsCheckRequest(null, null, null, null, null, null, false, false, false) : request;
        String prometheusUrl = firstNonBlank(input.prometheusUrl(), env("PROMETHEUS_URL"), "http://localhost:9090");
        String alertmanagerUrl = firstNonBlank(input.alertmanagerUrl(), env("ALERTMANAGER_URL"), "http://localhost:9093");
        String grafanaUrl = firstNonBlank(input.grafanaUrl(), env("GRAFANA_URL"));
        String dashboardUrl = firstNonBlank(input.grafanaDashboardUrl(), env("GRAFANA_DASHBOARD_URL"));
        String targetsBody = fetch(prometheusUrl + "/api/v1/targets?state=active", env("PROMETHEUS_BEARER_TOKEN"));
        String rulesBody = fetch(prometheusUrl + "/api/v1/rules", env("PROMETHEUS_BEARER_TOKEN"));
        String alertsBody = fetch(alertmanagerUrl + "/api/v2/alerts", env("ALERTMANAGER_BEARER_TOKEN"));
        boolean grafanaReachable = reachable(firstNonBlank(dashboardUrl, grafanaUrl == null ? null : grafanaUrl + "/api/health"), env("GRAFANA_BEARER_TOKEN"));
        JsonNode targets = parseJson(targetsBody);
        JsonNode rules = parseJson(rulesBody);
        JsonNode alerts = parseJson(alertsBody);
        boolean scrapeVerified = prometheusRequiredTargetsHealthy(targets);
        boolean alertRulesVerified = prometheusRequiredAlertsPresent(rules);
        int criticalAlerts = criticalAlertCount(alerts);
        boolean monitoringPass = scrapeVerified
            && alertRulesVerified
            && grafanaReachable
            && input.dashboardReviewed()
            && input.onCallEscalationVerified()
            && input.runbookDrillPassed()
            && criticalAlerts == 0;
        Map<String, Object> evidenceJson = new LinkedHashMap<>();
        evidenceJson.put("schemaVersion", 2);
        evidenceJson.put("generatedAt", LocalDateTime.now().toString());
        evidenceJson.put("runId", "ops-check-" + System.currentTimeMillis());
        evidenceJson.put("environment", firstNonBlank(env("ENVIRONMENT_NAME"), "production"));
        Map<String, Object> monitoring = new LinkedHashMap<>();
        monitoring.put("status", monitoringPass ? "PASS" : "FAIL");
        monitoring.put("prometheusScrapeVerified", scrapeVerified);
        monitoring.put("alertRulesVerified", alertRulesVerified);
        monitoring.put("dashboardReviewed", input.dashboardReviewed());
        monitoring.put("onCallEscalationVerified", input.onCallEscalationVerified());
        monitoring.put("runbookDrillPassed", input.runbookDrillPassed());
        monitoring.put("alertmanagerChecked", true);
        monitoring.put("activeCriticalAlerts", criticalAlerts);
        monitoring.put("grafanaReachable", grafanaReachable);
        monitoring.put("dashboardUrl", dashboardUrl);
        evidenceJson.put("monitoring", monitoring);
        ProductionEvidence evidence = baseEvidence("OPERATIONS_LIVE_CHECK", "Operations live check");
        evidence.setEvidenceSource("BACKEND_LIVE_CHECK");
        evidence.setEvidenceJson(toJson(evidenceJson));
        evidence.setRunId(String.valueOf(evidenceJson.get("runId")));
        evidence.setOverallStatus(monitoringPass ? "PASS" : "FAIL");
        evidence.setStatus(evidence.getOverallStatus());
        evidence.setProductionReady(false);
        evidence.setBlocksProductionCount(monitoringPass ? 0 : 1);
        evidence.setWarningsCount(0);
        evidence.setGrafanaDashboardUid(input.grafanaDashboardUid());
        evidence.setGrafanaDashboardUrl(dashboardUrl);
        evidence.setGrafanaDashboardSnapshotUrl(input.grafanaDashboardSnapshotUrl());
        evidence.setChecksJson(toJson(List.of(new ProductionPreflightCheck(
            "OPERATIONS",
            "operations.monitoring-live",
            monitoringPass ? "PASS" : "FAIL",
            !monitoringPass,
            "Prometheus scrape=" + scrapeVerified + ", alertRules=" + alertRulesVerified + ", Grafana=" + grafanaReachable + ", criticalAlerts=" + criticalAlerts,
            monitoringPass ? List.of() : List.of("Fix Prometheus/Grafana/Alertmanager and rerun production monitoring drill")
        ))));
        ProductionEvidence saved = repository.save(evidence);
        AttachmentDto attachment = attachmentService.saveBytes(
            ENTITY_TYPE,
            saved.getId(),
            "OPERATIONS_LIVE_CHECK",
            saved.getRunId() + ".json",
            "application/json",
            evidence.getEvidenceJson().getBytes(StandardCharsets.UTF_8)
        );
        saved.setEvidenceAttachmentId(attachment.getId());
        saved = repository.save(saved);
        auditService.log(ENTITY_NAME, saved.getId(), "OPERATIONS_LIVE_CHECK", saved.getOverallStatus(), null, null);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ProductionEvidenceResponse accept(UUID id, ProductionEvidenceAcceptanceRequest request) {
        ProductionEvidence evidence = findEntity(id);
        String acceptedBy = firstNonBlank(request == null ? null : request.acceptedBy(), username());
        evidence.setAcceptedBy(acceptedBy);
        evidence.setAcceptedAt(LocalDateTime.now());
        evidence.setMonitoringAcceptedBy(firstNonBlank(request == null ? null : request.monitoringAcceptedBy(), acceptedBy));
        evidence.setMonitoringAcceptedAt(LocalDateTime.now());
        evidence.setAcceptanceNotes(request == null ? null : request.notes());
        repository.save(evidence);
        auditService.log(ENTITY_NAME, evidence.getId(), "ACCEPT", acceptedBy, null, null);
        return mapper.toResponse(evidence);
    }
    @Transactional
    public ProductionEvidenceResponse updateSecurityChecklist(UUID id, ProductionEvidenceSecurityChecklistRequest request) {
        ProductionEvidence evidence = findEntity(id);
        if (request == null) {
            throw new BadRequestException("Security checklist is required");
        }
        evidence.setPentestStatus(normalizeStatus(request.pentestStatus()));
        evidence.setDependencyAuditStatus(normalizeStatus(request.dependencyAuditStatus()));
        evidence.setTlsCipherScanStatus(normalizeStatus(request.tlsCipherScanStatus()));
        evidence.setBackupAccessReviewStatus(normalizeStatus(request.backupAccessReviewStatus()));
        evidence.setSecurityNotes(request.notes());
        evidence.setSecurityReportAttachmentId(request.securityReportAttachmentId());
        evidence.setSecurityChecklistJson(toJson(Map.of(
            "pentestStatus", nvl(evidence.getPentestStatus()),
            "dependencyAuditStatus", nvl(evidence.getDependencyAuditStatus()),
            "tlsCipherScanStatus", nvl(evidence.getTlsCipherScanStatus()),
            "backupAccessReviewStatus", nvl(evidence.getBackupAccessReviewStatus()),
            "notes", nvl(evidence.getSecurityNotes())
        )));
        repository.save(evidence);
        auditService.log(ENTITY_NAME, evidence.getId(), "SECURITY_CHECKLIST", evidence.getPentestStatus(), null, null);
        return mapper.toResponse(evidence);
    }
    @Transactional(readOnly = true)
    public byte[] goLivePdf(UUID id) {
        ProductionEvidence evidence = findEntity(id);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph("ERP MM Go-Live Readiness Report"));
            document.add(new Paragraph("Evidence: " + evidence.getName()));
            document.add(new Paragraph("Type: " + evidence.getEvidenceType()));
            document.add(new Paragraph("Status: " + evidence.getOverallStatus()));
            document.add(new Paragraph("Production ready: " + evidence.isProductionReady()));
            document.add(new Paragraph("Environment: " + nvl(evidence.getEnvironmentName())));
            document.add(new Paragraph("Base URL: " + nvl(evidence.getBaseUrl())));
            document.add(new Paragraph("Grafana: " + nvl(evidence.getGrafanaDashboardUrl())));
            document.add(new Paragraph("Grafana snapshot: " + nvl(evidence.getGrafanaDashboardSnapshotUrl())));
            document.add(new Paragraph("Blocking checks: " + evidence.getBlocksProductionCount()));
            document.add(new Paragraph("Warnings: " + evidence.getWarningsCount()));
            document.add(new Paragraph("RPO actual minutes: " + Objects.toString(evidence.getActualRpoMinutes(), "")));
            document.add(new Paragraph("RTO actual minutes: " + Objects.toString(evidence.getActualRtoMinutes(), "")));
            document.add(new Paragraph("Backup retention days: " + Objects.toString(evidence.getBackupRetentionDays(), "")));
            document.add(new Paragraph("Latest backup checksum: " + nvl(evidence.getLatestBackupChecksum())));
            document.add(new Paragraph("Restore checksum: " + nvl(evidence.getRestoreChecksum())));
            document.add(new Paragraph("Pentest: " + nvl(evidence.getPentestStatus())));
            document.add(new Paragraph("Dependency audit: " + nvl(evidence.getDependencyAuditStatus())));
            document.add(new Paragraph("TLS/cipher scan: " + nvl(evidence.getTlsCipherScanStatus())));
            document.add(new Paragraph("Backup access review: " + nvl(evidence.getBackupAccessReviewStatus())));
            document.add(new Paragraph("Accepted by: " + nvl(evidence.getAcceptedBy())));
            document.add(new Paragraph("Accepted at: " + Objects.toString(evidence.getAcceptedAt(), "")));
            document.add(new Paragraph("Acceptance notes: " + nvl(evidence.getAcceptanceNotes())));
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new BusinessException("Cannot build go-live PDF report");
        }
    }
    @Transactional(readOnly = true)
    public byte[] goLiveExcel(UUID id) {
        ProductionEvidence evidence = findEntity(id);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Go-live readiness");
            List<List<String>> rows = List.of(
                List.of("Evidence", nvl(evidence.getName())),
                List.of("Type", nvl(evidence.getEvidenceType())),
                List.of("Status", nvl(evidence.getOverallStatus())),
                List.of("Production ready", String.valueOf(evidence.isProductionReady())),
                List.of("Environment", nvl(evidence.getEnvironmentName())),
                List.of("Base URL", nvl(evidence.getBaseUrl())),
                List.of("Grafana dashboard", nvl(evidence.getGrafanaDashboardUrl())),
                List.of("Grafana snapshot", nvl(evidence.getGrafanaDashboardSnapshotUrl())),
                List.of("Blocking checks", String.valueOf(evidence.getBlocksProductionCount())),
                List.of("Warnings", String.valueOf(evidence.getWarningsCount())),
                List.of("Actual RPO minutes", Objects.toString(evidence.getActualRpoMinutes(), "")),
                List.of("Actual RTO minutes", Objects.toString(evidence.getActualRtoMinutes(), "")),
                List.of("Backup retention days", Objects.toString(evidence.getBackupRetentionDays(), "")),
                List.of("Latest backup checksum", nvl(evidence.getLatestBackupChecksum())),
                List.of("Restore checksum", nvl(evidence.getRestoreChecksum())),
                List.of("Pentest", nvl(evidence.getPentestStatus())),
                List.of("Dependency audit", nvl(evidence.getDependencyAuditStatus())),
                List.of("TLS/cipher scan", nvl(evidence.getTlsCipherScanStatus())),
                List.of("Backup access review", nvl(evidence.getBackupAccessReviewStatus())),
                List.of("Accepted by", nvl(evidence.getAcceptedBy())),
                List.of("Accepted at", Objects.toString(evidence.getAcceptedAt(), "")),
                List.of("Acceptance notes", nvl(evidence.getAcceptanceNotes()))
            );
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(rows.get(i).get(0));
                row.createCell(1).setCellValue(rows.get(i).get(1));
            }
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException("Cannot build go-live Excel report");
        }
    }
    protected void beforeCreate(ProductionEvidence entity, ProductionEvidenceCreateRequest dto) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("ACTIVE");
        }
        if (entity.getCompanyId() == null) {
            entity.setCompanyId(CompanyContext.currentCompany());
        }
        if (entity.getCode() == null || entity.getCode().isBlank()) {
            entity.setCode(entityName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        if (blank(entity.getEvidenceType())) {
            entity.setEvidenceType("MANUAL");
        }
        if (blank(entity.getOverallStatus())) {
            entity.setOverallStatus(nvl(entity.getStatus(), "DRAFT"));
        }
        entity.setStatus(normalizeStatus(entity.getOverallStatus()));
    }
    protected void beforeUpdate(ProductionEvidence entity, ProductionEvidenceUpdateRequest dto) {
        entity.setName(BusinessDisplayNamePolicy.requireHumanName(
            entity.getClass().getSimpleName(), entity.getName(), entity.getCode()
        ));

        entity.setStatus(normalizeStatus(firstNonBlank(entity.getOverallStatus(), entity.getStatus(), "DRAFT")));
    }
    private ProductionEvidence baseEvidence(String type, String name) {
        ProductionEvidence evidence = new ProductionEvidence();
        evidence.setEvidenceType(type);
        evidence.setCode(type + "-" + UUID.randomUUID().toString().substring(0, 8));
        evidence.setName(name);
        evidence.setStatus("DRAFT");
        evidence.setOverallStatus("DRAFT");
        evidence.setEnvironmentName(firstNonBlank(env("ENVIRONMENT_NAME"), "production"));
        evidence.setCompanyId(CompanyContext.currentCompany());
        return evidence;
    }
    private void applyCheckSummary(ProductionEvidence evidence, List<ProductionPreflightCheck> checks) {
        evidence.setBlocksProductionCount(blockingFails(checks));
        evidence.setWarningsCount(warnings(checks));
        boolean hasFail = evidence.getBlocksProductionCount() > 0;
        boolean hasWarn = warnings(checks) > 0;
        evidence.setOverallStatus(hasFail ? "FAIL" : hasWarn ? "WARN" : "PASS");
        evidence.setStatus(evidence.getOverallStatus());
        evidence.setProductionReady(!hasFail && !hasWarn);
    }
    private void applyOperationalFields(ProductionEvidence evidence, JsonNode root) {
        evidence.setGrafanaDashboardUrl(text(root.path("monitoring"), "dashboardUrl", evidence.getGrafanaDashboardUrl()));
        evidence.setGrafanaDashboardSnapshotUrl(text(root.path("monitoring"), "dashboardSnapshotUrl", evidence.getGrafanaDashboardSnapshotUrl()));
        evidence.setBackupRetentionDays(integer(root.path("dr"), "backupRetentionDays"));
        evidence.setBackupRetentionVerified(bool(root.path("dr"), "backupRetentionVerified"));
        evidence.setLatestBackupChecksum(text(root.path("dr"), "latestBackupChecksum", null));
        evidence.setRestoreChecksum(text(root.path("dr"), "restoreChecksum", null));
        evidence.setActualRpoMinutes(integer(root.path("dr"), "actualRpoMinutes"));
        evidence.setActualRtoMinutes(integer(root.path("dr"), "actualRtoMinutes"));
    }
    private int blockingFails(List<ProductionPreflightCheck> checks) {
        return (int) checks.stream().filter(check -> check.blocksProduction() && "FAIL".equals(check.status())).count();
    }
    private int warnings(List<ProductionPreflightCheck> checks) {
        return (int) checks.stream().filter(check -> !"PASS".equals(check.status())).count();
    }
    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new BadRequestException("Invalid JSON");
        }
    }
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("Cannot serialize evidence JSON");
        }
    }
    private String fetch(String url, String bearerToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
            if (!blank(bearerToken)) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BadRequestException("Endpoint returned HTTP " + response.statusCode() + ": " + url);
            }
            return response.body();
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Cannot call endpoint " + url + ": " + ex.getMessage());
        }
    }
    private boolean reachable(String url, String bearerToken) {
        if (blank(url)) {
            return false;
        }
        try {
            fetch(url, bearerToken);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
    private boolean prometheusRequiredTargetsHealthy(JsonNode targets) {
        JsonNode activeTargets = targets.path("data").path("activeTargets");
        if (!activeTargets.isArray()) {
            return false;
        }
        List<String> requiredJobs = List.of("mm-backend", "mm-postgres", "mm-rabbitmq");
        for (String job : requiredJobs) {
            boolean present = false;
            for (JsonNode target : activeTargets) {
                if (job.equals(target.path("labels").path("job").asText()) && "up".equals(target.path("health").asText())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return false;
            }
        }
        return true;
    }
    private boolean prometheusRequiredAlertsPresent(JsonNode rules) {
        JsonNode groups = rules.path("data").path("groups");
        if (!groups.isArray()) {
            return false;
        }
        List<String> present = new ArrayList<>();
        for (JsonNode group : groups) {
            for (JsonNode rule : group.path("rules")) {
                if ("alerting".equals(rule.path("type").asText()) && !blank(rule.path("name").asText())) {
                    present.add(rule.path("name").asText());
                }
            }
        }
        return present.containsAll(REQUIRED_ALERTS);
    }
    private int criticalAlertCount(JsonNode alerts) {
        JsonNode values = alerts.isArray() ? alerts : alerts.path("data").path("alerts");
        if (!values.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode alert : values) {
            String severity = alert.path("labels").path("severity").asText();
            String state = firstNonBlank(alert.path("status").path("state").asText(), alert.path("state").asText(), "active");
            if ("critical".equals(severity) && !"resolved".equals(state) && !"suppressed".equals(state)) {
                count++;
            }
        }
        return count;
    }
    private String normalizeStatus(String value) {
        if (blank(value)) {
            return "DRAFT";
        }
        String status = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (status) {
            case "PASS", "PASSED", "OK" -> "PASS";
            case "WARN", "WARNING" -> "WARN";
            case "FAIL", "FAILED", "BLOCKED" -> "FAIL";
            case "ACCEPTED", "APPROVED" -> "ACCEPTED";
            default -> status;
        };
    }
    private String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || blank(authentication.getName()) ? "system" : authentication.getName();
    }
    private String safeFileName(String fileName) {
        if (blank(fileName)) {
            return "production-evidence.json";
        }
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    private String env(String key) {
        return firstNonBlank(environment.getProperty(key), System.getenv(key));
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!blank(value)) {
                return value.trim();
            }
        }
        return null;
    }
    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText(fallback);
    }
    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText("").isBlank()) {
            return null;
        }
        return value.isNumber() ? value.asInt() : Integer.valueOf(value.asText().replace("_", "").replace(" ", ""));
    }
    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : "true".equalsIgnoreCase(value.asText());
    }
    private String nvl(String value) {
        return value == null ? "" : value;
    }
    private String nvl(String value, String fallback) {
        return blank(value) ? fallback : value;
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    @Transactional(readOnly = true)
    public Page<ProductionEvidenceResponse> search(ProductionEvidenceSearchRequest request, Pageable pageable) {
        SearchCriteria filter = SearchCriteria.of(request);
        boolean scoped = com.enterprise.mm.security.AccessScope.companyScoped(companyScoped());
        return repository.search(
            TenantContext.currentTenant(),
            scoped ? CompanyContext.currentCompany() : null,
            filter.includeDeleted(),
            filter.normalizedSearch(),
            filter.fromDateTime(),
            filter.toDateTime(),
            filter.status(),
            !filter.hasStatus(),
            pageable
        ).map(mapper::toResponse);
    }
    @Transactional(readOnly = true)
    public ProductionEvidenceResponse get(UUID id) {
        return mapper.toResponse(findEntity(id));
    }
    @Transactional
    public ProductionEvidenceResponse create(ProductionEvidenceCreateRequest dto) {
        ProductionEvidence entity = mapper.toEntity(dto);
        beforeCreate(entity, dto);
        ProductionEvidence saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "CREATE", saved.getCode(), null, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public ProductionEvidenceResponse update(UUID id, ProductionEvidenceUpdateRequest dto) {
        ProductionEvidence entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        mapper.updateEntity(entity, dto);
        beforeUpdate(entity, dto);
        ProductionEvidence saved = repository.save(entity);
        auditService.log(entityName, saved.getId(), "UPDATE", saved.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    @Transactional
    public void softDelete(UUID id) {
        ProductionEvidence entity = findEntity(id);
        String oldSnapshot = auditService.snapshot(entity);
        entity.softDelete();
        repository.save(entity);
        auditService.log(entityName, id, "SOFT_DELETE", entity.getCode(), oldSnapshot, entity);
    }
    @Transactional
    public ProductionEvidenceResponse restore(UUID id) {
        ProductionEvidence entity = repository.findAnyById(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        String oldSnapshot = auditService.snapshot(entity);
        entity.restore();
        ProductionEvidence saved = repository.save(entity);
        auditService.log(entityName, id, "RESTORE", entity.getCode(), oldSnapshot, saved);
        return mapper.toResponse(saved);
    }
    protected ProductionEvidence findEntity(UUID id) {
        ProductionEvidence entity = repository.findByIdAndIsDeletedFalse(id)
            .orElseThrow(() -> new NotFoundException(entityName + " not found: " + id));
        if (entity.isDeleted()) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (!TenantContext.currentTenant().equals(entity.getTenantId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        if (com.enterprise.mm.security.AccessScope.companyScoped(companyScoped()) && !com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId())) {
            throw new NotFoundException(entityName + " not found: " + id);
        }
        return entity;
    }
    protected boolean companyScoped() {
        return true;
    }
}

