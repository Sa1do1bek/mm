package com.enterprise.mm.service;

import java.math.BigDecimal;

record ContractAiMetrics(
    BigDecimal limitAmount,
    BigDecimal usedAmount,
    BigDecimal utilizationPercent,
    BigDecimal totalInvoices,
    BigDecimal totalPayments,
    BigDecimal unpaidAmount,
    int overdueInvoices,
    int expiredPowersOfAttorney,
    int overdueSchedules
) {
}

