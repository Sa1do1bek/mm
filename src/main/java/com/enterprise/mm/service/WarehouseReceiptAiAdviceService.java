package com.enterprise.mm.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.WarehouseReceiptContextDtos.*;
import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Eligibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Explicit optional advice only. All receipt authorization and posting remain in the command flow. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseReceiptAiAdviceService {
    private final WarehouseReceiptContextService contexts;
    private final MmAiClient client;
    private final ObjectMapper mapper;
    private final ReceiptAdviceContextMapper transport;
    @Value("${mm.ai.receipt-advice.enabled:true}")
    private boolean enabled = true;
    @Value("${mm.ai.receipt-advice.max-request-bytes:2097152}")
    private int maxRequestBytes = 2097152;

    private static final Set<String> STATUSES = Set.of("READY", "NO_CANDIDATES", "INVALID_RESPONSE", "UNAVAILABLE");
    private static final Set<String> REASONS = Set.of("DESCRIPTION_MATCH", "DESCRIPTION_CONFLICT",
        "INSUFFICIENT_EVIDENCE", "MATERIAL_CHOICE_REQUIRED");

    public AiAdvice advise(UUID contractId, AiRequest request) {
        String provider = request.provider() == null ? "MM_AI" : request.provider().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("MM_AI", "CHATGPT", "CLAUDE", "GEMINI").contains(provider))
            throw new BadRequestException("Receipt advice is provided by MM_AI");
        String language = request.language() == null ? "uz" : request.language().strip().toLowerCase(Locale.ROOT);
        if (!Set.of("uz", "ru", "en").contains(language)) throw new BadRequestException("Language must be uz, ru or en");
        int size = request.size() == 0 ? 25 : request.size();
        if (request.page() < 0 || size < 1 || size > 25)
            throw new BadRequestException("AI advice requires a nonnegative page and size between 1 and 25");
        // Ends its own read-only transaction before the network call; never trust a browser snapshot.
        Context context = contexts.get(request.warehouseId(), contractId, request.page(), size);
        Set<String> warnings = new LinkedHashSet<>();
        if (!"MM_AI".equals(provider)) warnings.add("PROVIDER_MANAGED_BY_SERVICE");
        if (context.positions().content().stream().anyMatch(p -> p.candidateCount() > p.materials().size()))
            warnings.add("PREVIEW_ONLY");
        Set<String> eligible = eligible(context);
        if (eligible.isEmpty()) return result("NO_CANDIDATES", context, List.of(), true, warnings, null);
        if (!enabled || !client.isReceiptAdviceConfigured()) {
            warnings.add(enabled ? "AI_NOT_CONFIGURED" : "AI_DISABLED");
            return result("UNAVAILABLE", context, List.of(), false, warnings, null);
        }
        JsonNode outbound = transport.map(context, language);
        JsonNode response;
        long started = System.nanoTime();
        try {
            if (mapper.writeValueAsBytes(outbound).length > maxRequestBytes) {
                warnings.add("CONTEXT_TOO_LARGE");
                return result("UNAVAILABLE", context, List.of(), false, warnings, null);
            }
            response = client.suggestReceiptAdvice(outbound, language);
        } catch (MmAiServiceException failure) {
            // Never log upstream message/body, credentials or material names.
            log.warn("Receipt advice upstream failure: status={}, durationMs={}", failure.httpStatus(), elapsed(started));
            boolean busy = failure.httpStatus() == 503 && "RECEIPT_ADVICE_BUSY".equals(failure.errorCode());
            warnings.add(busy ? "RECEIPT_ADVICE_BUSY" : "AI_SERVICE_ERROR");
            return result("UNAVAILABLE", context, List.of(), false, warnings,
                busy ? failure.retryAfterSeconds() == null ? 5 : failure.retryAfterSeconds() : null);
        } catch (Exception failure) {
            log.warn("Receipt advice transport unavailable: durationMs={}", elapsed(started));
            warnings.add("LLM_UNAVAILABLE");
            return result("UNAVAILABLE", context, List.of(), false, warnings, null);
        }
        // A long AI call must not return advice against a changed scope/profile/material/limit snapshot.
        Context current = contexts.get(request.warehouseId(), contractId, request.page(), size);
        if (!mapper.valueToTree(context).equals(mapper.valueToTree(current))) {
            warnings.add("STALE_CONTEXT");
            return result("UNAVAILABLE", current, List.of(), false, warnings, null);
        }
        AiAdvice answer = validate(response, outbound, context, eligible, warnings, language);
        log.info("Receipt advice completed: status={}, suggestions={}, complete={}, durationMs={}",
            answer.status(), answer.suggestions().size(), answer.complete(), elapsed(started));
        return answer;
    }

    private Set<String> eligible(Context context) {
        Set<String> result = new LinkedHashSet<>();
        for (var position : context.positions().content()) {
            boolean ready = "ACTIVE".equals(position.status())
                && Set.of("MATERIAL", "MATERIAL_GROUP").contains(position.kind())
                && (position.readiness().selectable() || "MATERIAL_CHOICE_REQUIRED".equals(position.readiness().reasonCode()));
            if (!ready) continue;
            for (var material : position.materials()) {
                if (material.materialId() != null && position.specificationId().equals(material.specificationId())
                    && material.receiptSelection() != null && material.receiptSelection().eligibility() != Eligibility.BLOCKED)
                    result.add(WarehouseReceiptContextService.key(position.specificationId(), material.materialId()));
            }
        }
        return result;
    }

    private AiAdvice validate(JsonNode response, JsonNode outbound, Context context, Set<String> eligible,
        Set<String> warnings, String language) {
        if (response == null || !response.isObject() || !text(response, "status", 40)
            || !STATUSES.contains(response.path("status").textValue()) || !response.path("complete").isBoolean()
            || !response.path("message").isTextual() || response.path("message").textValue().length() > 2000
            || !response.path("suggestions").isArray() || response.path("suggestions").size() > 250
            || !transport.echoes(outbound, response.get("backendContext"))) return invalid(context, warnings);
        if (response.has("warnings")) {
            if (!response.path("warnings").isArray() || response.path("warnings").size() > 20) return invalid(context, warnings);
            for (JsonNode warning : response.path("warnings")) {
                if (!warning.isTextual() || warning.textValue().isBlank() || warning.textValue().length() > 100)
                    return invalid(context, warnings);
                warnings.add(warning.textValue());
            }
        }
        String status = response.path("status").textValue();
        if (!"READY".equals(status) && !response.path("suggestions").isEmpty()) return invalid(context, warnings);
        List<AiSuggestion> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean rejected = false;
        for (JsonNode node : response.path("suggestions")) {
            UUID spec, material;
            try {
                spec = UUID.fromString(node.path("specificationId").textValue());
                material = UUID.fromString(node.path("materialId").textValue());
            } catch (Exception invalidId) { rejected = true; continue; }
            String key = WarehouseReceiptContextService.key(spec, material);
            double confidence = node.path("confidence").asDouble(Double.NaN);
            if (!eligible.contains(key) || !node.path("recommendedSelected").isBoolean()
                || !node.path("requiresMaterialChoice").isBoolean() || !node.path("confidence").isNumber()
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || !text(node, "reasonCode", 100) || !REASONS.contains(node.path("reasonCode").textValue())
                || !node.path("reasonText").isTextual() || node.path("reasonText").textValue().length() > 2000
                || !seen.add(key)) { rejected = true; continue; }
            String reason = node.path("reasonCode").textValue();
            boolean choice = node.path("requiresMaterialChoice").booleanValue() || "MATERIAL_CHOICE_REQUIRED".equals(reason);
            boolean selected = node.path("recommendedSelected").booleanValue() && !choice && "DESCRIPTION_MATCH".equals(reason);
            suggestions.add(new AiSuggestion(spec, material, selected, confidence, node.path("reasonText").textValue(), reason, choice));
        }
        if ("READY".equals(status) && suggestions.isEmpty()) return invalid(context, warnings);
        Map<UUID, Long> positives = suggestions.stream().filter(AiSuggestion::recommendedSelected)
            .collect(Collectors.groupingBy(AiSuggestion::specificationId, Collectors.counting()));
        suggestions = suggestions.stream().map(s -> positives.getOrDefault(s.specificationId(), 0L) > 1 && s.recommendedSelected()
            ? new AiSuggestion(s.specificationId(), s.materialId(), false, s.confidence(), choiceMessage(language),
                "MATERIAL_CHOICE_REQUIRED", true) : s).toList();
        boolean complete = response.path("complete").booleanValue() && !rejected
            && ("READY".equals(status) || "NO_CANDIDATES".equals(status));
        if ("READY".equals(status) && suggestions.size() < eligible.size()) complete = false;
        if (rejected) warnings.add("AI_OUTPUT_REJECTED");
        if ("READY".equals(status) && !complete) warnings.add("PARTIAL_ADVICE");
        return result(status, context, suggestions, complete, warnings, null);
    }

    private boolean text(JsonNode node, String field, int max) {
        return node.path(field).isTextual() && !node.path(field).textValue().isBlank() && node.path(field).textValue().length() <= max;
    }

    private AiAdvice invalid(Context context, Set<String> warnings) {
        warnings.add("AI_OUTPUT_REJECTED");
        return result("INVALID_RESPONSE", context, List.of(), false, warnings, null);
    }

    private AiAdvice result(String status, Context context, List<AiSuggestion> suggestions, boolean complete,
        Set<String> warnings, Integer retryAfter) {
        String message = "READY".equals(status) ? "Optional MM AI advice; apply only by user action"
            : "NO_CANDIDATES".equals(status) ? "No executable material previews on this page"
            : "AI advice unavailable; backend recommendations and manual selection remain available";
        return new AiAdvice(status, message, context, suggestions, complete, List.copyOf(warnings), retryAfter);
    }

    private String choiceMessage(String language) {
        return switch (language) {
            case "ru" -> "Выберите материал и количество в пределах общего лимита договора";
            case "en" -> "Choose material quantities within the shared contract limit";
            default -> "Umumiy shartnoma limiti doirasida material va miqdorni tanlang";
        };
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
