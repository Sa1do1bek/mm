package com.enterprise.mm.integration.kimyosanoat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.config.IntegrationProperties;
import com.enterprise.mm.entity.IntegrationSetting;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.mapper.IntegrationSettingMapper;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.repository.IntegrationSettingRepository;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.IntegrationSettingService;
import com.enterprise.mm.service.SecretCipher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KimyosanoatSettingTest {
    private final UUID company = UUID.randomUUID();
    private final IntegrationSettingRepository repository = mock(IntegrationSettingRepository.class);
    private final SecretCipher cipher = mock(SecretCipher.class);
    private final IntegrationSettingService service = new IntegrationSettingService(repository,
        new IntegrationSettingMapper(), new IntegrationProperties(), null, null, null, null, cipher,
        mock(AuditService.class), null);

    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }

    @Test void settingsListAndFirstCredentialSaveWorkForNewCompany() {
        TenantContext.setTenant("tenant"); CompanyContext.setCompany(company);
        when(repository.findByTenantIdAndCompanyIdAndSystemCodeAndIsDeletedFalse(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(cipher.encrypt("fixture-password")).thenReturn("encrypted-password");
        when(repository.save(any(IntegrationSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var item = service.listSettings().stream().filter(x -> "KIMYOSANOAT".equals(x.getSystemCode())).findFirst().orElseThrow();
        assertThat(item.getAuthType()).isEqualTo("BASIC");
        assertThat(item.getBaseUrl()).isEqualTo("https://api-navoiy-azot.uzkimyo.uz/be/api/v1");
        assertThat(item.isMockMode()).isTrue();
        var request = new IntegrationSettingUpdateRequest();
        request.setEnabled(true); request.setMockMode(false); request.setAuthType("BASIC");
        request.setBaseUrl(item.getBaseUrl()); request.setUsername("mm_reader"); request.setPassword("fixture-password");
        var saved = service.saveSetting(ExternalIntegrationSystem.KIMYOSANOAT, request);
        assertThat(saved.getUsername()).isEqualTo("mm_reader");
        assertThat(saved.isPasswordConfigured()).isTrue();
        assertThat(saved.getPassword()).isNull();
        verify(cipher).encrypt("fixture-password");
    }
}
