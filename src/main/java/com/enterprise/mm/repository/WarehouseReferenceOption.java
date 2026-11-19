package com.enterprise.mm.repository;

import java.util.UUID;

/** The limited identity fields needed by warehouse reference controls. */
public interface WarehouseReferenceOption {
    UUID getId();
    String getCode();
    String getName();
    String getStatus();
}
