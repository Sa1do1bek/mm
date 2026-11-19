package com.enterprise.mm.service;

import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockCommissionService {
    private final CommissionService commissionService;

    @Transactional(readOnly = true)
    public Commission requireNonMovingStockCommission(UUID commissionId) {
        if (commissionId == null) {
            throw new BadRequestException("commissionId is required");
        }
        Commission commission = commissionService.findEntity(commissionId);
        if (commission.getType() != CommissionType.NON_MOVING_STOCK) {
            throw new BadRequestException("Non-moving stock process requires commission type NON_MOVING_STOCK");
        }
        if (!commission.isActive()) {
            throw new BadRequestException("Commission is not active: " + commissionId);
        }
        return commission;
    }
}
