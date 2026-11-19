package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.SmsSettingsDto;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.SmsSettingsMapper;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.SmsGatewaySetting;
import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.repository.SmsGatewaySettingRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Настройки SMS-шлюза, хранящиеся в БД (с шифрованием токена).
 * При отсутствии записи используются значения из application.yml (SecurityIdentityProperties).
 */
@Service
@RequiredArgsConstructor
public class SmsSettingsService {
    private static final String DEFAULT_TEMPLATE = "ERP MM verification code: {code}. Valid for {ttlSeconds} seconds.";
    private final SmsGatewaySettingRepository repository;
    private final SecurityIdentityProperties properties;
    private final SmsSettingsMapper smsSettingsMapper;
    private final SmsGatewayClient smsGatewayClient;

    @Transactional(readOnly = true)
    public SmsSettingsDto get() {
        SmsGatewaySetting row = currentRow();
        return row == null
            ? smsSettingsMapper.defaults(properties, smsSettingsMapper.normalizeProvider(properties.getSmsMfa().getGateway().getProviderCode()))
            : smsSettingsMapper.toDto(row, smsSettingsMapper.normalizeProvider(row.getProviderCode()));
    }
    @Transactional
    public SmsSettingsDto save(SmsSettingsDto dto) {
        SmsGatewaySetting row = currentRow();
        if (row == null) {
            row = new SmsGatewaySetting();
            row.setCode("SMS-GATEWAY");
            row.setName("SMS Gateway");
            row.setStatus("ACTIVE");
        }
        row.setEnabled(dto.isEnabled());
        row.setMfaEnabled(dto.isMfaEnabled());
        row.setTtlSeconds(dto.getTtlSeconds() > 0 ? dto.getTtlSeconds() : 300);
        String providerCode = normalizeProvider(dto.getProviderCode());
        row.setProviderCode(providerCode);
        row.setUrl(trimToNull(defaultUrl(providerCode, dto.getUrl())));
        row.setAuthUrl(trimToNull(defaultAuthUrl(providerCode, dto.getAuthUrl())));
        row.setHttpMethod(blank(dto.getHttpMethod()) ? "POST" : dto.getHttpMethod().trim().toUpperCase());
        row.setAuthHeader(blank(dto.getAuthHeader()) ? "Authorization" : dto.getAuthHeader().trim());
        if (!blank(dto.getAuthToken())) {
            row.setAuthToken(dto.getAuthToken().trim());
        }
        row.setLogin(trimToNull(dto.getLogin()));
        if (!blank(dto.getPassword())) {
            row.setPassword(dto.getPassword().trim());
        }
        row.setAccountSid(trimToNull(dto.getAccountSid()));
        row.setServiceSid(trimToNull(dto.getServiceSid()));
        row.setFromPhone(trimToNull(dto.getFromPhone()));
        row.setSender(trimToNull(dto.getSender()));
        row.setTemplate(blank(dto.getTemplate()) ? DEFAULT_TEMPLATE : dto.getTemplate());
        row.setTimeoutSeconds(dto.getTimeoutSeconds() > 0 ? dto.getTimeoutSeconds() : 8);
        SmsGatewaySetting saved = repository.save(row);
        return smsSettingsMapper.toDto(saved, smsSettingsMapper.normalizeProvider(saved.getProviderCode()));
    }
    @Transactional
    public String test(String phoneNumber) {
        SmsGatewaySetting row = currentRow();
        try {
            SmsGatewayClient.SmsGatewayResult result = smsGatewayClient.sendTest(row, phoneNumber);
            if (row != null) {
                row.setLastProviderStatus("SUCCESS");
                row.setLastProviderMessage("HTTP " + result.statusCode() + " via " + result.provider());
                row.setLastTestedAt(LocalDateTime.now());
                repository.save(row);
            }
            return "Тестовый SMS-запрос отправлен через " + result.provider() + ", HTTP " + result.statusCode();
        } catch (BusinessException ex) {
            if (row != null) {
                row.setLastProviderStatus("FAILED");
                row.setLastProviderMessage(ex.getMessage());
                row.setLastTestedAt(LocalDateTime.now());
                repository.save(row);
            }
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Не удалось обратиться к SMS-шлюзу: " + ex.getMessage());
        }
    }
    private SmsGatewaySetting currentRow() {
        return repository.findFirstByTenantIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant()).orElse(null);
    }
    private String defaultUrl(String providerCode, String value) {
        if (!blank(value)) {
            return value;
        }
        if ("ESKIZ".equals(providerCode)) {
            return "https://notify.eskiz.uz/api/message/sms/send";
        }
        if ("TWILIO".equals(providerCode)) {
            return "https://api.twilio.com/2010-04-01/Accounts/{accountSid}/Messages.json";
        }
        return value;
    }
    private String defaultAuthUrl(String providerCode, String value) {
        if (!blank(value)) {
            return value;
        }
        return "ESKIZ".equals(providerCode) ? "https://notify.eskiz.uz/api/auth/login" : value;
    }
    private String normalizeProvider(String value) {
        return smsSettingsMapper.normalizeProvider(value);
    }
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private String trimToNull(String value) {
        return blank(value) ? null : value.trim();
    }
}

