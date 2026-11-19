package com.enterprise.mm.payload;

import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderCommissionResponse extends BaseRecordDto {
    private UUID rfqId;
    private UUID commissionId;
    private Integer quorumRequired;
    private boolean active;
    private List<TenderCommissionMemberSettingResponse> members;
}
