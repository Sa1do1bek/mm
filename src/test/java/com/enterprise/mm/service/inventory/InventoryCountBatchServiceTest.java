package com.enterprise.mm.service.inventory;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.InventoryCountBatchDtos.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class InventoryCountBatchServiceTest {
    @Test void duplicateRowsAndMissingRevisionsCannotBeAdmitted() {
        var row=new RowCommand(UUID.randomUUID(),0L,BigDecimal.ZERO,false,null);
        assertThatThrownBy(()->InventoryCountMutationService.validateBatch(new BatchCommand(0L,List.of(row,row)),"key")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateBatch(new BatchCommand(null,List.of(row)),"key")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->InventoryCountMutationService.validateBatch(new BatchCommand(0L,List.of(new RowCommand(row.lineId(),null,BigDecimal.ZERO,false,null))),"key")).isInstanceOf(BadRequestException.class);
        var large=java.util.stream.IntStream.range(0,101).mapToObj(i->new RowCommand(UUID.randomUUID(),0L,BigDecimal.ZERO,false,null)).toList();
        assertThatThrownBy(()->InventoryCountMutationService.validateBatch(new BatchCommand(0L,large),"key")).isInstanceOf(BadRequestException.class);
    }
}
