package com.enterprise.mm.payload;

import com.enterprise.mm.enums.StockValuationRunScope;
import com.enterprise.mm.enums.ValuationMethod;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockValuationRunCreateRequest {
    @Size(max = 80)
    private String code;
    @Size(max = 240)
    private String name;
    @Size(max = 240)
    private String nameUz;
    @Size(max = 240)
    private String nameRu;
    @Size(max = 240)
    private String nameEn;
    private String description;
    @NotNull
    private StockValuationRunScope scopeType;
    private UUID warehouseId;
    private List<UUID> warehouseIds;
    @JsonAlias({"methodType", "method_type", "method", "valuation_method"})
    private ValuationMethod valuationMethod;
    private String descriptionUz;
    private String descriptionRu;
    private String descriptionEn;
}
