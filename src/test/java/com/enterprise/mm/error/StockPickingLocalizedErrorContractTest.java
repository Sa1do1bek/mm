package com.enterprise.mm.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.enterprise.mm.controller.FixedAssetStockFactController;
import com.enterprise.mm.controller.WarehouseStockFactController;
import com.enterprise.mm.service.FixedAssetStockFactService;
import com.enterprise.mm.service.WarehouseStockFactService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StockPickingLocalizedErrorContractTest {
    private static final String ROOT = "/api/workspaces/warehouse-execution/manual";
    private final WarehouseStockFactService materials = mock(WarehouseStockFactService.class);
    private final FixedAssetStockFactService assets = mock(FixedAssetStockFactService.class);
    private GlobalExceptionHandler handler;
    private MockMvc mvc;

    @BeforeEach void setup() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("message");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        handler = new GlobalExceptionHandler(messages);
        mvc = MockMvcBuilders.standaloneSetup(new WarehouseStockFactController(materials,
            org.mockito.Mockito.mock(com.enterprise.mm.service.ManualReceiptAccountDraftService.class)), new FixedAssetStockFactController(assets))
            .setControllerAdvice(handler).build();
    }

    static Stream<Arguments> errors() {
        return Stream.of(
            Arguments.of("uz", "stockPicking.insufficient", "STOCK_PICKING_INSUFFICIENT", "So‘ralgan miqdorni chiqarish uchun mavjud qoldiq yetarli emas."),
            Arguments.of("ru", "stockPicking.insufficient", "STOCK_PICKING_INSUFFICIENT", "Недостаточно доступного остатка для выдачи указанного количества."),
            Arguments.of("en", "stockPicking.insufficient", "STOCK_PICKING_INSUFFICIENT", "Not enough stock is available to issue the requested quantity."),
            Arguments.of("uz", "stockPicking.unavailable", "STOCK_PICKING_UNAVAILABLE", "Tanlangan qoldiqdan chiqim qilish mumkin emas."),
            Arguments.of("ru", "stockPicking.unavailable", "STOCK_PICKING_UNAVAILABLE", "Выбранный остаток недоступен для выдачи."),
            Arguments.of("en", "stockPicking.unavailable", "STOCK_PICKING_UNAVAILABLE", "The selected stock position is unavailable for issue.")
        );
    }

    @ParameterizedTest
    @MethodSource("errors")
    void materialAndFixedAssetPreviewAndPostingReturnLocalizedMessagesAndStableCodes(
        String language, String key, String code, String message) throws Exception {
        // A distinct fallback ensures a missing translation fails even for English.
        var error = new BusinessException("Internal untranslated stock detail", key);
        when(materials.previewIssue(any())).thenThrow(error);
        when(assets.previewIssue(any())).thenThrow(error);
        when(materials.record(any(), isNull(), anyString(), eq(false))).thenThrow(error);
        when(assets.record(any(), isNull(), anyString(), eq(false))).thenThrow(error);
        String assetRequest = """
            {"movementDate":"%s","lines":[{"materialId":"%s","quantity":14,"pickingMethod":"FIFO","allocationMode":"AUTO"}]}
            """.formatted(LocalDate.now(), UUID.randomUUID());
        String materialRequest = "{\"warehouseId\":\"" + UUID.randomUUID() + "\"," + assetRequest.substring(1);
        for (boolean fixedAsset : new boolean[]{false, true}) {
            String request = fixedAsset ? assetRequest : materialRequest;
            String preview = fixedAsset ? "/fixed-asset-stock/issue-preview" : "/stock-issue/preview";
            String issue = fixedAsset ? "/fixed-asset-stock/issue" : "/stock-issue";
            mvc.perform(post(ROOT + preview).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(request)
                .header("Accept-Language", language))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.errorCode").value(code));
            mvc.perform(multipart(ROOT + issue)
                .file(new MockMultipartFile("request", "request.json", "application/json", request.getBytes(StandardCharsets.UTF_8)))
                .accept(MediaType.APPLICATION_JSON)
                .header("Accept-Language", language).header("Idempotency-Key", "localized-issue"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.errorCode").value(code));
        }
    }

    @Test void languageVariantsAndMissingHeaderKeepTheSameStockPickingCode() {
        for (String language : new String[]{"uz-UZ,uz;q=0.9", "ru-RU,ru;q=0.9", "en-US,en;q=0.9", ""}) {
            var request = new MockHttpServletRequest();
            if (!language.isEmpty()) request.addHeader("Accept-Language", language);
            var response = handler.business(new BusinessException("Internal untranslated stock detail", "stockPicking.insufficient"), request);
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().errorCode()).isEqualTo("STOCK_PICKING_INSUFFICIENT");
            String expected = language.startsWith("uz") ? "So‘ralgan miqdorni chiqarish uchun mavjud qoldiq yetarli emas."
                : language.startsWith("ru") ? "Недостаточно доступного остатка для выдачи указанного количества."
                : "Not enough stock is available to issue the requested quantity.";
            assertThat(response.getBody().message()).isEqualTo(expected);
        }
    }

    @Test void unrelatedBusinessErrorsKeepTheirExistingCode() {
        var response = handler.business(new BusinessException("Other business error"), new MockHttpServletRequest());
        assertThat(response.getBody().errorCode()).isEqualTo("BusinessException");
        assertThat(response.getBody().message()).isEqualTo("Other business error");
    }
}
