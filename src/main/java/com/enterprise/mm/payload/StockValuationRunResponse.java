package com.enterprise.mm.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.enterprise.mm.enums.StockValuationRunScope;
import com.enterprise.mm.enums.ValuationMethod;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class StockValuationRunResponse extends BaseRecordDto {
    private String nameUz;
    private String nameRu;
    private String nameEn;
    private StockValuationRunScope scopeType;
    private List<WarehouseIDAndNameDto> warehouses;
    private ValuationMethod valuationMethod;
    private String descriptionUz;
    private String descriptionRu;
    private String descriptionEn;
    private UUID jobId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
    private BigDecimal progressPercent;
    @JsonIgnore
    @Override
    public String getCode() {
        return super.getCode();
    }

    @Override
    @JsonIgnore
    public boolean isDeleted() {
        return super.isDeleted();
    }
}
