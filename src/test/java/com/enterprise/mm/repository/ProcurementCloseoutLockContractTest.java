package com.enterprise.mm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class ProcurementCloseoutLockContractTest {
    @Test
    void closeoutFinalizationLocksCloseoutPurchaseOrderAndSourceAllocationsForUpdate() throws Exception {
        assertPessimisticWriteLock(ProcurementCloseoutRepository.class.getMethod("findByIdForUpdate", UUID.class));
        assertPessimisticWriteLock(PurchaseOrderRepository.class.getMethod("findByIdForUpdate", UUID.class));
        assertPessimisticWriteLock(PurchaseOrderSourceAllocationRepository.class.getMethod(
            "findByPurchaseOrderItemIdsForUpdate",
            String.class,
            UUID.class,
            List.class
        ));
    }

    private void assertPessimisticWriteLock(Method method) {
        Lock lock = method.getAnnotation(Lock.class);

        assertThat(lock).as(method.getName() + " lock").isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
