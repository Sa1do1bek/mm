package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetHandoverResponse extends BaseRecordDto {
    private UUID contractId;
    private String handoverType;
    private String fromCustodyType;
    private UUID fromEmployeeId;
    private String fromEmployeeName;
    private UUID fromDepartmentId;
    private String fromDepartmentName;
    private UUID fromWarehouseId;
    private String fromWarehouseName;
    private String toCustodyType;
    private UUID toEmployeeId;
    private String toEmployeeName;
    private UUID toDepartmentId;
    private String toDepartmentName;
    private UUID toWarehouseId;
    private String toWarehouseName;
    private String status;
    private UUID approvalInstanceId;
    private LocalDateTime approvedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime acknowledgedAt;
    private String exceptionStatus;
    private LocalDateTime closedAt;
    private List<AssetHandoverLineResponse> lines = List.of();
}
