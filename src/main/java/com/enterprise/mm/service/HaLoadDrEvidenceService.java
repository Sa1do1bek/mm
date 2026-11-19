package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ProductionPreflightDtos.ProductionPreflightCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HaLoadDrEvidenceService {
    private static final String DEFAULT_EVIDENCE_FILE = "reports/production-readiness/ha-load-dr-evidence.json";
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public List<ProductionPreflightCheck> checks() {
        Path evidence = evidencePath();
        if (!Files.isRegularFile(evidence)) {
            return List.of(
                missing("operations.load-test", "No production load-test evidence file found at " + evidence),
                missing("operations.ha", "No HA rollout/failover evidence file found at " + evidence),
                missing("operations.monitoring", "No monitoring runbook evidence file found at " + evidence),
                missing("operations.dr", "No DR backup/restore evidence file found at " + evidence)
            );
        }
        try {
            JsonNode root = objectMapper.readTree(evidence.toFile());
            return checks(root, evidence.toString());
        } catch (Exception ex) {
            return List.of(
                failOrOverride("operations.load-test", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix evidence JSON and rerun ops/ha-load-dr/validate-evidence.mjs"),
                failOrOverride("operations.ha", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix evidence JSON and rerun ops/ha-load-dr/validate-evidence.mjs"),
                failOrOverride("operations.monitoring", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix evidence JSON and rerun ops/ha-load-dr/validate-evidence.mjs"),
                failOrOverride("operations.dr", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix evidence JSON and rerun ops/ha-load-dr/validate-evidence.mjs")
            );
        }
    }
    public List<ProductionPreflightCheck> checks(String evidenceJson, String evidenceSource) {
        try {
            return checks(objectMapper.readTree(evidenceJson), evidenceSource);
        } catch (Exception ex) {
            return List.of(
                failOrOverride("operations.load-test", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix uploaded evidence JSON and rerun validation"),
                failOrOverride("operations.ha", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix uploaded evidence JSON and rerun validation"),
                failOrOverride("operations.monitoring", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix uploaded evidence JSON and rerun validation"),
                failOrOverride("operations.dr", "Cannot read HA/load/monitoring/DR evidence: " + ex.getMessage(), "Fix uploaded evidence JSON and rerun validation")
            );
        }
    }
    public List<ProductionPreflightCheck> checks(JsonNode root, String evidenceSource) {
        return List.of(
            loadCheck(root.path("loadTest"), evidenceSource),
            haCheck(root.path("ha"), evidenceSource),
            monitoringCheck(root.path("monitoring"), evidenceSource),
            drCheck(root.path("dr"), evidenceSource)
        );
    }
    private ProductionPreflightCheck loadCheck(JsonNode node, String evidence) {
        int targetVus = integer(node, "targetVus");
        long materialCards = longValue(node, "materialCards");
        long historicalOperations = longValue(node, "historicalOperations");
        double p95 = decimal(node, "p95Ms");
        double errorRate = decimal(node, "errorRate");
        double checksRate = decimal(node, "checksRate");
        boolean reportAccepted = bool(node, "reportAccepted");
        boolean pass = statusPass(node)
            && targetVus >= 10_000
            && materialCards >= 5_000_000L
            && historicalOperations >= 100_000_000L
            && p95 > 0
            && p95 <= 3_000
            && errorRate >= 0
            && errorRate < 0.01
            && checksRate >= 0.99
            && reportAccepted;
        String message = "Load evidence: targetVus=" + targetVus
            + ", materialCards=" + materialCards
            + ", historicalOperations=" + historicalOperations
            + ", p95Ms=" + p95
            + ", errorRate=" + errorRate
            + ", checksRate=" + checksRate
            + ", reportAccepted=" + reportAccepted
            + ", file=" + evidence;
        return pass
            ? pass("operations.load-test", message)
            : failOrOverride("operations.load-test", message, "Run k6 enterprise profile 10k/5M/100M, accept the report and save evidence with ops/ha-load-dr/run-production-evidence.sh");
    }
    private ProductionPreflightCheck haCheck(JsonNode node, String evidence) {
        int backendReplicas = integer(node, "backendReplicas");
        int frontendReplicas = integer(node, "frontendReplicas");
        double availabilityTargetPercent = decimal(node, "availabilityTargetPercent");
        double measuredAvailabilityPercent = decimal(node, "measuredAvailabilityPercent");
        int availabilityWindowDays = integer(node, "availabilityWindowDays");
        double errorBudgetRemainingPercent = decimal(node, "errorBudgetRemainingPercent");
        boolean pass = statusPass(node)
            && backendReplicas >= 3
            && frontendReplicas >= 2
            && bool(node, "pdbVerified")
            && bool(node, "hpaVerified")
            && bool(node, "rollingUpdateZeroDowntime")
            && bool(node, "multiNodeOrZoneSpread")
            && bool(node, "failoverDrillPassed")
            && availabilityTargetPercent >= 99.9
            && measuredAvailabilityPercent >= 99.9
            && availabilityWindowDays >= 30
            && errorBudgetRemainingPercent >= 0
            && bool(node, "syntheticProbePassed");
        String message = "HA evidence: backendReplicas=" + backendReplicas
            + ", frontendReplicas=" + frontendReplicas
            + ", pdbVerified=" + bool(node, "pdbVerified")
            + ", hpaVerified=" + bool(node, "hpaVerified")
            + ", zeroDowntime=" + bool(node, "rollingUpdateZeroDowntime")
            + ", spread=" + bool(node, "multiNodeOrZoneSpread")
            + ", failoverDrill=" + bool(node, "failoverDrillPassed")
            + ", availabilityTargetPercent=" + availabilityTargetPercent
            + ", measuredAvailabilityPercent=" + measuredAvailabilityPercent
            + ", availabilityWindowDays=" + availabilityWindowDays
            + ", errorBudgetRemainingPercent=" + errorBudgetRemainingPercent
            + ", syntheticProbePassed=" + bool(node, "syntheticProbePassed")
            + ", file=" + evidence;
        return pass
            ? pass("operations.ha", message)
            : failOrOverride("operations.ha", message, "Run Kubernetes rollout, PDB/HPA, failover drill and collect 30-day 99.9% SLO evidence");
    }
    private ProductionPreflightCheck drCheck(JsonNode node, String evidence) {
        double backupAgeHours = decimal(node, "latestBackupAgeHours");
        int rpoMinutes = integer(node, "rpoMinutes");
        int rtoMinutes = integer(node, "rtoMinutes");
        boolean pass = statusPass(node)
            && bool(node, "backupFresh")
            && backupAgeHours >= 0
            && backupAgeHours <= 24
            && bool(node, "restoreDrillPassed")
            && bool(node, "pitrEnabled")
            && bool(node, "objectStorageReplication")
            && rpoMinutes > 0
            && rpoMinutes <= 15
            && rtoMinutes > 0
            && rtoMinutes <= 60;
        String message = "DR evidence: backupAgeHours=" + backupAgeHours
            + ", restoreDrill=" + bool(node, "restoreDrillPassed")
            + ", pitr=" + bool(node, "pitrEnabled")
            + ", objectStorageReplication=" + bool(node, "objectStorageReplication")
            + ", rpoMinutes=" + rpoMinutes
            + ", rtoMinutes=" + rtoMinutes
            + ", file=" + evidence;
        return pass
            ? pass("operations.dr", message)
            : failOrOverride("operations.dr", message, "Run backup freshness check and isolated restore drill; enable PITR and object-storage replication");
    }
    private ProductionPreflightCheck monitoringCheck(JsonNode node, String evidence) {
        int activeCriticalAlerts = integer(node, "activeCriticalAlerts");
        boolean pass = statusPass(node)
            && bool(node, "prometheusScrapeVerified")
            && bool(node, "alertRulesVerified")
            && bool(node, "dashboardReviewed")
            && bool(node, "onCallEscalationVerified")
            && bool(node, "runbookDrillPassed")
            && bool(node, "alertmanagerChecked")
            && activeCriticalAlerts == 0;
        String message = "Monitoring evidence: prometheusScrape=" + bool(node, "prometheusScrapeVerified")
            + ", alertRules=" + bool(node, "alertRulesVerified")
            + ", dashboardReviewed=" + bool(node, "dashboardReviewed")
            + ", onCallEscalation=" + bool(node, "onCallEscalationVerified")
            + ", runbookDrill=" + bool(node, "runbookDrillPassed")
            + ", alertmanagerChecked=" + bool(node, "alertmanagerChecked")
            + ", activeCriticalAlerts=" + activeCriticalAlerts
            + ", file=" + evidence;
        return pass
            ? pass("operations.monitoring", message)
            : failOrOverride("operations.monitoring", message, "Verify Prometheus scrape, alert rules, dashboards, Alertmanager, on-call escalation and monitoring runbook drill");
    }
    private ProductionPreflightCheck missing(String code, String message) {
        return failOrOverride(code, message, "Generate evidence with ops/ha-load-dr/run-production-evidence.sh");
    }
    private ProductionPreflightCheck pass(String code, String message) {
        return new ProductionPreflightCheck("OPERATIONS", code, "PASS", false, message, List.of());
    }
    private ProductionPreflightCheck failOrOverride(String code, String message, String action) {
        String property = switch (code) {
            case "operations.load-test" -> "PRODUCTION_LOAD_TEST_APPROVED";
            case "operations.ha" -> "PRODUCTION_HA_APPROVED";
            case "operations.monitoring" -> "PRODUCTION_MONITORING_APPROVED";
            case "operations.dr" -> "PRODUCTION_DR_APPROVED";
            default -> "";
        };
        if (truthy(environment.getProperty("ALLOW_MANUAL_PRODUCTION_GATE_OVERRIDE", System.getenv("ALLOW_MANUAL_PRODUCTION_GATE_OVERRIDE")))
            && truthy(environment.getProperty(property, System.getenv(property)))) {
            return new ProductionPreflightCheck("OPERATIONS", code, "WARN", false, message + "; manual override " + property + " is set", List.of(action));
        }
        return new ProductionPreflightCheck("OPERATIONS", code, "FAIL", true, message, List.of(action));
    }
    private Path evidencePath() {
        String configured = environment.getProperty("HA_LOAD_DR_EVIDENCE_FILE", System.getenv("HA_LOAD_DR_EVIDENCE_FILE"));
        if (!blank(configured)) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path local = Path.of(DEFAULT_EVIDENCE_FILE).toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path repositoryRoot = Path.of("..", DEFAULT_EVIDENCE_FILE).toAbsolutePath().normalize();
        return Files.isRegularFile(repositoryRoot) ? repositoryRoot : local;
    }
    private boolean statusPass(JsonNode node) {
        String status = text(node, "status").toUpperCase(Locale.ROOT);
        return "PASS".equals(status) || "PASSED".equals(status) || "OK".equals(status);
    }
    private boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return truthy(value.asText(""));
    }
    private int integer(JsonNode node, String field) {
        return (int) longValue(node, field);
    }
    private long longValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText("0").replace("_", "").replace(" ", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
    private double decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText("0").replace(",", ".").trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }
    private boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

