package com.enterprise.mm.erp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OneCNativePostingProfileTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String TENANT = "default";
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID MATERIAL = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID NATIVE_WAREHOUSE = UUID.randomUUID();
    private static final UUID NATIVE_MATERIAL = UUID.randomUUID();

    private ObjectNode profile() {
        var data = mapper.createObjectNode().put("tenantId", TENANT).put("companyId", COMPANY.toString());
        var posting = data.putObject("nativePosting").put("organizationRef", UUID.randomUUID().toString());
        posting.putObject("issue").put("expenseAccountRef", UUID.randomUUID().toString())
            .put("expenseCodeRef", UUID.randomUUID().toString());
        data.putObject("warehouses").put(WAREHOUSE.toString(), NATIVE_WAREHOUSE.toString());
        data.putObject("materials").putObject(MATERIAL.toString())
            .put("nativeMaterialId", NATIVE_MATERIAL.toString())
            .put("nativeAccountId", UUID.randomUUID().toString()).put("nativeUnitId", UUID.randomUUID().toString());
        return data;
    }

    private ObjectNode event() {
        var event = mapper.createObjectNode().put("tenantId", TENANT).put("companyId", COMPANY.toString())
            .put("documentKind", "ISSUE");
        event.putArray("lines").addObject().put("materialId", MATERIAL.toString()).put("warehouseId", WAREHOUSE.toString());
        return event;
    }

    @Test
    void resolvesExplicitReferencesWithoutMutatingOriginalAndPinsScope() {
        var resolver = new OneCNativePostingProfile(profile());
        var original = event();
        var resolved = resolver.resolve(TENANT, COMPANY, original);
        assertFalse(original.has("nativePosting"));
        assertEquals(NATIVE_MATERIAL.toString(), resolved.at("/lines/0/nativeMaterialId").asText());
        assertEquals(NATIVE_WAREHOUSE.toString(), resolved.at("/lines/0/nativeWarehouseId").asText());
        assertFalse(resolver.appliesTo(TENANT, UUID.randomUUID()));
        assertThrows(OneCNativePostingProfile.MissingMapping.class,
            () -> resolver.resolve("another-tenant", COMPANY, original));
    }

    @Test
    void missingReferenceLeavesOriginalUntouched() {
        var resolver = new OneCNativePostingProfile(profile());
        var original = event();
        ((ObjectNode) original.at("/lines/0")).put("warehouseId", UUID.randomUUID().toString());
        assertThrows(OneCNativePostingProfile.MissingMapping.class, () -> resolver.resolve(TENANT, COMPANY, original));
        assertFalse(original.has("nativePosting"));
        assertFalse(original.at("/lines/0").has("nativeMaterialId"));
    }
}
