package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseOrderRevisionDto extends BaseRecordDto {
    private UUID purchaseOrderId;
    private Integer revisionNumber;
    private String reason;
    private String diffJson;
    private String snapshotJson;
    private LocalDateTime revisedAt;
}