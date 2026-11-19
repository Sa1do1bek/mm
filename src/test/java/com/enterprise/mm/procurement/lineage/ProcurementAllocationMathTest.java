package com.enterprise.mm.procurement.lineage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcurementAllocationMathTest {
    @Test
    void splitAndCancelledAllocationsLeaveOnlyTheActiveBalance() {
        BigDecimal remaining = ProcurementAllocationMath.remaining(
            new BigDecimal("100"),
            List.of(
                new ProcurementAllocationMath.Allocation(new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO),
                new ProcurementAllocationMath.Allocation(new BigDecimal("40"), new BigDecimal("10"), BigDecimal.ZERO)
            )
        );

        assertEquals(new BigDecimal("10"), remaining);
    }

    @Test
    void overAllocationIsRejectedBeforeACommandIsPersisted() {
        assertThrows(BusinessException.class, () -> ProcurementAllocationMath.requireAvailable(
            new BigDecimal("41"),
            new BigDecimal("100"),
            List.of(new ProcurementAllocationMath.Allocation(new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO))
        ));
    }

    @Test
    void negativeInputsAreRejected() {
        assertThrows(BusinessException.class, () -> ProcurementAllocationMath.remaining(
            new BigDecimal("100"),
            List.of(new ProcurementAllocationMath.Allocation(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO))
        ));
    }
}
