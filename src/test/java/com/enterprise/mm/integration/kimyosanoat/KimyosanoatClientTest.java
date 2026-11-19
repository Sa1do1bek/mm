package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.service.IntegrationSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class KimyosanoatClientTest {
    @Test
    void eventWorkerReadsDetailBySuppliedWarehouse1cUuid() throws Exception {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api.uzkimyo.uz/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":77}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);

        assertThat(client.detailBy1cId(UUID.fromString("671308d4-9ed8-11df-8030-001a4bcf769a"), 77).path("id").asLong())
            .isEqualTo(77);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo(
            "https://api.uzkimyo.uz/be/api/v1/mm-issue-transfers/by-warehouse1c/77?warehouse1cId=671308d4-9ed8-11df-8030-001a4bcf769a");
    }
    @Test
    void listsAllSourceWarehousesUsingTheirShared1cIdentity() throws Exception {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api.uzkimyo.uz/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"content\":[]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);

        client.listBy1c("8a3ba0d3c1060d4b11ea3697bd71da53", 1, 20);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.uzkimyo.uz/be/api/v1/mm-issue-transfers/by-warehouse1c?warehouse1cId=bd71da53-3697-11ea-8a3b-a0d3c1060d4b&page=1&size=20");
    }
    @Test
    void permitsTheConfirmedMainApiForWarehouseResolution() throws Exception {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api.uzkimyo.uz/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"warehouseId\":41267671}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);
        assertThat(client.resolveWarehouse("9636a0d3c1060d4b11eb5ec5e51689bd")).isEqualTo(41267671L);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://api.uzkimyo.uz/be/api/v1/mm-issue-transfers/resolve-warehouse?warehouse1cId=e51689bd-5ec5-11eb-9636-a0d3c1060d4b");
    }

    @Test
    void refusesToSendCredentialsToAnUnrelatedHost() {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api.uzkimyo.uz.invalid/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.list(1, 0, 20))
            .isInstanceOf(com.enterprise.mm.error.BusinessException.class);
        verifyNoInteractions(http);
    }

    @Test
    void resolvesAnImportedWarehouseUsingThe1cUuidRepresentation() throws Exception {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api-navoiy-azot.uzkimyo.uz/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"warehouseId\":12}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);

        assertThat(client.resolveWarehouse("8030001a4bcf769a11df9ed8671308d4")).isEqualTo(12);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getQuery())
            .isEqualTo("warehouse1cId=671308d4-9ed8-11df-8030-001a4bcf769a");
    }

    @Test
    void sendsBasicCredentialsDirectlyToTheIssueFeed() throws Exception {
        var settings = mock(IntegrationSettingService.class);
        var endpoint = new IntegrationSettingService.RuntimeEndpoint("KIMYOSANOAT", "Kimyosanoat", true, false,
            "https://api-navoiy-azot.uzkimyo.uz/be/api/v1", "BASIC", "mm-reader", "fixture-password",
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        when(settings.endpoint(ExternalIntegrationSystem.KIMYOSANOAT)).thenReturn(endpoint);
        var http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"content\":[]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var client = new KimyosanoatClient(settings, new ObjectMapper());
        ReflectionTestUtils.setField(client, "http", http);

        assertThat(client.list(12, 0, 20).path("content").isArray()).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().uri().getPath()).endsWith("/mm-issue-transfers");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Basic " +
            Base64.getEncoder().encodeToString("mm-reader:fixture-password".getBytes(StandardCharsets.UTF_8)));
    }
}
