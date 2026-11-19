package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalStepCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID approvalWorkflowId;
    private Integer stepNumber;
    private String approverRole;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String decision;
    private String commentText;
}
