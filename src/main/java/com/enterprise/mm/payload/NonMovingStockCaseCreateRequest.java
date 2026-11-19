package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NonMovingStockCaseCreateRequest {
    private List<UUID> ids;
    private UUID commissionId;
    private String commissionProtocolNo;
}
