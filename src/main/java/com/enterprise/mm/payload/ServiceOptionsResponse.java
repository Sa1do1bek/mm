package com.enterprise.mm.payload;

import java.util.List;

public record ServiceOptionsResponse(
    List<String> serviceOrderStatuses,
    List<String> serviceActStatuses,
    List<String> serviceTypes,
    List<String> approvalActions,
    List<String> attachmentTypes
) {
}
