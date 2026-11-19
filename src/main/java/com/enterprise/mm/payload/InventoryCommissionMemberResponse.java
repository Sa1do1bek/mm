package com.enterprise.mm.payload;

import com.enterprise.mm.enums.CommissionMemberRole;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InventoryCommissionMemberResponse {
    private UUID id;
    private UUID userId;
    private UUID employeeId;
    private String name;
    private String memberName;
    private CommissionMemberRole role;
    private boolean active;
    private boolean showSystemQuantityToCounter;
}
