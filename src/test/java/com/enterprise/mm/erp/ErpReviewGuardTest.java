package com.enterprise.mm.erp;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErpReviewGuardTest {
    @Test void confirmationRequiresAccessToEverySourceAndDestination() throws Exception {
        var mapper=new ObjectMapper(); UUID event=UUID.randomUUID(), company=UUID.randomUUID(), source=UUID.randomUUID(), destination=UUID.randomUUID();
        var json=mapper.createObjectNode().put("eventId",event.toString()).put("tenantId","t").put("companyId",company.toString())
            .put("operation","TRANSFER_RECEIPT").put("sourceWarehouseId",source.toString()).put("destinationWarehouseId",destination.toString());
        json.putArray("lines").addObject().put("warehouseId",destination.toString());
        var access=mock(RoleDataScopeService.class);
        ErpExchangeService.requireConfirmationAccess(mapper,access,json.toString(),"TRANSFER_RECEIPT",event,"t",company);
        verify(access).assertWarehouseAccess(source); verify(access).assertWarehouseAccess(destination);
        doThrow(new NotFoundException("outside scope")).when(access).assertWarehouseAccess(source);
        assertThatThrownBy(() -> ErpExchangeService.requireConfirmationAccess(mapper,access,json.toString(),"TRANSFER_RECEIPT",event,"t",company)).isInstanceOf(NotFoundException.class);
        json.remove("sourceWarehouseId");
        assertThatThrownBy(() -> ErpExchangeService.requireConfirmationAccess(mapper,access,json.toString(),"TRANSFER_RECEIPT",event,"t",company)).isInstanceOf(ConflictException.class);
    }
    @Test void rejectsDamagedReceiptOnlyWhenExchangeIsEnabled() {
        assertThatThrownBy(() -> ErpExchangeHooks.requireSupportedReceipt(true,new BigDecimal("1"))).isInstanceOf(BusinessException.class).hasMessageContaining("quarantine");
        ErpExchangeHooks.requireSupportedReceipt(false,new BigDecimal("1"));
        ErpExchangeHooks.requireSupportedReceipt(true,BigDecimal.ZERO);
    }
}
