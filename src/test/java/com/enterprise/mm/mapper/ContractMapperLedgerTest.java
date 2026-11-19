package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.payload.ContractCreateRequest;
import com.enterprise.mm.payload.ContractUpdateRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractMapperLedgerTest {
    private final ContractMapper mapper = new ContractMapper();

    @Test
    void toEntityDoesNotCopyClientLedgerAmounts() {
        ContractCreateRequest dto = new ContractCreateRequest();
        dto.setName("en: Test contract");
        dto.setSupplierId(UUID.randomUUID());
        dto.setUsedAmount(new BigDecimal("999"));
        dto.setServiceUsedAmount(new BigDecimal("888"));

        Contract entity = mapper.toEntity(dto);

        assertThat(entity.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(entity.getServiceUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void updateEntityPreservesExistingLedgerAmounts() {
        Contract entity = new Contract();
        entity.setUsedAmount(new BigDecimal("150"));
        entity.setServiceUsedAmount(new BigDecimal("40"));

        ContractUpdateRequest dto = new ContractUpdateRequest();
        dto.setName("en: Updated contract");
        dto.setUsedAmount(null);
        dto.setServiceUsedAmount(new BigDecimal("1"));

        mapper.updateEntity(entity, dto);

        assertThat(entity.getUsedAmount()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(entity.getServiceUsedAmount()).isEqualByComparingTo(new BigDecimal("40"));
    }
}
