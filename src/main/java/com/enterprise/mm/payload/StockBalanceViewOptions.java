package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/** Read/export controls; expiry day bounds include both today and the specified final day. */
@Getter @Setter
public class StockBalanceViewOptions {
    private Integer expiresWithinDays;
    private boolean includeExpired;
    private Boolean positiveOnly;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate expiryDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) private LocalDate expiryDateTo;
    private BigDecimal quantityMin;
    private BigDecimal quantityMax;
    private BigDecimal availableQuantityMin;
    private BigDecimal availableQuantityMax;
    private BigDecimal totalValueMin;
    private BigDecimal totalValueMax;
    private Boolean hasReserved;
    private Boolean hasQuarantine;
    private UUID unitOfMeasureId;
    private String sortBy;
    private String sortDir;
}
