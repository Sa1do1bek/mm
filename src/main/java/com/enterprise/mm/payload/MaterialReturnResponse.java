package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialReturnResponse extends BaseRecordDto {
    private UUID issueRequestId;
    private String issueRequestCode;
    private String issueRequestName;
    private UUID warehouseId;
    private String warehouseName;
    private String reason;
    private String acceptanceStatus;
    private UUID stockMovementDocumentId;
    private String originType;
    private String historicalDocumentNumber;
    private java.time.LocalDate historicalDocumentDate;
    private String historicalSourceSystem;
    private String ownerId;
    private List<MaterialReturnLineResponse> lines;
    private List<String> allowedActions;
}
