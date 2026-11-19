package com.enterprise.mm.common;

import com.enterprise.mm.error.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Shared data-quality gates for operational APIs (UX-LIVE-09).
 */
public final class DataQualityGuards {
    private static final LocalDate MIN_OPERATIONAL_DATE = LocalDate.of(1990, 1, 1);

    private DataQualityGuards() {
    }

    public static LocalDate requireSaneDate(LocalDate value, String fieldName) {
        if (value == null) {
            return null;
        }
        LocalDate max = LocalDate.now().plusYears(30);
        if (value.isBefore(MIN_OPERATIONAL_DATE) || value.isAfter(max)) {
            throw new BusinessException(
                fieldName + " is outside the allowed operational date range",
                "dataQuality.date.impossible",
                fieldName,
                value);
        }
        return value;
    }

    public static void requireDateOrder(LocalDate from, LocalDate to, String fromField, String toField) {
        if (from == null || to == null) {
            return;
        }
        if (to.isBefore(from)) {
            throw new BusinessException(
                toField + " must be on or after " + fromField,
                "dataQuality.date.rangeInvalid",
                fromField,
                toField);
        }
    }

    public static void requireSameUom(String leftUom, String rightUom, String context) {
        String left = normalizeUom(leftUom);
        String right = normalizeUom(rightUom);
        if (left == null || right == null) {
            return;
        }
        if (!left.equals(right)) {
            throw new BusinessException(
                "Incompatible UOMs in " + context + ": " + left + " vs " + right,
                "dataQuality.uom.incompatible",
                left,
                right);
        }
    }

    public static boolean sameUomSet(Set<String> uoms) {
        if (uoms == null || uoms.isEmpty()) {
            return true;
        }
        return uoms.stream().map(DataQualityGuards::normalizeUom).filter(Objects::nonNull).distinct().count() <= 1;
    }

    public static BigDecimal roundMoney(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            return null;
        }
        int scale = moneyScale(currencyCode);
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }

    public static int moneyScale(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return 2;
        }
        try {
            return Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            return 2;
        }
    }

    private static String normalizeUom(String uom) {
        return uom == null || uom.isBlank() ? null : uom.trim().toUpperCase(Locale.ROOT);
    }
}
