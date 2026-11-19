package com.enterprise.mm.service.approval;

import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ApprovalModuleRegistry {
    private final Map<String, ApprovalModuleAdapter> adapters;

    public ApprovalModuleRegistry(List<ApprovalModuleAdapter> adapters) {
        this.adapters = adapters.stream()
            .flatMap(adapter -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(adapter.moduleKey()),
                adapter.additionalModuleKeys().stream()
            ).map(key -> Map.entry(normalize(key), adapter)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public ApprovalModuleAdapter adapter(String moduleKey) {
        ApprovalModuleAdapter adapter = adapters.get(normalize(moduleKey));
        if (adapter == null) {
            throw new BusinessException("Approval module is not supported: " + moduleKey + ". Supported modules: " + supportedModules());
        }
        return adapter;
    }

    public List<String> supportedModules() {
        return adapters.keySet().stream()
            .sorted()
            .toList();
    }

    public static String normalize(String moduleKey) {
        if (moduleKey == null || moduleKey.isBlank()) {
            return "";
        }
        return moduleKey.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
}
