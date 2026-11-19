package com.enterprise.mm.payload;

import java.util.UUID;

public record BankResponse(UUID id, String name, String mfo, String swift, String type, String city) {
}
