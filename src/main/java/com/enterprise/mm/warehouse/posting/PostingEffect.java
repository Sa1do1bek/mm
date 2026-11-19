package com.enterprise.mm.warehouse.posting;

import com.enterprise.mm.error.BadRequestException;
import java.math.BigDecimal;
import java.util.UUID;

/** One signed effect. A value-only source position can never be executable stock. */
public record PostingEffect(
    PostingEffectType type,
    UUID materialId,
    UUID warehouseId,
    String accountCode,
    String baseUnitCode,
    BigDecimal quantity,
    BigDecimal amount,
    String currencyCode,
    UUID storageLocationId,
    UUID storageBinId,
    UUID stockBatchId,
    UUID ownerId,
    String stockCategory,
    String disposition,
    String sourceReference
) {
    public PostingEffect(PostingEffectType type, UUID materialId, UUID warehouseId, String accountCode,
                         String baseUnitCode, BigDecimal quantity, BigDecimal amount, String currencyCode) {
        this(type, materialId, warehouseId, accountCode, baseUnitCode, quantity, amount, currencyCode,
            null, null, null, null, null, null, null);
    }

    public PostingEffect {
        if (type == null || materialId == null || quantity == null || amount == null) {
            throw new BadRequestException("Posting effect type, material, quantity and amount are required");
        }
        if (quantity.stripTrailingZeros().scale() > 7 || quantity.precision() - quantity.scale() > 15) {
            throw new BadRequestException("Posting quantity exceeds base-unit precision");
        }
        if (amount.stripTrailingZeros().scale() > 4 || amount.precision() - amount.scale() > 15) {
            throw new BadRequestException("Posting amount exceeds monetary precision");
        }
        if (type == PostingEffectType.STOCK) {
            if (warehouseId == null || blank(baseUnitCode) || quantity.signum() == 0 || amount.signum() != 0) {
                throw new BadRequestException("Physical stock effect requires a warehouse, base unit and nonzero quantity only");
            }
        } else if (type == PostingEffectType.VALUE_ONLY) {
            if (quantity.signum() != 0 || amount.signum() == 0 || !blank(baseUnitCode)
                || blank(accountCode) || blank(currencyCode)) {
                throw new BadRequestException("Value-only effect requires account, currency and amount without stock quantity");
            }
        } else {
            if (quantity.signum() == 0 && amount.signum() == 0) {
                throw new BadRequestException("Posting effect has no quantity or value");
            }
            if (type == PostingEffectType.ACCOUNT && blank(accountCode)) {
                throw new BadRequestException("Account effect requires an account code");
            }
            if (quantity.signum() != 0 && blank(baseUnitCode)) {
                throw new BadRequestException("Quantity effect requires a base unit");
            }
            if (amount.signum() != 0 && blank(currencyCode)) {
                throw new BadRequestException("Value effect requires a currency");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
