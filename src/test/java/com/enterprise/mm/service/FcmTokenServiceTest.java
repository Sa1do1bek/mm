package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FcmToken;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.enums.FcmDevicePlatform;
import com.enterprise.mm.payload.FcmTokenRegisterRequest;
import com.enterprise.mm.repository.FcmTokenRepository;
import com.enterprise.mm.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FcmTokenServiceTest {
    private static final String TENANT = "tenant-fcm";
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000007701");

    @Mock private FcmTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;

    private FcmTokenService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(TENANT);
        CompanyContext.setCompany(COMPANY_ID);
        service = new FcmTokenService(tokenRepository, userRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void registerStoresNormalizedClientLanguage() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(tokenRepository.findByTokenAndIsDeletedFalse("token-1")).thenReturn(Optional.empty());
        when(tokenRepository.save(any(FcmToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(userId, new FcmTokenRegisterRequest(" token-1 ", FcmDevicePlatform.WEB, "browser-1", "ru-RU"));

        ArgumentCaptor<FcmToken> captor = ArgumentCaptor.forClass(FcmToken.class);
        org.mockito.Mockito.verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getLanguage()).isEqualTo("ru");
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
    }

    @Test
    void registerFallsBackToEnglishForUnsupportedLanguage() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findByIdAndIsDeletedFalse(userId)).thenReturn(Optional.of(user));
        when(tokenRepository.findByTokenAndIsDeletedFalse("token-2")).thenReturn(Optional.empty());
        when(tokenRepository.save(any(FcmToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.register(userId, new FcmTokenRegisterRequest("token-2", FcmDevicePlatform.ANDROID, null, "fr"));

        assertThat(response.language()).isEqualTo("en");
    }
}
