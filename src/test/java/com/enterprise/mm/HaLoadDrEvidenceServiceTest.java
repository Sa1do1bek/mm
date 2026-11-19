package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.service.HaLoadDrEvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HaLoadDrEvidenceServiceTest {
    @Test
    void validEvidencePassesLoadHaAndDrGates() throws Exception {
        Path file = Files.createTempFile("ha-load-dr-evidence", ".json");
        Files.writeString(file, """
            {
              "loadTest": {
                "status": "PASS",
                "targetVus": 10000,
                "materialCards": 5000000,
                "historicalOperations": 100000000,
                "p95Ms": 2500,
                "errorRate": 0.005,
                "checksRate": 0.995,
                "reportAccepted": true
              },
              "ha": {
                "status": "PASS",
                "backendReplicas": 3,
                "frontendReplicas": 2,
                "pdbVerified": true,
                "hpaVerified": true,
                "rollingUpdateZeroDowntime": true,
                "multiNodeOrZoneSpread": true,
                "failoverDrillPassed": true,
                "availabilityTargetPercent": 99.9,
                "measuredAvailabilityPercent": 99.95,
                "availabilityWindowDays": 30,
                "errorBudgetRemainingPercent": 50,
                "syntheticProbePassed": true
              },
              "monitoring": {
                "status": "PASS",
                "prometheusScrapeVerified": true,
                "alertRulesVerified": true,
                "dashboardReviewed": true,
                "onCallEscalationVerified": true,
                "runbookDrillPassed": true,
                "alertmanagerChecked": true,
                "activeCriticalAlerts": 0
              },
              "dr": {
                "status": "PASS",
                "backupFresh": true,
                "latestBackupAgeHours": 2,
                "restoreDrillPassed": true,
                "pitrEnabled": true,
                "objectStorageReplication": true,
                "rpoMinutes": 15,
                "rtoMinutes": 60
              }
            }
            """);

        var env = new MockEnvironment().withProperty("HA_LOAD_DR_EVIDENCE_FILE", file.toString());
        var checks = new HaLoadDrEvidenceService(env, new ObjectMapper()).checks();

        assertThat(checks).hasSize(4);
        assertThat(checks).allSatisfy(check -> {
            assertThat(check.status()).isEqualTo("PASS");
            assertThat(check.blocksProduction()).isFalse();
        });
    }

    @Test
    void missingEvidenceBlocksProduction() {
        var env = new MockEnvironment().withProperty("HA_LOAD_DR_EVIDENCE_FILE", "/tmp/mm-missing-ha-load-dr-evidence.json");
        var checks = new HaLoadDrEvidenceService(env, new ObjectMapper()).checks();

        assertThat(checks).hasSize(4);
        assertThat(checks).allSatisfy(check -> {
            assertThat(check.status()).isEqualTo("FAIL");
            assertThat(check.blocksProduction()).isTrue();
        });
    }
}
