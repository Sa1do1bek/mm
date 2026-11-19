package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class ErpDeliveryWorkerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final UUID ref = UUID.randomUUID();
    private String ack(String event, String status, String posted, String document) {
        return "{\"protocolVersion\":1,\"eventId\":\"" + event + "\",\"status\":\"" + status + "\",\"posted\":" + posted + ",\"documentRef\":\"" + document + "\"}";
    }
    @Test void acceptsOnlyMatchingConsistentAcknowledgment() throws Exception {
        var parsed = ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(id.toString(), "POSTED", "true", ref.toString())), id);
        assertThat(parsed.documentRef()).isEqualTo(ref);
        assertThat(parsed.posted()).isTrue();
        assertThatThrownBy(() -> ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(UUID.randomUUID().toString(), "POSTED", "true", ref.toString())), id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(id.toString(), "POSTED", "false", ref.toString())), id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(id.toString(), "POSTED", "true", "bad")), id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ErpDeliveryWorker.parseAcknowledgment(mapper.readTree("{}"), id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(id.toString(), "POSTED", "true", "00000000-0000-0000-0000-000000000000")), id)).isInstanceOf(IllegalStateException.class);
        assertThat(ErpDeliveryWorker.parseAcknowledgment(mapper.readTree(ack(id.toString(), "AWAITING_CONFIRMATION", "false", ref.toString())), id).posted()).isFalse();
    }
    @Test void exposesOnlySanitizedBoundedGatewayErrors() throws Exception {
        assertThat(ErpDeliveryWorker.httpError(mapper,409,"{\"error\":\"Invalid quantity\\nline 2\"}"))
            .isEqualTo("HTTP 409: Invalid quantity line 2");
        assertThat(ErpDeliveryWorker.httpError(mapper,503,"not json")).isEqualTo("HTTP 503");
        String body=mapper.createObjectNode().put("error","x".repeat(600)).toString();
        assertThat(ErpDeliveryWorker.httpError(mapper,409,body)).hasSize(500).startsWith("HTTP 409: ");
    }
}
