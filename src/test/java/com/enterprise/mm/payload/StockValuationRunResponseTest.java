package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockValuationRunResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializationDoesNotExposeDeletedFlag() throws Exception {
        StockValuationRunResponse response = new StockValuationRunResponse();
        response.setId(UUID.randomUUID());
        response.setStatus("DRAFT");
        response.setDeleted(true);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("deleted");
        assertThat(json).contains("\"status\":\"DRAFT\"");
    }
}
