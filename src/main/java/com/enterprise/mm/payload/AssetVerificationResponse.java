package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetVerificationResponse extends BaseRecordDto {
    private UUID warehouseId;
    private String warehouseName;
    private UUID departmentId;
    private String departmentName;
    private String verificationType;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<AssetVerificationLineResponse> lines = List.of();
}
