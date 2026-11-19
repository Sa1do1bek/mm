package com.enterprise.mm.warehouse.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingCommandFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requiresAStableIdempotencyKeyForEveryNewCommand() throws Exception {
        assertThatThrownBy(() -> command(" ", "{\"quantity\":3}"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency");
        assertThat(command("  issue-123  ", "{\"quantity\":3}").idempotencyKey())
            .isEqualTo("issue-123");
    }

    @Test
    void hashesNestedObjectsCanonicallyButPreservesArrayOrder() throws Exception {
        var first = command("issue-123", "{\"b\":2,\"a\":{\"y\":9,\"x\":1},\"lines\":[1,2]}");
        var reordered = command("issue-123", "{\"lines\":[1,2],\"a\":{\"x\":1,\"y\":9},\"b\":2}");
        var different = command("issue-123", "{\"b\":2,\"a\":{\"y\":9,\"x\":1},\"lines\":[2,1]}");

        assertThat(PostingCommandFingerprint.sha256(first)).isEqualTo(PostingCommandFingerprint.sha256(reordered));
        assertThat(PostingCommandFingerprint.sha256(first)).isNotEqualTo(PostingCommandFingerprint.sha256(different));
    }

    @Test
    void postingDateAndExpectedVersionArePartOfThePayloadIdentity() throws Exception {
        var first = command("issue-123", "{\"quantity\":3}");
        var changedDate = new PostingCommand(first.book(), first.operation(), first.contextKey(), first.resourceId(),
            first.idempotencyKey(), first.postingDate().plusDays(1), first.expectedVersion(), first.payload());
        var changedVersion = new PostingCommand(first.book(), first.operation(), first.contextKey(), first.resourceId(),
            first.idempotencyKey(), first.postingDate(), first.expectedVersion() + 1, first.payload());

        assertThat(PostingCommandFingerprint.sha256(first)).isNotEqualTo(PostingCommandFingerprint.sha256(changedDate));
        assertThat(PostingCommandFingerprint.sha256(first)).isNotEqualTo(PostingCommandFingerprint.sha256(changedVersion));
    }

    private PostingCommand command(String key, String payload) throws Exception {
        return new PostingCommand("WAREHOUSE", "ISSUE", "OWNED", UUID.fromString("4fd022be-e352-4c3d-b8bb-9f5590c23bad"),
            key, LocalDate.of(2026, 10, 14), 7L, mapper.readTree(payload));
    }
}
