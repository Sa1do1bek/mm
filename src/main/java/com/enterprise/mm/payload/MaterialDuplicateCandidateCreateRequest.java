package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class MaterialDuplicateCandidateCreateRequest {
    private String code;
    private String name;
    private String status;
    private String description;
    private UUID materialId;
    private UUID candidateMaterialId;
    private BigDecimal similarityScore;
    private String reason;
    private UUID aiSourceJobId;
    private String aiEvidenceJson;
}
