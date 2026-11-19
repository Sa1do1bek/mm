package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MaterialAttributeTemplate;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.MaterialAttributeRequest;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MaterialAttributeValidationService {
    private final MaterialAttributeTemplateRepository templateRepository;

    public void validate(List<MaterialAttributeRequest> requests) {
        if (requests == null) {
            return;
        }
        Set<String> normalizedKeys = new HashSet<>();
        for (MaterialAttributeRequest request : requests) {
            if (request == null) {
                throw new BadRequestException(
                    "Material attribute is required",
                    "MATERIAL_ATTRIBUTE_REQUIRED"
                );
            }
            String key = trim(request.key());
            String value = trim(request.value());
            if (blank(key)) {
                throw new BadRequestException(
                    "Material attribute key is required",
                    "MATERIAL_ATTRIBUTE_KEY_REQUIRED"
                );
            }
            if (blank(value)) {
                throw new BadRequestException(
                    "Material attribute value is required",
                    "MATERIAL_ATTRIBUTE_VALUE_REQUIRED"
                );
            }
            if (key.length() > 255) {
                throw new BadRequestException(
                    "Material attribute key is too long",
                    "MATERIAL_ATTRIBUTE_KEY_TOO_LONG"
                );
            }
            if (value.length() > 4000) {
                throw new BadRequestException(
                    "Material attribute value is too long",
                    "MATERIAL_ATTRIBUTE_VALUE_TOO_LONG"
                );
            }
            String normalizedKey = normalizeKey(key);
            if (!normalizedKeys.add(normalizedKey)) {
                throw new BusinessException(
                    "Duplicate material attribute key: " + key,
                    "MATERIAL_ATTRIBUTE_DUPLICATE_KEY",
                    key
                );
            }
        }
    }

    public void validate(UUID materialGroupId, List<MaterialAttributeRequest> requests) {
        validate(requests);
        if (materialGroupId == null) {
            return;
        }
        List<MaterialAttributeTemplate> templates = templateRepository.findActiveByScopeAndMaterialGroup(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), materialGroupId);
        if (templates.isEmpty()) {
            return;
        }

        Map<String, MaterialAttributeTemplate> templatesByKey = new LinkedHashMap<>();
        for (MaterialAttributeTemplate template : templates) {
            String key = normalizeKey(template.getAttributeName());
            if (templatesByKey.putIfAbsent(key, template) != null) {
                throw new BusinessException(
                    "Duplicate active material attribute template: " + template.getAttributeName(),
                    "MATERIAL_ATTRIBUTE_TEMPLATE_DUPLICATE",
                    template.getAttributeName()
                );
            }
        }

        Map<String, MaterialAttributeRequest> requestsByKey = new LinkedHashMap<>();
        for (MaterialAttributeRequest request : requests == null ? List.<MaterialAttributeRequest>of() : requests) {
            requestsByKey.put(normalizeKey(request.key()), request);
        }
        for (MaterialAttributeTemplate template : templates) {
            String key = normalizeKey(template.getAttributeName());
            MaterialAttributeRequest request = requestsByKey.remove(key);
            if (Boolean.TRUE.equals(template.getRequired()) && request == null) {
                throw new BusinessException(
                    "Required material attribute is missing: " + template.getAttributeName(),
                    "MATERIAL_ATTRIBUTE_TEMPLATE_REQUIRED",
                    template.getAttributeName()
                );
            }
            if (request != null) {
                validateTemplateValue(template, trim(request.value()));
            }
        }
        if (!requestsByKey.isEmpty()) {
            String unknown = requestsByKey.values().iterator().next().key();
            throw new BusinessException(
                "Material attribute is not defined for the selected group: " + unknown,
                "MATERIAL_ATTRIBUTE_TEMPLATE_UNKNOWN",
                unknown
            );
        }
    }

    private void validateTemplateValue(MaterialAttributeTemplate template, String value) {
        String type = template.getDataType() == null
            ? "STRING"
            : template.getDataType().trim().toUpperCase(Locale.ROOT);
        try {
            switch (type) {
                case "NUMBER" -> new BigDecimal(value);
                case "DATE" -> LocalDate.parse(value);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException("not a boolean");
                    }
                }
                case "ENUM" -> validateEnumValue(template, value);
                case "STRING", "TEXT" -> { }
                default -> throw new BusinessException(
                    "Unsupported material attribute template type: " + type,
                    "MATERIAL_ATTRIBUTE_TEMPLATE_TYPE_INVALID",
                    type
                );
            }
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw invalidValue(template, type);
        } catch (IllegalArgumentException exception) {
            throw invalidValue(template, type);
        }
    }

    private void validateEnumValue(MaterialAttributeTemplate template, String value) {
        Set<String> allowed = new HashSet<>();
        String raw = template.getAllowedValues();
        if (raw != null) {
            for (String item : raw.split("[,;|\\n\\r]+")) {
                if (!item.isBlank()) {
                    allowed.add(item.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (allowed.isEmpty() || !allowed.contains(value.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(
                "Value is not in the allowed values for material attribute " + template.getAttributeName(),
                "MATERIAL_ATTRIBUTE_ENUM_NOT_ALLOWED",
                template.getAttributeName(),
                value
            );
        }
    }

    private BusinessException invalidValue(MaterialAttributeTemplate template, String type) {
        return new BusinessException(
            "Invalid " + type + " value for material attribute " + template.getAttributeName(),
            "MATERIAL_ATTRIBUTE_TEMPLATE_VALUE_INVALID",
            template.getAttributeName(),
            type
        );
    }

    public String normalizeKey(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    public String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
