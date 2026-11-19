package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.entity.IntegrationSetting;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.mapper.IntegrationSettingMapper;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.repository.IntegrationSettingRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FakturaEndpointConfigurationTest {
    private final UUID companyId = UUID.randomUUID();
    private final IntegrationSettingRepository repository = mock(IntegrationSettingRepository.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final IntegrationProperties properties = new IntegrationProperties();

    @AfterEach
    void clear() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void savedCompanyModeCanUseCompleteServerCredentialsWithoutMixingPairs() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        IntegrationProperties.SystemEndpoint configured = new IntegrationProperties.SystemEndpoint();
        configured.setUsername("server-login");
        configured.setPassword("server-password");
        configured.setClientId("server-client");
        configured.setClientSecret("server-secret");
        properties.getSystems().put("faktura-uz", configured);

        IntegrationSetting saved = new IntegrationSetting();
        saved.setEnabled(true);
        saved.setMockMode(false);
        saved.setBaseUrl("https://api.faktura.uz");
        saved.setAuthPath("https://account.faktura.uz/token");
        saved.setUsername("incomplete-db-login");
        when(repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(
            "tenant-a", companyId, "FAKTURA_UZ")).thenReturn(Optional.of(saved));

        IntegrationSettingService service = new IntegrationSettingService(repository, null, properties,
            null, null, null, null, cipher, null, null);
        var endpoint = service.endpoint(ExternalIntegrationSystem.FAKTURA_UZ);

        assertThat(endpoint.mockMode()).isFalse();
        assertThat(endpoint.baseUrl()).isEqualTo("https://api.faktura.uz");
        assertThat(endpoint.username()).isEqualTo("server-login");
        assertThat(endpoint.password()).isEqualTo("server-password");
        assertThat(endpoint.clientId()).isEqualTo("server-client");
        assertThat(endpoint.clientSecret()).isEqualTo("server-secret");
    }

    @Test
    void completeSavedCredentialsTakePrecedenceOverServerCredentials() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        IntegrationProperties.SystemEndpoint configured = new IntegrationProperties.SystemEndpoint();
        configured.setUsername("server-login");
        configured.setPassword("server-password");
        configured.setClientId("server-client");
        configured.setClientSecret("server-secret");
        properties.getSystems().put("faktura-uz", configured);

        IntegrationSetting saved = new IntegrationSetting();
        saved.setUsername("company-login");
        saved.setPasswordEncrypted("encrypted-password");
        saved.setClientId("company-client");
        saved.setClientSecretEncrypted("encrypted-secret");
        when(cipher.decrypt("encrypted-password")).thenReturn("company-password");
        when(cipher.decrypt("encrypted-secret")).thenReturn("company-secret");
        when(repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(
            "tenant-a", companyId, "FAKTURA_UZ")).thenReturn(Optional.of(saved));

        IntegrationSettingService service = new IntegrationSettingService(repository, null, properties,
            null, null, null, null, cipher, null, null);
        var endpoint = service.endpoint(ExternalIntegrationSystem.FAKTURA_UZ);

        assertThat(endpoint.username()).isEqualTo("company-login");
        assertThat(endpoint.password()).isEqualTo("company-password");
        assertThat(endpoint.clientId()).isEqualTo("company-client");
        assertThat(endpoint.clientSecret()).isEqualTo("company-secret");
    }

    @Test
    void savingAbsoluteAuthUrlDoesNotAddALeadingSlash() {
        TenantContext.setTenant("tenant-a");
        CompanyContext.setCompany(companyId);
        when(repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(
            "tenant-a", companyId, "FAKTURA_UZ")).thenReturn(Optional.empty());
        when(repository.save(any(IntegrationSetting.class))).thenAnswer(call -> call.getArgument(0));
        IntegrationSettingService service = new IntegrationSettingService(repository,
            mock(IntegrationSettingMapper.class), properties, null, null, null, null, cipher,
            mock(AuditService.class), null);
        IntegrationSettingUpdateRequest request = new IntegrationSettingUpdateRequest();
        request.setEnabled(true);
        request.setBaseUrl("https://api.faktura.uz");
        request.setAuthPath("https://account.faktura.uz/token");

        service.saveSetting(ExternalIntegrationSystem.FAKTURA_UZ, request);
        request.setAuthPath("/https://account.faktura.uz/token");
        service.saveSetting(ExternalIntegrationSystem.FAKTURA_UZ, request);

        ArgumentCaptor<IntegrationSetting> saved = ArgumentCaptor.forClass(IntegrationSetting.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(setting ->
            assertThat(setting.getAuthPath()).isEqualTo("https://account.faktura.uz/token"));
    }
}
