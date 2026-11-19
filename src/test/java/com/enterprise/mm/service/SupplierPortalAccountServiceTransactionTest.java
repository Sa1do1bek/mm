package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class SupplierPortalAccountServiceTransactionTest {

    @Test
    void rfqInvitationProvisioningBusinessErrorsDoNotRollbackCallerTransaction() throws NoSuchMethodException {
        Transactional transactional = SupplierPortalAccountService.class
            .getDeclaredMethod("ensureForRfqInvitation", UUID.class, String.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(BusinessException.class);
    }
}
