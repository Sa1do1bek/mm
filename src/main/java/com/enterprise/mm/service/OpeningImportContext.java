package com.enterprise.mm.service;

import java.util.UUID;
import java.util.function.Supplier;

/** Internal capability for the managed importer; never derived from an HTTP reference string. */
final class OpeningImportContext {
    private record Scope(UUID companyId, UUID importId) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private OpeningImportContext() {}
    static boolean allows(UUID companyId) {
        return CURRENT.get() != null && CURRENT.get().companyId().equals(companyId);
    }
    static boolean allows(UUID companyId, UUID importId) {
        return allows(companyId) && CURRENT.get().importId().equals(importId);
    }
    static <T> T within(UUID companyId, UUID importId, Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(companyId, importId));
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
