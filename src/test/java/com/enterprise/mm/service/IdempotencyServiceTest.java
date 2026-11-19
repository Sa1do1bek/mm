package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.IdempotencyRecord;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {
    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final AtomicReference<IdempotencyRecord> stored = new AtomicReference<>();
    private final IdempotencyService service = new IdempotencyService(repository, new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("idempotency-test");
        CompanyContext.setCompany(UUID.randomUUID());
        when(repository.findByTenantIdAndCompanyIdAndOperationAndResourceIdAndIdempotencyKeyAndIsDeletedFalse(
            anyString(), any(UUID.class), anyString(), any(UUID.class), anyString()
        )).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(IdempotencyRecord.class))).thenAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);
            stored.set(record);
            return record;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void repeatedKeyReturnsStoredResponseWithoutRepeatingAction() {
        UUID resourceId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        SampleResponse first = service.execute(
            "ISSUE",
            resourceId,
            "same-key",
            new SampleRequest("payload"),
            SampleResponse.class,
            () -> new SampleResponse("result-" + calls.incrementAndGet())
        );
        SampleResponse replay = service.execute(
            "ISSUE",
            resourceId,
            "same-key",
            new SampleRequest("payload"),
            SampleResponse.class,
            () -> new SampleResponse("result-" + calls.incrementAndGet())
        );

        assertThat(first).isEqualTo(new SampleResponse("result-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void repeatedKeyRejectsDifferentPayload() {
        UUID resourceId = UUID.randomUUID();
        service.execute(
            "ISSUE",
            resourceId,
            "same-key",
            new SampleRequest("first"),
            SampleResponse.class,
            () -> new SampleResponse("stored")
        );

        assertThatThrownBy(() -> service.execute(
            "ISSUE",
            resourceId,
            "same-key",
            new SampleRequest("different"),
            SampleResponse.class,
            () -> new SampleResponse("must-not-run")
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("different request payload");
    }

    @Test
    void requiredKeyRejectsBlankBeforeRunningAction() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.executeRequired(
            "WAREHOUSE_POST", UUID.randomUUID(), "  ", new SampleRequest("payload"),
            SampleResponse.class, () -> {
                calls.incrementAndGet();
                return new SampleResponse("must-not-run");
            }
        )).isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThat(calls).hasValue(0);
    }

    @Test
    void requiredKeyReplayReturnsReceiptBeforeActionChecksStaleVersion() {
        UUID resourceId = UUID.randomUUID();
        SampleRequest request = new SampleRequest("payload");
        SampleResponse first = service.executeRequired(
            "WAREHOUSE_POST", resourceId, "post-123", request, SampleResponse.class,
            () -> new SampleResponse("receipt-123")
        );

        SampleResponse replay = service.executeRequired(
            "WAREHOUSE_POST", resourceId, "post-123", request, SampleResponse.class,
            () -> { throw new ConflictException("stale document version"); }
        );

        assertThat(replay).isEqualTo(first);
    }

    private record SampleRequest(String value) {
    }

    private record SampleResponse(String value) {
    }
}
