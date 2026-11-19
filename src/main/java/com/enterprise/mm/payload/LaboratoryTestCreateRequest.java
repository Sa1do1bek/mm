package com.enterprise.mm.payload;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LaboratoryTestCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 50)
    private String status;
    private String description;
    private UUID qualityInspectionId;
    private UUID materialId;
    private String sampleNumber;
    private String testType;
    private String testMethod;
    private String specification;
    private String measuredValue;
    private String result;
    private boolean passed;
    private LocalDateTime requestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private UUID labTechnicianEmployeeId;
    private UUID protocolAttachmentId;
    private String limsSampleId;
    private String limsStatus;
    private LocalDateTime limsRequestedAt;
    private LocalDateTime limsResultReceivedAt;
}
