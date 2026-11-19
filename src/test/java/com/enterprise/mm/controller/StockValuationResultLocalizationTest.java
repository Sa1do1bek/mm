package com.enterprise.mm.controller;

import com.enterprise.mm.config.LocaleConfig;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.mapper.StockValuationMapper;
import com.enterprise.mm.payload.StockValuationCostJobResultResponse;
import com.enterprise.mm.payload.StockValuationRunItemResponse;
import com.enterprise.mm.payload.StockValuationSelectedStocksResponse.StockCost;
import com.enterprise.mm.service.*;
import com.enterprise.mm.web.LocalizedNameResponseAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StockValuationResultLocalizationTest {
    @ParameterizedTest
    @ValueSource(strings = {"uz", "ru", "en", "uz-UZ", "de", ""})
    void returnsStableStatusesAndLocalizedReasonsAcrossResultEndpoints(String language) throws Exception {
        var selection = mock(StockValuationCostSelectionService.class);
        var jobs = mock(StockValuationCostJobService.class);
        var runs = mock(StockValuationRunService.class);
        var controller = new StockValuationController(mock(StockValuationRecordService.class),
            mock(StockValuationService.class), mock(StockValuationMapper.class),
            mock(InventoryValuationPolicyService.class), selection, jobs, mock(StockValuationHistoryService.class), runs);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var mvc = MockMvcBuilders.standaloneSetup(controller)
            .setLocaleResolver(new LocaleConfig().localeResolver())
            .setControllerAdvice(new LocalizedNameResponseAdvice(mapper)).build();
        String original = "Not enough stock for LIFO valuation. Missing: 10.0000";
        var bad = new StockCost(null, "Steel", null, "Main", BigDecimal.TEN, ValuationMethod.LIFO, null, false, original);
        var good = new StockCost(null, "Steel", null, "Main", BigDecimal.TEN, ValuationMethod.LIFO, BigDecimal.TEN, true, null);
        when(selection.calculateStockCosts((UUID) null, (UUID) null)).thenReturn(List.of(bad, good));
        when(jobs.getResults(any(), any())).thenReturn(new PageImpl<>(List.of(
            new StockValuationCostJobResultResponse(null, "Steel", null, "Main", BigDecimal.TEN, ValuationMethod.LIFO, null, false, original),
            new StockValuationCostJobResultResponse(null, "Steel", null, "Main", BigDecimal.TEN, ValuationMethod.LIFO, BigDecimal.TEN, true, null))));
        when(runs.items(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
            new StockValuationRunItemResponse(null, null, "Steel", null, "Main", BigDecimal.TEN,null, ValuationMethod.LIFO, null, null, null, false, original, 1, null),
            new StockValuationRunItemResponse(null, null, "Steel", null, "Main", BigDecimal.TEN,null, ValuationMethod.LIFO, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, true, null, 2, null))));
        String expectedError = switch (language) {
            case "ru" -> "Для расчёта LIFO недостаточно остатков партий. Не хватает: 10.0000.";
            case "en" -> original;
            default -> "LIFO hisoblash uchun partiya qoldig‘i 10.0000 birlikka yetishmayapti.";
        };
        String id = UUID.randomUUID().toString();
        for (String path : List.of("/cost/stocks", "/costs/stocks", "/costs/stocks/jobs/" + id + "/results", "/runs/" + id + "/items")) {
            String rows = path.endsWith("stocks") ? "$.data.stocks" : "$.data.content";
            var request = get("/api/valuation" + path).accept(MediaType.APPLICATION_JSON);
            if (!language.isEmpty()) request.header("Accept-Language", language);
            mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rows + "[0].status").value("FAILED"))
                .andExpect(jsonPath(rows + "[0].statusLabel").doesNotExist())
                .andExpect(jsonPath(rows + "[0].available").value(false))
                .andExpect(jsonPath(rows + "[0].reason").value(expectedError))
                .andExpect(jsonPath(rows + "[0].errorMessage").doesNotExist())
                .andExpect(jsonPath(rows + "[1].status").value("COMPLETED"))
                .andExpect(jsonPath(rows + "[1].statusLabel").doesNotExist())
                .andExpect(jsonPath(rows + "[1].available").value(true))
                .andExpect(jsonPath(rows + "[1].reason").isEmpty());
        }
        assertThat(bad.errorMessage()).isEqualTo(original);
    }
    @org.junit.jupiter.api.AfterEach
    void clearLocale() {
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    @org.junit.jupiter.api.Test
    void sameStoredRowCanBeReadInDifferentLanguages() {
        String original = "Not enough stock for FIFO valuation. Missing: 2.5000";
        var row = new StockValuationCostJobResultResponse(null, null, null, null, BigDecimal.TEN,
            ValuationMethod.FIFO, null, false, original);
        var mapper = new ObjectMapper();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("uz"));
        var uz = mapper.valueToTree(row);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("ru"));
        var ru = mapper.valueToTree(row);
        assertThat(uz.path("reason").asText()).isEqualTo("FIFO hisoblash uchun partiya qoldig‘i 2.5000 birlikka yetishmayapti.");
        assertThat(ru.path("reason").asText()).isEqualTo("Для расчёта FIFO недостаточно остатков партий. Не хватает: 2.5000.");
        assertThat(row.errorMessage()).isEqualTo(original);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "uz,Materialning tasdiqlangan bozor narxi topilmadi.",
        "ru,Утверждённая рыночная цена материала не найдена.",
        "en,Approved market price not found for material"
    })
    void preservesSpecificBusinessReason(String language, String expected) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        var row = new StockValuationCostJobResultResponse(null, null, null, null, null,
            ValuationMethod.MARKET_PRICE, null, false, "Approved market price not found for material");
        assertThat(new ObjectMapper().valueToTree(row).path("reason").asText()).isEqualTo(expected);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "uz,Hisoblash amalga oshmadi. Ma’lumotlarni tekshiring yoki texnik yordamga murojaat qiling.",
        "ru,Не удалось выполнить расчёт. Проверьте данные или обратитесь в техническую поддержку.",
        "en,Valuation calculation failed. Check the calculation data or contact support."
    })
    void unexpectedTechnicalErrorsUseLocalizedFallback(String language, String expected) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        var row = new StockValuationCostJobResultResponse(null, null, null, null, null,
            ValuationMethod.FIFO, null, false, "Internal database exception: server:5432");
        assertThat(new ObjectMapper().valueToTree(row).path("reason").asText()).isEqualTo(expected);
    }
}
