package com.enterprise.mm.supply.workspace.error;

import com.enterprise.mm.error.ConflictException;

public class SupplyAssignmentConflictException extends ConflictException {
    public SupplyAssignmentConflictException(String code, String message) {
        super(code + ": " + message, "supply.workspace.assignment.conflict", code);
    }
}
