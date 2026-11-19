package com.enterprise.mm;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enterprise.mm.controller.CatalogMigrationController;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkRequest;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogChunkResponse;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRow;
import com.enterprise.mm.payload.CatalogMigrationDtos.MaterialCatalogRowResponse;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.service.CatalogMigrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class CatalogMigrationControllerTest {
    private final CatalogMigrationService service = mock(CatalogMigrationService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID companyId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CatalogMigrationController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setValidator(validator).build();
    }

    @Test
    void bothExplicitScopeHeadersAreRequired() throws Exception {
        var body = mapper.writeValueAsString(command());
        mvc.perform(post("/api/migration/catalogs/materials").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/migration/catalogs/materials").header("X-Tenant-Id", "catalog-test")
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidOrUnboundedRowsAreRejectedBeforeService() throws Exception {
        var request = command();
        var badSource = new MaterialCatalogChunkRequest(request.importId(), request.chunkId(), request.sourceManifestSha256(),
            request.preparedManifestSha256(), List.of(new MaterialCatalogRow("not-a-full-source-id", new MaterialCreateRequest())));
        mvc.perform(post("/api/migration/catalogs/materials").header("X-Tenant-Id", "catalog-test")
            .header("X-Company-Id", companyId).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(badSource))).andExpect(status().isBadRequest());
        var tooMany = new MaterialCatalogChunkRequest(request.importId(), request.chunkId(), request.sourceManifestSha256(),
            request.preparedManifestSha256(), java.util.Collections.nCopies(201, request.rows().getFirst()));
        mvc.perform(post("/api/migration/catalogs/materials").header("X-Tenant-Id", "catalog-test")
            .header("X-Company-Id", companyId).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(tooMany))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void responseReturnsImportIdentityAndSourceToMaterialIds() throws Exception {
        var request = command();
        UUID materialId = UUID.randomUUID();
        var response = new MaterialCatalogChunkResponse(request.importId(), request.chunkId(), request.sourceManifestSha256(),
            request.preparedManifestSha256(), "catalog-test", companyId,
            List.of(new MaterialCatalogRowResponse(request.rows().getFirst().sourceId(), materialId, "MAT-1", true)));
        when(service.importMaterials(eq("catalog-test"), eq(companyId), org.mockito.ArgumentMatchers.any())).thenReturn(response);
        mvc.perform(post("/api/migration/catalogs/materials").header("X-Tenant-Id", "catalog-test")
            .header("X-Company-Id", companyId).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(request))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.companyId").value(companyId.toString()))
            .andExpect(jsonPath("$.data.rows[0].sourceId").value("1".repeat(32)))
            .andExpect(jsonPath("$.data.rows[0].materialId").value(materialId.toString()));
    }

    private MaterialCatalogChunkRequest command() {
        var payload = new MaterialCreateRequest();
        payload.setCode("MAT-1");
        payload.setName("Material 1");
        return new MaterialCatalogChunkRequest(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64),
            List.of(new MaterialCatalogRow("1".repeat(32), payload)));
    }
}
