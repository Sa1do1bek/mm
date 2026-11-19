package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class KimyosanoatEventControllerTest {
    private final KimyosanoatEventInbox inbox = mock(KimyosanoatEventInbox.class);
    private final KimyosanoatWaybillInbox waybills = mock(KimyosanoatWaybillInbox.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String companyId = UUID.randomUUID().toString();
    private final KimyosanoatEventController controller = new KimyosanoatEventController(
        inbox, waybills, json, "kimyo-events", "inbound-secret", "default", companyId);
    private final KimyosanoatEventInbox.Event event = new KimyosanoatEventInbox.Event(
        UUID.randomUUID(), 123L, UUID.randomUUID(), "EXIT_ACCEPTED", OffsetDateTime.parse("2026-11-18T10:30:00+05:00"));

    @Test void basicCredentialsAreRequiredBeforeSaving() throws Exception {
        byte[] body = json.writeValueAsBytes(event);
        assertThat(controller.receive(null, null, null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.receive("Basic ???", null, null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.receive(basic("kimyo-events", "wrong"), null, null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(inbox, waybills);
    }

    @Test void newAndRepeatedDeliveriesGetDifferentAcknowledgements() throws Exception {
        when(inbox.accept(any(KimyosanoatEventInbox.Event.class))).thenReturn(
            new KimyosanoatEventInbox.Receipt(event.eventId(), "RECEIVED", false),
            new KimyosanoatEventInbox.Receipt(event.eventId(), "RECEIVED", true));
        byte[] body = json.writeValueAsBytes(event);
        assertThat(controller.receive(basic("kimyo-events", "inbound-secret"), null, null, body).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.receive(basic("kimyo-events", "inbound-secret"), null, null, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(inbox, times(2)).accept(argThat(received -> received.eventId().equals(event.eventId())
            && received.transferId().equals(event.transferId())));
    }

    @Test void acceptsUnwrappedFakturaWaybillBytes() {
        byte[] body = "{\"local_waybills\":[{\"head\":{},\"document\":{}}]}".getBytes(StandardCharsets.UTF_8);
        UUID warehouse1cId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        when(waybills.accept(body, 123L, warehouse1cId))
            .thenReturn(new KimyosanoatWaybillInbox.Receipt(receiptId, "RECEIVED", false));
        assertThat(controller.receive(basic("kimyo-events", "inbound-secret"), 123L, warehouse1cId, body)
            .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(waybills).accept(body, 123L, warehouse1cId);
        verifyNoInteractions(inbox);
    }

    @Test void missingConfigurationDisablesReceiver() throws Exception {
        var disabled = new KimyosanoatEventController(inbox, waybills, json, "", "", "default", companyId);
        assertThat(disabled.receive(basic("kimyo-events", "inbound-secret"), null, null, json.writeValueAsBytes(event)).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(inbox, waybills);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
