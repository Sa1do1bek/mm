package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LaboratoryTestResponse extends BaseRecordDto {
    private UUID qualityInspectionId;
    private String qualityInspectionCode;
    private String qualityInspectionName;
    private UUID materialId;
    private String materialCode;
    private String materialName;
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