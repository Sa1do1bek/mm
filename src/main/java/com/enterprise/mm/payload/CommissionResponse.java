package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommissionType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommissionResponse extends BaseRecordDto {
    private CommissionType type;
    private List<CommissionMemberResponse> members;
    private LocalDate validFrom;
    private LocalDate validTo;
    private boolean active;
    @io.swagger.v3.oas.annotations.media.Schema(description = "RFQ ids currently assigned to this TENDER commission via active tender-commission records.")
    private List<UUID> assignedRfqIds;
}
