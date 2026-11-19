package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
class KimyosanoatWaybillInboxPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final String TENANT = "default";
    static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static JdbcTemplate jdbc;
    static KimyosanoatWaybillInbox waybills;

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        for (String migration : new String[] {
                "V86__kimyosanoat_event_inbox.sql", "V98__kimyosanoat_event_processing.sql",
                "V101__kimyosanoat_waybill_inbox.sql"
        }) new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + migration)).execute(source);
        jdbc = new JdbcTemplate(source);
        var json = new ObjectMapper().findAndRegisterModules();
        var events = new KimyosanoatEventInbox(jdbc, json, TENANT, COMPANY.toString());
        waybills = new KimyosanoatWaybillInbox(jdbc, json, events, TENANT, COMPANY.toString());
    }

    @Test void savesExactBytesWithoutSourceIdentifiersForReview() {
        byte[] body = waybill("N-100");
        var first = waybills.accept(body, null, null);
        var repeat = waybills.accept(body, null, null);
        assertThat(first.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(repeat.duplicate()).isTrue();
        assertThat(repeat.receiptId()).isEqualTo(first.receiptId());
        assertThat(waybills.raw(first.receiptId(), TENANT, COMPANY)).isEqualTo(body);
        assertThat(waybills.review(TENANT, COMPANY)).anySatisfy(item ->
            assertThat(item.receiptId()).isEqualTo(first.receiptId()));
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?",
            Integer.class, first.receiptId())).isZero();
    }

    @Test void headersQueueTheExistingVerifiedStockWorkerOnce() {
        byte[] body = waybill("N-101");
        UUID warehouse = UUID.randomUUID();
        var first = waybills.accept(body, 555L, warehouse);
        var repeat = waybills.accept(body, 555L, warehouse);
        assertThat(first.state()).isEqualTo("RECEIVED");
        assertThat(repeat.duplicate()).isTrue();
        assertThat(repeat.receiptId()).isEqualTo(first.receiptId());
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?",
            Integer.class, first.receiptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select transfer_id from kimyosanoat_event_inbox where event_id=?",
            Long.class, first.receiptId())).isEqualTo(555L);
        assertThatThrownBy(() -> waybills.accept(body, 556L, warehouse)).isInstanceOf(ConflictException.class);
    }

    @Test void sameReceiptCanBeUpgradedAfterMissingHeadersAreSupplied() {
        byte[] body = waybill("N-102");
        var first = waybills.accept(body, null, null);
        var upgraded = waybills.accept(body, 557L, UUID.randomUUID());
        assertThat(upgraded.receiptId()).isEqualTo(first.receiptId());
        assertThat(upgraded.state()).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?",
            Integer.class, first.receiptId())).isEqualTo(1);
    }

    @Test void rejectsInvalidWaybillsAndPartialHeaders() {
        assertThatThrownBy(() -> waybills.accept("{}".getBytes(StandardCharsets.UTF_8), null, null))
            .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> waybills.accept(waybill("N-103"), 558L, null))
            .isInstanceOf(BadRequestException.class);
    }

    @Test void multipleWaybillsAreStoredButNeverQueuedAsOneTransfer() {
        byte[] body = ("{\"local_waybills\":[{\"head\":{},\"document\":{}},"
            + "{\"head\":{},\"document\":{}}]}").getBytes(StandardCharsets.UTF_8);
        var receipt = waybills.accept(body, 559L, UUID.randomUUID());
        assertThat(receipt.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(waybills.review(TENANT, COMPANY))
            .filteredOn(item -> item.receiptId().equals(receipt.receiptId()))
            .singleElement().satisfies(item -> assertThat(item.reason()).isEqualTo("MULTIPLE_WAYBILLS"));
        assertThat(jdbc.queryForObject("select count(*) from kimyosanoat_event_inbox where event_id=?",
            Integer.class, receipt.receiptId())).isZero();
    }

    private static byte[] waybill(String number) {
        return ("{\"local_waybills\":[{\"head\":{\"file_name\":\"sample.xml\"},"
            + "\"document\":{\"waybill_number\":\"" + number + "\"}}]}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
