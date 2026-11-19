package com.enterprise.mm.payload;

import java.util.UUID;

public record MaterialLifecycleRequest(UUID replacementMaterialId, String reason) {
}