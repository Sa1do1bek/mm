package com.enterprise.mm.web;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserIdentityDisplayEnricher {
    private static final java.util.Set<String> DISPLAY_FIELDS = java.util.Set.of(
        "acceptedby",
        "activatedby",
        "actor",
        "actedby",
        "approvedby",
        "assignee",
        "blockedby",
        "buyerdecisionby",
        "cancelledby",
        "changedby",
        "closedby",
        "configuredby",
        "consumedby",
        "countedby",
        "createdby",
        "decidedby",
        "expiredby",
        "monitoringacceptedby",
        "openedby",
        "owner",
        "postedby",
        "publishedby",
        "recordedby",
        "rejectedby",
        "releasedby",
        "reopenedby",
        "responsiblebuyer",
        "requestedby",
        "requester",
        "reservedby",
        "reviewedby",
        "submittedby",
        "terminalby",
        "updatedby",
        "verifiedby"
    );

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void enrich(JsonNode body) {
        enrich(body, null, null, new HashMap<>());
    }

    private void enrich(JsonNode node, String fieldName, JsonNode parent, Map<String, String> cache) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isTextual() && shouldDisplayUser(field.getKey(), objectNode)) {
                    String displayName = displayName(value.textValue(), cache);
                    if (displayName != null) {
                        objectNode.put(field.getKey(), displayName);
                    }
                } else {
                    enrich(value, field.getKey(), objectNode, cache);
                }
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (JsonNode item : arrayNode) {
                enrich(item, fieldName, parent, cache);
            }
        }
    }

    private boolean shouldDisplayUser(String fieldName, ObjectNode parent) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        if ("username".equals(normalized)) {
            return isAuditUsername(parent);
        }
        if (normalized.endsWith("username") || normalized.endsWith("userName".toLowerCase(Locale.ROOT))) {
            return true;
        }
        return DISPLAY_FIELDS.contains(normalized);
    }

    private boolean isAuditUsername(ObjectNode parent) {
        return parent != null
            && parent.has("action")
            && (parent.has("occurredAt") || parent.has("entityType") || parent.has("entityId"));
    }

    private String displayName(String username, Map<String, String> cache) {
        String normalized = username == null ? null : username.trim();
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String key = normalized.toLowerCase(Locale.ROOT);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String displayName = userRepository
            .findByTenantIdAndCompanyIdAndUsernameIgnoreCaseAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized)
            .or(() -> userRepository.findByTenantIdAndCompanyIdIsNullAndUsernameAndIsDeletedFalse(
                TenantContext.currentTenant(), normalized))
            .map(this::displayName)
            .orElse(null);
        cache.put(key, displayName);
        return displayName;
    }

    private String displayName(User user) {
        return firstNonBlank(user.getFullName(), user.getName(), user.getUsername(), user.getEmail());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
