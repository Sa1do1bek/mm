package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.AuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogMapperTest {
    private final AuditLogMapper mapper = new AuditLogMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void mapsStoredJsonStringsAsJsonNodes() {
        AuditLog log = new AuditLog();
        log.setId(UUID.randomUUID());
        log.setOldSnapshot("{\"status\":\"DRAFT\"}");
        log.setNewSnapshot("{\"status\":\"APPROVED\"}");
        log.setDiff("{\"status\":{\"old\":\"DRAFT\",\"new\":\"APPROVED\"}}");

        var response = mapper.toResponse(log);

        assertThat(response.getOldSnapshot().get("status").asText()).isEqualTo("DRAFT");
        assertThat(response.getNewSnapshot().get("status").asText()).isEqualTo("APPROVED");
        assertThat(response.getDiff().get("status").get("new").asText()).isEqualTo("APPROVED");
    }
}
