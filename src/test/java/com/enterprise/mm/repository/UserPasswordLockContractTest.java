package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import com.enterprise.mm.payload.ChangePasswordRequest;
import com.enterprise.mm.service.AuditService;
import com.enterprise.mm.service.PasswordChangeService;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class UserPasswordLockContractTest {
    @Test
    void passwordChangeLookupUsesPessimisticWriteLock() throws Exception {
        Method method = UserRepository.class.getMethod(
            "findCurrentUserForPasswordChange",
            UUID.class,
            String.class,
            String.class
        );

        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void passwordMutationAndAuditUseTheSameTransactionBoundary() throws Exception {
        Method changePassword = PasswordChangeService.class.getMethod(
            "changePassword",
            Authentication.class,
            ChangePasswordRequest.class
        );
        Method audit = AuditService.class.getMethod(
            "logInCurrentTransaction",
            String.class,
            UUID.class,
            String.class,
            String.class
        );

        assertThat(changePassword.getAnnotation(Transactional.class)).isNotNull();
        assertThat(audit.getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.MANDATORY);
    }
}
