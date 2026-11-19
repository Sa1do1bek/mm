package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WarehouseResponse extends BaseRecordDto {
    private java.util.Map<String,Object> sourceData;
    private java.util.UUID parentId;
    private boolean sourceGroup;
    private boolean sourceHasBalance;
    private String address;
    private List<UUID> responsibleEmployeeIds;
}
