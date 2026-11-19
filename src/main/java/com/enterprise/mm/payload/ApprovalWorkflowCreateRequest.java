package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApprovalWorkflowCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    @Size(max = 80)
    private String moduleKey;
    @Size(max = 20)
    private String requestType;
    private String entityType;
    private UUID entityId;
    private Integer currentStep;
}
