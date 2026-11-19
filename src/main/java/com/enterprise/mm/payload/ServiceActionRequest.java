package com.enterprise.mm.payload;

import java.math.BigDecimal;

public record ServiceActionRequest(
    String comment,
    String actor,
    BigDecimal amount
) {
}
