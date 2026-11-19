package com.enterprise.mm.integration.faktura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

class FakturaImportServiceTest {
    private final FakturaClient client = mock(FakturaClient.class);
    private final FakturaSourceStore store = mock(FakturaSourceStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Executor executor = mock(Executor.class);
    private final FakturaImportService importer = new FakturaImportService(client, store, jdbc, executor);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void context() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(UUID.randomUUID());
        when(client.verifiedCompanyInn()).thenReturn("123456789");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void scansCreationAndUpdateWindowsAndCountsImportedContent() throws Exception {
        LocalDate today = LocalDate.now();
        JsonNode empty = json.readTree("{\"documents\":[]}");
        JsonNode header = json.readTree("{\"documents\":[{\"uniqueId\":\"doc-1\",\"status\":24}]}");
        JsonNode content = json.readTree("{\"Success\":true,\"Documents\":[{\"UniqueId\":\"doc-1\",\"Content\":{\"documentNumber\":\"P-1\"}}]}");
        when(client.documents(eq(today), eq(today), anyInt(), eq(0), eq(100), anyBoolean())).thenReturn(empty);
        when(client.documents(today, today, 20, 0, 100, false)).thenReturn(header);
        when(client.contents(java.util.List.of("doc-1"))).thenReturn(content);
        when(store.upsert(eq(20), any(JsonNode.class), any(JsonNode.class))).thenReturn(FakturaSourceStore.Change.CREATED);

        var result = importer.importRange(today, today);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fetched()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(client).documents(today, today, 20, 0, 100, true);
        verify(client).documents(today, today, 28, 0, 100, false);
        verify(client).documents(today, today, 29, 0, 100, true);
    }

    @Test
    void missingContentIsReportedAsPartial() throws Exception {
        LocalDate today = LocalDate.now();
        when(client.documents(eq(today), eq(today), anyInt(), eq(0), eq(100), anyBoolean()))
            .thenReturn(json.readTree("{\"documents\":[]}"));
        when(client.documents(today, today, 20, 0, 100, false))
            .thenReturn(json.readTree("{\"documents\":[{\"uniqueId\":\"doc-1\"}]}"));
        when(client.contents(java.util.List.of("doc-1")))
            .thenReturn(json.readTree("{\"Success\":true,\"Documents\":[]}"));

        var result = importer.importRange(today, today);

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.requestedFrom()).isEqualTo(today);
        assertThat(result.requestedTo()).isEqualTo(today);
        verify(jdbc).update(startsWith("INSERT INTO public.faktura_import_failures"),
            any(), any(), eq("tenant-a"), any(), eq(20), eq("doc-1"), any(), eq("CONTENT"), eq("CONTENT_MISSING"));
    }

    @Test
    void manualImportReturnsWhileWorkerKeepsCompanyContext() throws Exception {
        UUID company = CompanyContext.currentCompany();
        LocalDate today = LocalDate.now();
        when(client.documents(eq(today), eq(today), anyInt(), eq(0), eq(100), anyBoolean()))
            .thenReturn(json.readTree("{\"documents\":[]}"));
        when(client.verifiedCompanyInn()).thenAnswer(call -> {
            assertThat(TenantContext.currentTenant()).isEqualTo("tenant-a");
            assertThat(CompanyContext.currentCompany()).isEqualTo(company);
            return "123456789";
        });

        var started = importer.startImportRange(today, today);

        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.finishedAt()).isNull();
        verify(client, never()).verifiedCompanyInn();
        ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(work.capture());

        TenantContext.clear();
        CompanyContext.clear();
        work.getValue().run();

        verify(client).verifiedCompanyInn();
        assertThat(CompanyContext.currentCompany()).isNotEqualTo(company);
    }
}
