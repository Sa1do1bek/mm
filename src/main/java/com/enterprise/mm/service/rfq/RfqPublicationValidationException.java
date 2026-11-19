package com.enterprise.mm.service.rfq;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqPublicationDtos.ValidationIssue;
import java.util.List;

public class RfqPublicationValidationException extends BusinessException {
    private final List<ValidationIssue> issues;

    public RfqPublicationValidationException(List<ValidationIssue> issues) {
        super("RFQ cannot be published because publication validation failed");
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
