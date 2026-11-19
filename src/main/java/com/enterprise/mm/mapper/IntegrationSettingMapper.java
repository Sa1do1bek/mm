package com.enterprise.mm.mapper;

import com.enterprise.mm.entity.IntegrationSetting;
import com.enterprise.mm.payload.IntegrationSettingResponse;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.payload.IntegrationSettingCreateRequest;
import org.springframework.stereotype.Component;

@Component
public class IntegrationSettingMapper {
    public IntegrationSettingResponse toResponse(IntegrationSetting entity) {
        if (entity == null) {
            return null;
        }
        IntegrationSettingResponse dto = new IntegrationSettingResponse();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setDeleted(entity.isDeleted());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        dto.setSystemCode(entity.getSystemCode());
        dto.setProviderName(entity.getProviderName());
        dto.setEnabled(entity.isEnabled());
        dto.setMockMode(entity.isMockMode());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setAuthType(entity.getAuthType());
        dto.setUsername(entity.getUsername());
        dto.setApiKeyHeader(entity.getApiKeyHeader());
        dto.setClientId(entity.getClientId());
        dto.setSenderTin(entity.getSenderTin());
        dto.setSenderBranchCode(entity.getSenderBranchCode());
        dto.setCallbackUrl(entity.getCallbackUrl());
        dto.setAuthPath(entity.getAuthPath());
        dto.setHealthPath(entity.getHealthPath());
        dto.setEventsPath(entity.getEventsPath());
        dto.setInvoicePath(entity.getInvoicePath());
        dto.setDocumentPath(entity.getDocumentPath());
        dto.setSupplierStatusPath(entity.getSupplierStatusPath());
        dto.setProviderVersion(entity.getProviderVersion());
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setProjectId(entity.getProjectId());
        dto.setAiModel(entity.getAiModel());
        dto.setAiRequestPath(entity.getAiRequestPath());
        dto.setAiSystemPrompt(entity.getAiSystemPrompt());
        dto.setAiTemperature(entity.getAiTemperature());
        dto.setAiMaxTokens(entity.getAiMaxTokens());
        dto.setAiTimeoutSeconds(entity.getAiTimeoutSeconds());
        dto.setPassword(null);
        dto.setBearerToken(null);
        dto.setApiKey(null);
        dto.setClientSecret(null);
        dto.setPasswordConfigured(hasSecret(entity.getPasswordEncrypted()));
        dto.setBearerTokenConfigured(hasSecret(entity.getBearerTokenEncrypted()));
        dto.setApiKeyConfigured(hasSecret(entity.getApiKeyEncrypted()));
        dto.setClientSecretConfigured(hasSecret(entity.getClientSecretEncrypted()));
        return dto;
    }

    public IntegrationSetting toEntity(IntegrationSettingCreateRequest dto) {
        if (dto == null) {
            return null;
        }
        IntegrationSetting entity = new IntegrationSetting();
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSystemCode(dto.getSystemCode());
        entity.setProviderName(dto.getProviderName());
        entity.setEnabled(dto.isEnabled());
        entity.setMockMode(dto.isMockMode());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setAuthType(dto.getAuthType());
        entity.setUsername(dto.getUsername());
        entity.setApiKeyHeader(dto.getApiKeyHeader());
        entity.setClientId(dto.getClientId());
        entity.setSenderTin(dto.getSenderTin());
        entity.setSenderBranchCode(dto.getSenderBranchCode());
        entity.setCallbackUrl(dto.getCallbackUrl());
        entity.setAuthPath(dto.getAuthPath());
        entity.setHealthPath(dto.getHealthPath());
        entity.setEventsPath(dto.getEventsPath());
        entity.setInvoicePath(dto.getInvoicePath());
        entity.setDocumentPath(dto.getDocumentPath());
        entity.setSupplierStatusPath(dto.getSupplierStatusPath());
        entity.setProviderVersion(dto.getProviderVersion());
        entity.setOrganizationId(dto.getOrganizationId());
        entity.setProjectId(dto.getProjectId());
        entity.setAiModel(dto.getAiModel());
        entity.setAiRequestPath(dto.getAiRequestPath());
        entity.setAiSystemPrompt(dto.getAiSystemPrompt());
        entity.setAiTemperature(dto.getAiTemperature());
        entity.setAiMaxTokens(dto.getAiMaxTokens());
        entity.setAiTimeoutSeconds(dto.getAiTimeoutSeconds());
        return entity;
    }

    public void updateEntity(IntegrationSetting entity, IntegrationSettingUpdateRequest dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setStatus(dto.getStatus());
        entity.setDescription(dto.getDescription());
        entity.setSystemCode(dto.getSystemCode());
        entity.setProviderName(dto.getProviderName());
        entity.setEnabled(dto.isEnabled());
        entity.setMockMode(dto.isMockMode());
        entity.setBaseUrl(dto.getBaseUrl());
        entity.setAuthType(dto.getAuthType());
        entity.setUsername(dto.getUsername());
        entity.setApiKeyHeader(dto.getApiKeyHeader());
        entity.setClientId(dto.getClientId());
        entity.setSenderTin(dto.getSenderTin());
        entity.setSenderBranchCode(dto.getSenderBranchCode());
        entity.setCallbackUrl(dto.getCallbackUrl());
        entity.setAuthPath(dto.getAuthPath());
        entity.setHealthPath(dto.getHealthPath());
        entity.setEventsPath(dto.getEventsPath());
        entity.setInvoicePath(dto.getInvoicePath());
        entity.setDocumentPath(dto.getDocumentPath());
        entity.setSupplierStatusPath(dto.getSupplierStatusPath());
        entity.setProviderVersion(dto.getProviderVersion());
        entity.setOrganizationId(dto.getOrganizationId());
        entity.setProjectId(dto.getProjectId());
        entity.setAiModel(dto.getAiModel());
        entity.setAiRequestPath(dto.getAiRequestPath());
        entity.setAiSystemPrompt(dto.getAiSystemPrompt());
        entity.setAiTemperature(dto.getAiTemperature());
        entity.setAiMaxTokens(dto.getAiMaxTokens());
        entity.setAiTimeoutSeconds(dto.getAiTimeoutSeconds());
    }

    private boolean hasSecret(String value) {
        return value != null && !value.isBlank();
    }
}
