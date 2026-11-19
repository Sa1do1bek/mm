package com.enterprise.mm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class IntegrationHealthWorkspaceSearchNormalizationTest {
    @Mock private IntegrationEventRepository eventRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private IntegrationBusinessDocumentResolver documentResolver;
    @Mock private IntegrationPayloadRedactor redactor;
    @Mock private IntegrationSettingService settingService;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private IntegrationHealthWorkspaceService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void leavesAnEmptySearchNullBecauseTheRepositoryCastsItToText() {
        UUID companyId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        TenantContext.setTenant("tenant-overview-integration");
        CompanyContext.setCompany(companyId);
        PageRequest pageable = PageRequest.of(0, 10);
        when(eventRepository.searchWorkspace(
            anyString(), any(UUID.class), anyString(), nullable(String.class), any(), anyInt(), any()
        )).thenReturn(Page.empty(pageable));

        service.eventsForCompany(IntegrationHealthWorkspaceService.QUEUE_DEAD, null, pageable);

        ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);
        verify(eventRepository).searchWorkspace(
            eq("tenant-overview-integration"),
            eq(companyId),
            eq(IntegrationHealthWorkspaceService.QUEUE_DEAD),
            search.capture(),
            any(),
            eq(IntegrationDispatchService.MAX_AUTOMATIC_RETRIES),
            eq(pageable)
        );
        assertThat(search.getValue()).isNull();
    }

    @Test
    void castsEverySearchParameterOccurrenceToTextForPostgres() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/enterprise/mm/repository/IntegrationEventRepository.java"
        ));

        assertThat(source).contains("cast(:search as string) is null");
        assertThat(source.split("cast\\(:search as string\\)", -1)).hasSize(8);
    }
}
