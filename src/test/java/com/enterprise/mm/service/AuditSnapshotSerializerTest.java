package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditSnapshotSerializerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AuditSnapshotSerializer serializer = new AuditSnapshotSerializer(objectMapper);

    @Test
    void diffMarksAllNewFieldsWhenOldSnapshotIsMissing() throws Exception {
        SampleEntity entity = new SampleEntity(UUID.randomUUID(), "MAT-1", "Draft", 0L);

        JsonNode diff = objectMapper.readTree(serializer.diff(null, entity));

        assertThat(diff.get("code").get("old").isNull()).isTrue();
        assertThat(diff.get("code").get("new").asText()).isEqualTo("MAT-1");
    }

    @Test
    void diffKeepsOnlyChangedFieldsForUpdates() throws Exception {
        UUID id = UUID.randomUUID();
        SampleEntity oldEntity = new SampleEntity(id, "MAT-1", "Draft", 1L);
        SampleEntity newEntity = new SampleEntity(id, "MAT-1", "Active", 2L);

        JsonNode diff = objectMapper.readTree(serializer.diff(oldEntity, newEntity));

        assertThat(diff.has("status")).isTrue();
        assertThat(diff.get("status").get("old").asText()).isEqualTo("Draft");
        assertThat(diff.get("status").get("new").asText()).isEqualTo("Active");
        assertThat(diff.has("code")).isFalse();
    }

    @Test
    void snapshotsRedactCredentialAndAuthorizationVariantsWithAConstantMarker() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("password", "plain-secret");
        value.put("password_hash", "$2a$hash");
        value.put("oldPassword", null);
        value.put("newPassword", "another-secret");
        value.put("confirm_new_password", "another-secret");
        value.put("token", "jwt-value");
        value.put("accessToken", "access-value");
        value.put("refresh_token", "refresh-value");
        value.put("authorization", "Bearer value");
        value.put("authorizationHeader", "Bearer value");
        value.put("safeField", "visible");

        JsonNode snapshot = objectMapper.readTree(serializer.toJson(value));

        for (String field : value.keySet()) {
            if (!field.equals("safeField")) {
                assertThat(snapshot.get(field).asText()).isEqualTo("[REDACTED]");
            }
        }
        assertThat(snapshot.get("safeField").asText()).isEqualTo("visible");
        assertThat(snapshot.toString())
            .doesNotContain("plain-secret", "$2a$hash", "jwt-value", "Bearer value");
    }

    @Test
    void diffsNeverRevealChangedPasswordHashOrItsLength() {
        Map<String, Object> before = Map.of(
            "passwordHash", "short-hash",
            "status", "ACTIVE"
        );
        Map<String, Object> after = Map.of(
            "passwordHash", "a-much-longer-password-hash",
            "status", "DISABLED"
        );

        String diff = serializer.diff(before, after);

        assertThat(diff).doesNotContain("short-hash", "longer-password-hash", "passwordHash");
        assertThat(diff).contains("\"status\"");
    }

    @Test
    void beanSnapshotsRedactSecretsEvenWhenTheSecretValueIsNull() throws Exception {
        JsonNode snapshot = objectMapper.readTree(serializer.toJson(
            new SecretBean(null, "Bearer secret", "safe")
        ));

        assertThat(snapshot.get("passwordHash").asText()).isEqualTo("[REDACTED]");
        assertThat(snapshot.get("authorizationHeader").asText()).isEqualTo("[REDACTED]");
        assertThat(snapshot.get("description").asText()).isEqualTo("safe");
    }

    record SampleEntity(UUID id, String code, String status, Long version) {
        public UUID getId() {
            return id;
        }

        public String getCode() {
            return code;
        }

        public String getStatus() {
            return status;
        }

        public Long getVersion() {
            return version;
        }
    }

    record SecretBean(String passwordHash, String authorizationHeader, String description) {
        public String getPasswordHash() {
            return passwordHash;
        }

        public String getAuthorizationHeader() {
            return authorizationHeader;
        }

        public String getDescription() {
            return description;
        }
    }
}
