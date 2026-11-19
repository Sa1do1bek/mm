package com.enterprise.mm.payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;
import java.time.LocalDateTime;

@Getter
@Setter
public class MaterialMasterRequestResponse extends BaseRecordDto {
    private String requestType;
    private UUID materialId;
    private UUID targetMaterialId;
    private String proposedName;
    private UUID materialGroupId;
    private UUID uomId;
    private String mxikCode;
    private String tnvedCode;
    private String requestedBy;
    private String lastActionComment;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime decidedAt;
    private LocalDateTime appliedAt;
}
