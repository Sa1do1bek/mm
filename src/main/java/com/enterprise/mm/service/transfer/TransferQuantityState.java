package com.enterprise.mm.service.transfer;

import java.math.BigDecimal;

/** Quantities retain their unit; reporting an exception never clears company-owned transit. */
public record TransferQuantityState(BigDecimal planned, BigDecimal dispatched, BigDecimal cancelled,
    BigDecimal received, BigDecimal returnedFromTransit, BigDecimal postedTransitLoss) {
    public TransferQuantityState {
        for (BigDecimal value : new BigDecimal[] {planned, dispatched, cancelled, received, returnedFromTransit, postedTransitLoss}) {
            if (value == null || value.signum() < 0) throw new IllegalArgumentException("Transfer quantities must be nonnegative");
        }
        if (dispatched.add(cancelled).compareTo(planned) > 0)
            throw new IllegalArgumentException("Dispatched and cancelled quantities exceed the plan");
        if (received.add(returnedFromTransit).add(postedTransitLoss).compareTo(dispatched) > 0)
            throw new IllegalArgumentException("Clearances exceed dispatched quantity");
    }
    public BigDecimal stillToDispatch() { return planned.subtract(dispatched).subtract(cancelled); }
    public BigDecimal unresolvedTransit() { return dispatched.subtract(received).subtract(returnedFromTransit).subtract(postedTransitLoss); }
}
