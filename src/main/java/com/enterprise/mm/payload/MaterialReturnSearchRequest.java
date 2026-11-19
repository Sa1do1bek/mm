package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MaterialReturnSearchRequest(
    String search,
    List<String> status,
    UUID issueRequestId,
    UUID warehouseId,
    LocalDate dateFrom,
    LocalDate dateTo
) {
}
