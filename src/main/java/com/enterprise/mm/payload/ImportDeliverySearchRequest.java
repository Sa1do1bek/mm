package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ImportDeliverySearchRequest(
    String search,
    List<String> status,
    UUID supplierId,
    UUID purchaseOrderId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    boolean includeDeleted
) implements SearchCriteria {
    public static ImportDeliverySearchRequest empty() {
        return new ImportDeliverySearchRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            false
        );
    }
}
