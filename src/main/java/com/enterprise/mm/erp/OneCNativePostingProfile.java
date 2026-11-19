package com.enterprise.mm.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The company-scoped 1C posting profile is maintained by the MM operator. */
@Component
final class OneCNativePostingProfile {
    private final JsonNode profile;
    private final String tenantId;
    private final UUID companyId;

    @Autowired
    OneCNativePostingProfile(ObjectMapper mapper,
            @Value("${mm.one-c-api.native-posting-profile-path:${MM_ONE_C_API_NATIVE_POSTING_PROFILE_PATH:}}") String path) {
        this(load(mapper, path));
    }

    OneCNativePostingProfile(JsonNode profile) {
        this.profile = profile == null ? null : profile.deepCopy();
        tenantId = this.profile == null ? null : this.profile.path("tenantId").asText();
        companyId = this.profile == null ? null : uuid(this.profile, "companyId");
        if (this.profile != null && (!this.profile.isObject() || !this.profile.path("tenantId").isTextual()
                || tenantId.isBlank() || tenantId.length() > 80)) {
            throw new IllegalArgumentException("Native posting profile must have a valid tenantId");
        }
    }

    private static JsonNode load(ObjectMapper mapper, String path) {
        if (path.isBlank()) return null;
        try (var input = Files.newInputStream(Path.of(path))) {
            return mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(input);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read the MM native posting profile", ex);
        }
    }

    boolean appliesTo(String tenant, UUID company) {
        return profile != null && tenantId.equals(tenant) && companyId.equals(company);
    }

    ObjectNode resolve(String tenant, UUID company, ObjectNode original) {
        if (!appliesTo(tenant, company) || !tenant.equals(original.path("tenantId").asText())
                || !company.equals(uuid(original, "companyId"))) {
            throw new MissingMapping("MM company and native posting profile do not match");
        }
        ObjectNode result = original.deepCopy();
        JsonNode posting = profile.path("nativePosting");
        ObjectNode nativePosting = result.putObject("nativePosting");
        putRef(nativePosting, "organizationRef", posting, "organizationRef");
        switch (result.path("documentKind").asText()) {
            case "RECEIPT" -> copyRefs(nativePosting.putObject("receipt"), posting.path("receipt"),
                List.of("supplierRef", "contractRef", "contraAccountRef", "subconto1Ref", "subconto2Ref"));
            case "ISSUE" -> copyRefs(nativePosting.putObject("issue"), posting.path("issue"),
                List.of("expenseAccountRef", "expenseCodeRef"));
            case "TRANSFER" -> {
                putRef(nativePosting, "transitWarehouseRef", posting, "transitWarehouseRef");
                result.put("nativeSourceWarehouseId", warehouse(result, "sourceWarehouseId"));
                result.put("nativeDestinationWarehouseId", warehouse(result, "destinationWarehouseId"));
            }
            default -> throw new MissingMapping("Unsupported MM document kind for native posting");
        }
        JsonNode lines = result.path("lines");
        if (!lines.isArray() || lines.isEmpty()) throw new MissingMapping("MM document has no posting lines");
        for (JsonNode entry : lines) {
            if (!(entry instanceof ObjectNode line)) throw new MissingMapping("Invalid MM posting line");
            String mmMaterial = uuid(line, "materialId").toString();
            JsonNode material = profile.path("materials").path(mmMaterial);
            if (material.isMissingNode()) throw new MissingMapping("Material " + mmMaterial);
            copyRefs(line, material, List.of("nativeMaterialId", "nativeAccountId", "nativeUnitId"));
            line.put("nativeWarehouseId", warehouse(line, "warehouseId"));
        }
        return result;
    }

    private String warehouse(JsonNode entry, String field) {
        String mmWarehouse = uuid(entry, field).toString();
        return uuid(profile.path("warehouses"), mmWarehouse, "Warehouse " + mmWarehouse).toString();
    }

    private static void copyRefs(ObjectNode target, JsonNode references, List<String> fields) {
        for (String field : fields) putRef(target, field, references, field);
    }

    private static void putRef(ObjectNode target, String targetField, JsonNode references, String sourceField) {
        target.put(targetField, uuid(references, sourceField).toString());
    }

    private static UUID uuid(JsonNode source, String field) { return uuid(source, field, field); }

    private static UUID uuid(JsonNode source, String field, String description) {
        JsonNode node = source.path(field);
        String value = node.asText();
        try {
            UUID id = UUID.fromString(value);
            if (node.isTextual() && id.toString().equalsIgnoreCase(value)
                    && (id.getMostSignificantBits() != 0 || id.getLeastSignificantBits() != 0)) return id;
        } catch (IllegalArgumentException ignored) {
            // Report only the missing mapping field.
        }
        throw new MissingMapping(description);
    }

    static final class MissingMapping extends IllegalArgumentException {
        MissingMapping(String message) { super(message); }
    }
}
