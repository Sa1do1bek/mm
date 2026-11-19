package com.enterprise.mm.payload;

import com.enterprise.mm.enums.IssueRequestCostObjectType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueRequestResponse extends BaseRecordDto {
    private UUID warehouseId;
    private String warehouseName;
    private UUID requesterUserId;
    private String requesterUserName;
    private UUID receiverUserId;
    private String receiverUserName;
    private IssueRequestCostObjectType costObjectType;
    private UUID costObjectId;
    private String costObjectName;
    private LocalDate requiredDate;
    private LocalDate issuedDate;
    private String purpose;
    private String approvalComment;
    private UUID stockMovementDocumentId;
    private List<IssueRequestLineResponse> lines;
    private List<String> allowedActions;
}
