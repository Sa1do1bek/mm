package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.error.BadRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NonMovingStockCommissionServiceTest {
    @Mock
    private CommissionService commissionService;

    @Test
    void acceptsActiveNonMovingStockCommission() {
        UUID commissionId = UUID.randomUUID();
        Commission commission = commission(commissionId, CommissionType.NON_MOVING_STOCK, true);
        when(commissionService.findEntity(commissionId)).thenReturn(commission);

        NonMovingStockCommissionService service = new NonMovingStockCommissionService(commissionService);

        assertThat(service.requireNonMovingStockCommission(commissionId)).isSameAs(commission);
    }

    @Test
    void rejectsWrongCommissionType() {
        UUID commissionId = UUID.randomUUID();
        when(commissionService.findEntity(commissionId)).thenReturn(commission(commissionId, CommissionType.TENDER, true));

        NonMovingStockCommissionService service = new NonMovingStockCommissionService(commissionService);

        assertThatThrownBy(() -> service.requireNonMovingStockCommission(commissionId))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("NON_MOVING_STOCK");
    }

    @Test
    void rejectsInactiveCommission() {
        UUID commissionId = UUID.randomUUID();
        when(commissionService.findEntity(commissionId)).thenReturn(commission(commissionId, CommissionType.NON_MOVING_STOCK, false));

        NonMovingStockCommissionService service = new NonMovingStockCommissionService(commissionService);

        assertThatThrownBy(() -> service.requireNonMovingStockCommission(commissionId))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("not active");
    }

    private Commission commission(UUID id, CommissionType type, boolean active) {
        Commission commission = new Commission();
        commission.setId(id);
        commission.setType(type);
        commission.setActive(active);
        return commission;
    }
}
