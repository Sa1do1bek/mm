package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalStepResponse extends BaseRecordDto {
    private UUID approvalWorkflowId;
    private Integer stepNumber;
    private String approverRole;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String decision;
    private String commentText;
}