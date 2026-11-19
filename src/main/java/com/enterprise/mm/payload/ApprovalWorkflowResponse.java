package com.enterprise.mm.payload;

import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalWorkflowResponse extends BaseRecordDto {
    private String moduleKey;
    private String requestType;
    private String entityType;
    private UUID entityId;
    private Integer currentStep;
}
