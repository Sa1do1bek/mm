package com.enterprise.mm.service.approval;

import com.enterprise.mm.error.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ApprovalOperationRegistry {
    private final Map<String, ApprovalOperationHandler> handlers;

    public ApprovalOperationRegistry(List<ApprovalOperationHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
            handler -> ApprovalModuleRegistry.normalize(handler.moduleKey()), Function.identity()
        ));
    }

    public ApprovalOperationHandler handler(String moduleKey) {
        ApprovalOperationHandler handler = handlers.get(ApprovalModuleRegistry.normalize(moduleKey));
        if (handler == null) {
            throw new BusinessException("Approval operation is not supported: " + moduleKey);
        }
        return handler;
    }

    public List<String> moduleKeys() {
        return handlers.keySet().stream().sorted().toList();
    }
}
