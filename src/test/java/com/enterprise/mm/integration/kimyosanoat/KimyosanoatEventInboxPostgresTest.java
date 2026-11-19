package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class KimyosanoatEventInboxPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static KimyosanoatEventInbox inbox;

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V86__kimyosanoat_event_inbox.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V98__kimyosanoat_event_processing.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        inbox = new KimyosanoatEventInbox(jdbc, new ObjectMapper().findAndRegisterModules(), "default",
            "00000000-0000-0000-0000-000000000001");
    }

    @Test void savesOnceAndRejectsConflictingReuse() {
        UUID id = UUID.randomUUID();
        var event = new KimyosanoatEventInbox.Event(id, 123L, UUID.randomUUID(), "EXIT_ACCEPTED",
            OffsetDateTime.parse("2026-11-18T10:30:00+05:00"));
        assertThat(inbox.accept(event).duplicate()).isFalse();
        assertThat(inbox.accept(event).duplicate()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, id))
            .isEqualTo("RECEIVED");
        assertThatThrownBy(() -> inbox.accept(new KimyosanoatEventInbox.Event(id, 123L, UUID.randomUUID(), "EXIT_ACCEPTED", event.occurredAt())))
            .isInstanceOf(ConflictException.class);
    }

    @Test void rejectsUnsupportedEventsWithoutPersisting() {
        var event = new KimyosanoatEventInbox.Event(UUID.randomUUID(), 1L, UUID.randomUUID(), "EXIT_REQUESTED",
            OffsetDateTime.parse("2026-11-18T10:30:00+05:00"));
        assertThatThrownBy(() -> inbox.accept(event)).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?", Integer.class, event.eventId()))
            .isZero();
    }

    @Test void reviewQueueIsScopedAndCanBeRetried() {
        UUID id = UUID.randomUUID();
        inbox.accept(new KimyosanoatEventInbox.Event(id, 991L, UUID.randomUUID(), "EXIT_ACCEPTED",
            OffsetDateTime.parse("2026-11-18T10:30:00+05:00")));
        jdbc.update("update kimyosanoat_event_inbox set processing_state='REVIEW_REQUIRED', review_reason='STOCK_INSUFFICIENT' where event_id=?", id);
        assertThat(inbox.reviewQueue()).anySatisfy(item -> {
            assertThat(item.eventId()).isEqualTo(id);
            assertThat(item.reason()).isEqualTo("STOCK_INSUFFICIENT");
        });
        inbox.retry(id);
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, id))
            .isEqualTo("RECEIVED");
    }
}
