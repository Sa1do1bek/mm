package com.enterprise.mm.mapper;

import com.enterprise.mm.config.SecurityIdentityProperties;
import com.enterprise.mm.entity.SmsGatewaySetting;
import com.enterprise.mm.payload.SmsSettingsDto;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SmsSettingsMapper {
    public SmsSettingsDto toDto(SmsGatewaySetting row, String normalizedProviderCode) {
        SmsSettingsDto dto = new SmsSettingsDto();
        dto.setEnabled(row.isEnabled());
        dto.setMfaEnabled(row.isMfaEnabled());
        dto.setTtlSeconds(row.getTtlSeconds());
        dto.setProviderCode(normalizedProviderCode);
        dto.setUrl(row.getUrl());
        dto.setAuthUrl(row.getAuthUrl());
        dto.setHttpMethod(row.getHttpMethod());
        dto.setAuthHeader(row.getAuthHeader());
        dto.setAuthToken(null);
        dto.setAuthTokenConfigured(!blank(row.getAuthToken()));
        dto.setLogin(row.getLogin());
        dto.setPassword(null);
        dto.setPasswordConfigured(!blank(row.getPassword()));
        dto.setAccountSid(row.getAccountSid());
        dto.setServiceSid(row.getServiceSid());
        dto.setFromPhone(row.getFromPhone());
        dto.setSender(row.getSender());
        dto.setTemplate(row.getTemplate());
        dto.setTimeoutSeconds(row.getTimeoutSeconds());
        dto.setLastProviderStatus(row.getLastProviderStatus());
        dto.setLastProviderMessage(row.getLastProviderMessage());
        dto.setLastTestedAt(row.getLastTestedAt());
        return dto;
    }

    public SmsSettingsDto defaults(SecurityIdentityProperties properties, String normalizedProviderCode) {
        var sms = properties.getSmsMfa();
        var gw = sms.getGateway();
        SmsSettingsDto dto = new SmsSettingsDto();
        dto.setEnabled(gw.isEnabled());
        dto.setMfaEnabled(sms.isEnabled());
        dto.setTtlSeconds(sms.getTtlSeconds());
        dto.setProviderCode(normalizedProviderCode);
        dto.setUrl(gw.getUrl());
        dto.setAuthUrl(gw.getAuthUrl());
        dto.setHttpMethod(gw.getMethod());
        dto.setAuthHeader(gw.getAuthHeader());
        dto.setAuthToken(null);
        dto.setAuthTokenConfigured(!blank(gw.getAuthToken()));
        dto.setLogin(gw.getLogin());
        dto.setPassword(null);
        dto.setPasswordConfigured(!blank(gw.getPassword()));
        dto.setAccountSid(gw.getAccountSid());
        dto.setServiceSid(gw.getServiceSid());
        dto.setFromPhone(gw.getFromPhone());
        dto.setSender(gw.getSender());
        dto.setTemplate(gw.getTemplate());
        dto.setTimeoutSeconds(gw.getTimeoutSeconds());
        return dto;
    }

    public String normalizeProvider(String value) {
        if (blank(value)) {
            return "CUSTOM";
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return "TWILLO".equals(normalized) ? "TWILIO" : normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
