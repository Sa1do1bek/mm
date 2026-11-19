package com.enterprise.mm.erp.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.erp.OneCApiAccess;
import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WarehouseMirrorContractTest {
    @Test void disabledOrUnpinnedSourceCannotAcceptAndUnknownOrganizationFailsClosed(){
        assertThatThrownBy(()->new WarehouseMirrorSettings(false,"source","org").requireEnabled()).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->new WarehouseMirrorSettings(true,"","org").requireEnabled()).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->new WarehouseMirrorSettings(true,"source","org").validate("source","foreign")).isInstanceOf(BadRequestException.class);
    }
    @Test void everyNativeRouteAuthenticatesBeforeDelegating(){
        var access=mock(OneCApiAccess.class);var service=mock(WarehouseMirrorService.class);var controller=new WarehouseMirrorNativeController(access,service);var n=new ObjectMapper().createObjectNode().put("eventId","event");
        controller.snapshot("key","event",n);controller.poll("key");controller.page("key",n);controller.failure("key",n);verify(access,times(4)).authenticate("key");verify(service).receive(n);verify(service).poll();verify(service).page(n);verify(service).historyFailure(n);
        doThrow(new org.springframework.security.authentication.BadCredentialsException("disabled")).when(access).authenticate("bad");assertThatThrownBy(()->controller.page("bad",n)).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);verifyNoMoreInteractions(service);
    }
}
