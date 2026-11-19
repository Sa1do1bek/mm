package com.enterprise.mm.supply.workspace.domain;

import com.enterprise.mm.supply.workspace.enums.SupplyCommitmentKind;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SupplyCommitmentKey(SupplyCommitmentKind kind, UUID id) {
    private static final Pattern CANONICAL = Pattern.compile(
        "^(SCHEDULE|PO):([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    public SupplyCommitmentKey {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(id, "id is required");
    }

    public String canonical() {
        return kind.name() + ":" + id;
    }

    public static SupplyCommitmentKey parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Supply commitment key is required");
        }
        Matcher matcher = CANONICAL.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid canonical supply commitment key");
        }
        try {
            SupplyCommitmentKey key = new SupplyCommitmentKey(
                SupplyCommitmentKind.valueOf(matcher.group(1)), UUID.fromString(matcher.group(2)));
            if (!key.canonical().equals(value)) {
                throw new IllegalArgumentException("Non-canonical supply commitment key");
            }
            return key;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw new IllegalArgumentException("Invalid canonical supply commitment key", exception);
        }
    }
}
