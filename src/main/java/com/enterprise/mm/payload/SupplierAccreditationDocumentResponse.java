package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierAccreditationDocumentResponse extends BaseRecordDto {
    private UUID applicationId;
    private String applicationCode;
    private String applicationName;
    private String documentType;
    private UUID attachmentId;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
}
