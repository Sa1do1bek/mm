package com.enterprise.mm.payload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** UX-LIVE-01: ApiResponse.error can carry structured error context in `data`. */
class ApiResponseTest {

    @Test
    void errorWithoutDataLeavesDataNull() {
        ApiResponse<Void> response = ApiResponse.error("Something failed", "SOME_ERROR");

        assertThat(response.data()).isNull();
        assertThat(response.message()).isEqualTo("Something failed");
        assertThat(response.errorCode()).isEqualTo("SOME_ERROR");
        assertThat(response.timestamp()).isNotNull();
    }

    @Test
    void errorWithDataSurfacesItForCallers() {
        Map<String, Object> context = Map.of("purchaseOrderCode", "PO-1");

        ApiResponse<Map<String, Object>> response = ApiResponse.error("Something failed", "SOME_ERROR", context);

        assertThat(response.data()).isEqualTo(context);
        assertThat(response.message()).isEqualTo("Something failed");
        assertThat(response.errorCode()).isEqualTo("SOME_ERROR");
    }
}
