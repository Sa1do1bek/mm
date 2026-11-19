package com.enterprise.mm.service;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
class ProductionAllocationTest {
    @Test void quantityOnlyDraftsRetainLegacyIdempotencySerialization() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var input = new com.enterprise.mm.warehouse.production.ProductionOperationDtos.Input(null, null, BigDecimal.ONE);
        var output = new com.enterprise.mm.warehouse.production.ProductionOperationDtos.Output(null, BigDecimal.ONE, BigDecimal.ONE, null, "OUTPUT");
        assertThat(mapper.writeValueAsString(input)).isEqualTo("{\"materialId\":null,\"stockPositionId\":null,\"quantity\":1}");
        assertThat(mapper.writeValueAsString(output)).isEqualTo("{\"materialId\":null,\"quantity\":1,\"costWeight\":1,\"batchNumber\":null,\"lineType\":\"OUTPUT\"}");
    }
    @Test void exactRemainderAndZeroWeightScrapArePreserved() {
        var allocated=ProductionOperationService.allocate(new BigDecimal("1"),List.of(BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO));
        assertThat(allocated).containsExactly(new BigDecimal("0.3333"),new BigDecimal("0.3333"),new BigDecimal("0.3334"),new BigDecimal("0.0000"));
        assertThat(allocated.stream().reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1");
    }
    @Test void invalidWeightsAreRejected() {
        assertThatThrownBy(()->ProductionOperationService.allocate(BigDecimal.ONE,List.of(BigDecimal.ZERO))).hasMessageContaining("positive");
        assertThatThrownBy(()->ProductionOperationService.allocate(BigDecimal.ONE,List.of(new BigDecimal("-1")))).hasMessageContaining("nonnegative");
    }
}
