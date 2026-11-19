package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class KimyosanoatEventWorkerPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static KimyosanoatEventInbox inbox;
    static DataSourceTransactionManager manager;
    KimyosanoatClient client;
    KimyosanoatIssueService issues;
    KimyosanoatEventWorker worker;

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V86__kimyosanoat_event_inbox.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V98__kimyosanoat_event_processing.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        inbox = new KimyosanoatEventInbox(jdbc, new ObjectMapper().findAndRegisterModules(), "default",
            "00000000-0000-0000-0000-000000000001");
    }

    @BeforeEach void setup() {
        jdbc.update("delete from kimyosanoat_event_inbox");
        client = mock(KimyosanoatClient.class);
        issues = mock(KimyosanoatIssueService.class);
        worker = new KimyosanoatEventWorker(jdbc, manager, client, issues, true);
    }

    private KimyosanoatEventInbox.Event event() {
        return new KimyosanoatEventInbox.Event(UUID.randomUUID(), 77L, UUID.randomUUID(), "EXIT_ACCEPTED",
            OffsetDateTime.parse("2026-11-18T10:30:00+05:00"));
    }

    @Test void uncertainStockGoesToManualReviewWithReason() {
        var event = event(); inbox.accept(event);
        var source = new ObjectMapper().createObjectNode();
        when(client.detailBy1cId(event.warehouse1cId(), 77)).thenReturn(source);
        when(client.resolveWarehousesBy1cId(event.warehouse1cId())).thenReturn(List.of(12L));
        when(issues.autoApprove(event.eventId(), 77, event.warehouse1cId(), source, List.of(12L)))
            .thenThrow(new KimyosanoatAutoReviewException("STOCK_INSUFFICIENT", "Not enough owned stock"));
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, event.eventId()))
            .isEqualTo("REVIEW_REQUIRED");
        assertThat(inbox.reviewQueue()).anySatisfy(item -> {
            assertThat(item.eventId()).isEqualTo(event.eventId());
            assertThat(item.reason()).isEqualTo("STOCK_INSUFFICIENT");
        });
    }

    @Test void sourceConnectionFailureIsRetriedWithoutPosting() {
        var event = event(); inbox.accept(event);
        when(client.detailBy1cId(event.warehouse1cId(), 77)).thenThrow(new BusinessException("Connection failed"));
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, event.eventId()))
            .isEqualTo("RETRY");
        verifyNoInteractions(issues);
    }

    @Test void repeatedlyMissingSourceDocumentGoesToReview() {
        var event = event(); inbox.accept(event);
        jdbc.update("update kimyosanoat_event_inbox set attempts=2 where event_id=?", event.eventId());
        when(client.detailBy1cId(event.warehouse1cId(), 77)).thenThrow(new KimyosanoatSourceHttpException(404));
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("select processing_state from kimyosanoat_event_inbox where event_id=?", String.class, event.eventId()))
            .isEqualTo("REVIEW_REQUIRED");
        verifyNoInteractions(issues);
    }
}
