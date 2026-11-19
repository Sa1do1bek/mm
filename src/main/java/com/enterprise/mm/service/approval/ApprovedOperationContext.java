package com.enterprise.mm.service.approval;

import com.enterprise.mm.error.BusinessException;
import java.util.UUID;
import java.util.function.Supplier;

/** Permits a domain command only while applying its approved request. */
public final class ApprovedOperationContext {
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();

    private ApprovedOperationContext() {}

    public static void run(String moduleKey, UUID targetId, Runnable command) {
        call(moduleKey, targetId, () -> { command.run(); return null; });
    }

    public static <T> T call(String moduleKey, UUID targetId, Supplier<T> command) {
        if (CURRENT.get() != null) {
            throw new BusinessException("Nested approved operations are not supported");
        }
        CURRENT.set(new Permit(moduleKey, targetId));
        try {
            return command.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static void require(String moduleKey, UUID targetId) {
        Permit permit = CURRENT.get();
        if (permit == null || !permit.moduleKey().equals(moduleKey) || !permit.targetId().equals(targetId)) {
            throw new BusinessException("An approved operation request is required");
        }
    }

    private record Permit(String moduleKey, UUID targetId) {}
}
