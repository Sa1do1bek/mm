package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class MaterialDuplicateCandidateResponse extends BaseRecordDto {
    private UUID materialId;
    private UUID candidateMaterialId;
    private BigDecimal similarityScore;
    private String reason;
    private UUID aiSourceJobId;
    private String aiEvidenceJson;
    private String decisionComment;
    private LocalDateTime decidedAt;
    private LocalDateTime mergedAt;
}
