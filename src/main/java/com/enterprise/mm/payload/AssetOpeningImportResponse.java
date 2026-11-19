package com.enterprise.mm.payload;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssetOpeningImportResponse extends BaseRecordDto {
    private String status;
    private UUID sourceFileId;
    private LocalDateTime validatedAt;
    private LocalDateTime postedAt;
    private String postedBy;
}
