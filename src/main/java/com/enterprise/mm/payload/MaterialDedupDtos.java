package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MaterialDedupDtos {
    private MaterialDedupDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterialDedupResult(
        MaterialDedupQuery query,
        List<MaterialDedupMatch> matches,
        List<Object> findings,
        Map<String, Object> stats,
        String thresholdUsed,
        Boolean llmUsed,
        String exportFileName
    ) {
        public MaterialDedupResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
            findings = findings == null ? List.of() : List.copyOf(findings);
            stats = stats == null ? Map.of() : Map.copyOf(stats);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterialDedupQuery(
        UUID materialId,
        String name,
        String domain,
        Boolean domainAmbiguous
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterialDedupMatch(
        UUID materialId,
        UUID sourceMaterialId,
        UUID queryMaterialId,
        UUID candidateMaterialId,
        String candidateMaterialName,
        String candidateMaterialCode,
        String candidateDomain,
        String candidateMaterialGroupName,
        String candidateUnitOfMeasureCode,
        String candidateManufacturer,
        String similarityScore,
        String similarityPercent,
        String verdict,
        String reason,
        List<MaterialDedupSignal> signals,
        String lexicalScore,
        String semanticScore
    ) {
        public MaterialDedupMatch {
            signals = signals == null ? List.of() : List.copyOf(signals);
        }

        public UUID resolvedSourceMaterialId(UUID queryMaterialId) {
            if (materialId != null) {
                return materialId;
            }
            if (sourceMaterialId != null) {
                return sourceMaterialId;
            }
            if (queryMaterialId != null) {
                return queryMaterialId;
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterialDedupSignal(
        String kind,
        String detail,
        String contribution,
        String weight
    ) {
    }
}
