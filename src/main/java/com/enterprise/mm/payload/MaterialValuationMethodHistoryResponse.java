package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MaterialValuationMethodHistoryResponse extends BaseRecordDto {
    private UUID materialId;
    private String oldValuationMethod;
    private String newValuationMethod;
    private LocalDateTime effectiveAt;
    private String changeReason;
}
