package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class ContractAiJsonSupport {
    private final ObjectMapper objectMapper;

    List<ContractAiFindingDto> mergeFindings(List<ContractAiFindingDto> base, String aiText) {
        List<ContractAiFindingDto> parsed = parseFindings(aiText);
        if (parsed.isEmpty()) {
            return base;
        }
        List<ContractAiFindingDto> merged = new ArrayList<>(base);
        for (ContractAiFindingDto finding : parsed) {
            boolean exists = merged.stream().anyMatch(existing -> existing.title().equalsIgnoreCase(finding.title()));
            if (!exists) {
                merged.add(finding);
            }
        }
        return merged;
    }
    List<ContractAiRecommendationDto> mergeRecommendations(List<ContractAiRecommendationDto> base, String aiText) {
        List<ContractAiRecommendationDto> parsed = parseRecommendations(aiText);
        if (parsed.isEmpty()) {
            return base;
        }
        List<ContractAiRecommendationDto> merged = new ArrayList<>(base);
        for (ContractAiRecommendationDto recommendation : parsed) {
            boolean exists = merged.stream().anyMatch(existing -> existing.action().equalsIgnoreCase(recommendation.action()));
            if (!exists) {
                merged.add(recommendation);
            }
        }
        return distinctRecommendations(merged);
    }
    List<ContractAiRecommendationDto> distinctRecommendations(List<ContractAiRecommendationDto> recommendations) {
        List<ContractAiRecommendationDto> result = new ArrayList<>();
        for (ContractAiRecommendationDto recommendation : recommendations) {
            boolean exists = result.stream().anyMatch(existing -> existing.action().equalsIgnoreCase(recommendation.action()));
            if (!exists) {
                result.add(recommendation);
            }
        }
        return result;
    }
    private List<ContractAiFindingDto> parseFindings(String aiText) {
        JsonNode root = readJsonFragment(aiText);
        JsonNode findings = root == null ? null : (root.isArray() ? root : root.path("findings"));
        if (findings == null || !findings.isArray()) {
            return List.of();
        }
        List<ContractAiFindingDto> result = new ArrayList<>();
        for (JsonNode node : findings) {
            String title = text(node, "title");
            if (title == null) {
                continue;
            }
            result.add(new ContractAiFindingDto(
                enumValue(text(node, "severity"), "INFO"),
                firstNonBlank(text(node, "category"), "AI"),
                title,
                firstNonBlank(text(node, "details"), title)
            ));
        }
        return result;
    }
    private List<ContractAiRecommendationDto> parseRecommendations(String aiText) {
        JsonNode root = readJsonFragment(aiText);
        JsonNode recommendations = root == null ? null : root.path("recommendations");
        if (recommendations == null || !recommendations.isArray()) {
            return List.of();
        }
        List<ContractAiRecommendationDto> result = new ArrayList<>();
        for (JsonNode node : recommendations) {
            String action = text(node, "action");
            if (action == null) {
                continue;
            }
            result.add(new ContractAiRecommendationDto(
                enumValue(text(node, "priority"), "LOW"),
                action,
                firstNonBlank(text(node, "reason"), action)
            ));
        }
        return result;
    }
    private JsonNode readJsonFragment(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            int objectStart = text.indexOf('{');
            int objectEnd = text.lastIndexOf('}');
            if (objectStart >= 0 && objectEnd > objectStart) {
                try {
                    return objectMapper.readTree(text.substring(objectStart, objectEnd + 1));
                } catch (Exception ignoredAgain) {
                    return null;
                }
            }
            return null;
        }
    }
    private String enumValue(String value, String fallback) {
        return firstNonBlank(value, fallback).replace('-', '_').toUpperCase(Locale.ROOT);
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
