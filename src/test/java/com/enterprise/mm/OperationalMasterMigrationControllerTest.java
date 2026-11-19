package com.enterprise.mm;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.enterprise.mm.controller.OperationalMasterMigrationController;
import com.enterprise.mm.payload.CatalogMasterMigrationDtos.*;
import com.enterprise.mm.service.OperationalMasterMigrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
class OperationalMasterMigrationControllerTest {
    private final OperationalMasterMigrationService service = mock(OperationalMasterMigrationService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID company = UUID.randomUUID();
    private MockMvc mvc;
    @BeforeEach void setUp() {
        var validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new OperationalMasterMigrationController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).setValidator(validator).build();
    }
    @Test void explicitTenantAndCompanyAreRequired() throws Exception {
        String body = mapper.writeValueAsString(command());
        mvc.perform(post("/api/migration/operational-masters/suppliers").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/migration/operational-masters/suppliers").header("X-Tenant-Id", "master-test").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void invalidSourceAndOversizedChunksRejectBeforeService() throws Exception {
        var p = command();
        for (var rows : List.of(List.of(new MasterRow("short", "Catalog45", null, p.rows().getFirst().payload())), java.util.Collections.nCopies(201, p.rows().getFirst()))) {
            var bad = new MasterChunkRequest(p.importId(), p.chunkId(), p.sourceManifestSha256(), p.preparedManifestSha256(), rows);
            mvc.perform(post("/api/migration/operational-masters/suppliers").header("X-Tenant-Id", "master-test").header("X-Company-Id", company)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(bad))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void responseKeepsKindAndExactManifestAndMappingIdentity() throws Exception {
        var p = command(); var master = UUID.randomUUID();
        when(service.importMasters(eq("suppliers"), eq("master-test"), eq(company), any())).thenReturn(new MasterChunkResponse(
            p.importId(), p.chunkId(), p.sourceManifestSha256(), p.preparedManifestSha256(), "master-test", company, "suppliers",
            List.of(new MasterRowResponse("1".repeat(32), master, "CATEGORY-1", true))));
        mvc.perform(post("/api/migration/operational-masters/suppliers").header("X-Tenant-Id", "master-test").header("X-Company-Id", company)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(p))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kind").value("suppliers")).andExpect(jsonPath("$.data.rows[0].masterId").value(master.toString()));
    }
    @Test void supplierNameRepairRequiresScopedValidSourceAndVersion() throws Exception {
        UUID id=UUID.randomUUID();
        var request=new SupplierNameRepairRequest("1".repeat(32),"b".repeat(64),"Transit Line Ltd.",0L);
        mvc.perform(post("/api/migration/operational-masters/supplier-names/"+id)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isBadRequest());
        for(var bad:List.of(new SupplierNameRepairRequest("short","b".repeat(64),"Transit Line Ltd.",0L),
            new SupplierNameRepairRequest("1".repeat(32),"b".repeat(64),"Transit Line Ltd.",-1L))) {
            mvc.perform(post("/api/migration/operational-masters/supplier-names/"+id).header("X-Tenant-Id","master-test").header("X-Company-Id",company)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(bad))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/api/migration/operational-masters/supplier-names/"+id).header("X-Tenant-Id","master-test").header("X-Company-Id",company)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isOk());
        verify(service).repairSupplierName(id,"master-test",company,request);
    }
    private MasterChunkRequest command() {
        return new MasterChunkRequest(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64),
            List.of(new MasterRow("1".repeat(32), "Catalog45", null, mapper.createObjectNode().put("code", "CATEGORY-1").put("name", "Category"))));
    }
}
