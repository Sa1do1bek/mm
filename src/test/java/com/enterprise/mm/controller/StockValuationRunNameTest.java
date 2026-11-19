package com.enterprise.mm.controller;

import com.enterprise.mm.config.LocaleConfig;
import com.enterprise.mm.entity.StockValuationRun;
import com.enterprise.mm.mapper.StockValuationMapper;
import com.enterprise.mm.repository.*;
import com.enterprise.mm.service.*;
import com.enterprise.mm.web.LocalizedNameResponseAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockValuationRunNameTest {
    private final UUID id = UUID.randomUUID();
    private final StockValuationRunRepository repository = mock(StockValuationRunRepository.class);
    private StockValuationRun stored;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var jobs = mock(StockValuationCostJobService.class);
        var queued = new com.enterprise.mm.payload.StockValuationCostJobResponse(
            UUID.randomUUID(), null, null, null, "QUEUED", null, null, 0, 0, 0, 0, null, null, null, null, null);
        when(jobs.startJob(any(), any(), any())).thenReturn(queued);
        when(jobs.getJob(any())).thenReturn(queued);
        var service = new StockValuationRunService(repository, mock(StockValuationRunItemRepository.class),
            mock(StockValuationCostJobResultRepository.class), jobs, mock(WarehouseRepository.class), mock(StockValuationRepository.class));
        var controller = new StockValuationController(mock(StockValuationRecordService.class), mock(StockValuationService.class),
            mock(StockValuationMapper.class), mock(InventoryValuationPolicyService.class), mock(StockValuationCostSelectionService.class),
            jobs, mock(StockValuationHistoryService.class), service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setLocaleResolver(new LocaleConfig().localeResolver())
            .setControllerAdvice(new LocalizedNameResponseAdvice(new ObjectMapper().findAndRegisterModules())).build();
        lenient().when(repository.save(any(StockValuationRun.class))).thenAnswer(inv -> {
            stored = inv.getArgument(0);
            stored.setId(id);
            stored.setName(com.enterprise.mm.util.BusinessNameGenerator.normalize(stored.getName(), "StockValuationRun"));
            return stored;
        });
        lenient().when(repository.findScoped(eq(id), any(), any())).thenAnswer(inv -> Optional.ofNullable(stored));
    }

    @Test
    void storesThreeNamesAndReturnsRequestedNamePlusAllEditorFields() throws Exception {
        mvc.perform(post("/api/valuation/runs").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .header("Accept-Language", "uz").content("""
                {"scopeType":"GLOBAL","nameUz":"Sentabr baholashi","nameRu":"Оценка за сентябрь","nameEn":"September valuation"}
                """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Sentabr baholashi"));
        assertThat(stored.getName()).isEqualTo("ru: Оценка за сентябрь\nen: September valuation\nuz: Sentabr baholashi");
        for (String language : new String[]{"uz", "ru", "en"}) {
            String expected = switch (language) {
                case "uz" -> "Sentabr baholashi";
                case "ru" -> "Оценка за сентябрь";
                default -> "September valuation";
            };
            mvc.perform(get("/api/valuation/runs/" + id).accept(MediaType.APPLICATION_JSON).header("Accept-Language", language))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(expected))
                .andExpect(jsonPath("$.data.nameUz").value("Sentabr baholashi"))
                .andExpect(jsonPath("$.data.nameRu").value("Оценка за сентябрь"))
                .andExpect(jsonPath("$.data.nameEn").value("September valuation"));
        }
    }

    @Test
    void updatingOneTranslationKeepsTheOthers() throws Exception {
        seed("ru: Русское имя\nen: English name\nuz: Eski nom");
        mvc.perform(put("/api/valuation/runs/" + id).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .header("Accept-Language", "uz").content("""
                {"scopeType":"GLOBAL","nameUz":"Yangi nom"}
                """))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Yangi nom"))
            .andExpect(jsonPath("$.data.nameRu").value("Русское имя"))
            .andExpect(jsonPath("$.data.nameEn").value("English name"));
    }

    @Test
    void missingTranslationUsesExistingProjectFallback() throws Exception {
        mvc.perform(post("/api/valuation/runs").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .header("Accept-Language", "ru").content("""
                {"scopeType":"GLOBAL","nameUz":"  Faqat o‘zbekcha  "}
                """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Faqat o‘zbekcha"))
            .andExpect(jsonPath("$.data.nameUz").value("Faqat o‘zbekcha"))
            .andExpect(jsonPath("$.data.nameRu").value("Faqat o‘zbekcha"));
    }

    @Test
    void legacyPlainNameStillWorksAndCanBeEdited() throws Exception {
        mvc.perform(post("/api/valuation/runs").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .content("""
                {"scopeType":"GLOBAL","name":"Samsung"}
                """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("Samsung"))
            .andExpect(jsonPath("$.data.nameUz").value("Samsung"))
            .andExpect(jsonPath("$.data.nameRu").value("Samsung"))
            .andExpect(jsonPath("$.data.nameEn").value("Samsung"));
    }

    @Test
    void omittedNamesOnUpdateKeepStoredTranslations() throws Exception {
        String original = "ru: Русское имя\nen: English name\nuz: Eski nom";
        seed(original);
        mvc.perform(put("/api/valuation/runs/" + id).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .content("{\"scopeType\":\"GLOBAL\"}"))
            .andExpect(status().isOk());
        assertThat(stored.getName()).isEqualTo(original);
    }

    @Test
    void eachTranslationHasItsOwn240CharacterLimit() throws Exception {
        String longName = "a".repeat(240);
        String valid = new ObjectMapper().writeValueAsString(java.util.Map.of("scopeType", "GLOBAL",
            "nameUz", longName, "nameRu", longName, "nameEn", longName));
        mvc.perform(post("/api/valuation/runs").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.nameUz").value(longName));
        assertThat(stored.getName().length()).isGreaterThan(720);
        for (String field : new String[]{"nameUz", "nameRu", "nameEn"}) {
            String invalid = new ObjectMapper().writeValueAsString(java.util.Map.of("scopeType", "GLOBAL", field, "a".repeat(241)));
            mvc.perform(post("/api/valuation/runs").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        }
    }

    private void seed(String name) {
        stored = new StockValuationRun();
        stored.setId(id);
        stored.setName(name);
        stored.setStatus(StockValuationRun.STATUS_DRAFT);
    }
}
