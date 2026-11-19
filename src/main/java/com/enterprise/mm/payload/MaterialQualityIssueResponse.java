package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.LocalDateTime;

@Getter
@Setter
public class MaterialQualityIssueResponse extends BaseRecordDto {
    private UUID materialId;
    private String issueType;
    private String severity;
    private String fieldName;
    private String assignedTo;
    private String resolutionComment;
    private String evidenceReference;
    private LocalDateTime assignedAt;
    private LocalDateTime startedAt;
    private LocalDateTime resolvedAt;
}
