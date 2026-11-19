package com.enterprise.mm.procurement.command;

import com.enterprise.mm.entity.PurchaseRequest;
import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QueueDefinition;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementCommandQueryRepository;
import com.enterprise.mm.procurement.command.service.ProcurementCommandQueryService;
import com.enterprise.mm.procurement.command.service.ProcurementBlockerResolver;
import com.enterprise.mm.procurement.command.service.ProcurementNextActionResolver;
import com.enterprise.mm.procurement.command.service.ProcurementStageResolver;
import com.enterprise.mm.procurement.command.service.ProcurementQueueService;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.RFQRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcurementQueueServiceTest {

    private final ProcurementCommandQueryRepository repository = mock(ProcurementCommandQueryRepository.class);
    private final ProcurementQueueService queueService = new ProcurementQueueService(repository);
    private final ProcurementCommandQueryService queryService = new ProcurementCommandQueryService(
        repository, mock(RFQRepository.class), mock(PurchaseOrderRepository.class),
        new ProcurementStageResolver(), new ProcurementBlockerResolver(), new ProcurementNextActionResolver());

    @ParameterizedTest
    @EnumSource(ProcurementQueueKey.class)
    void enabledQueueCountEqualsSearchTotal(ProcurementQueueKey key) {
        ProcurementCommandSearchRequest filters = ProcurementCommandSearchRequest.builder().search("PR-10").build();
        PurchaseRequest request = new PurchaseRequest();
        request.setCode("PR-10");
        when(repository.count(any())).thenReturn(1L);
        when(repository.search(any(), eq(PageRequest.of(0, 25))))
            .thenReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 25), 1));

        long count = queueService.count(key, filters);
        long searchTotal = queryService.search(filters.withQueue(key), PageRequest.of(0, 25)).getTotalElements();

        assertThat(count).isEqualTo(searchTotal);
    }

    @Test
    void partiallyAllocatedQueueIsAvailableAndCounted() {
        when(repository.count(any())).thenReturn(2L);

        QueueDefinition queue = queueService.definition(ProcurementQueueKey.PARTIALLY_ALLOCATED);

        assertThat(queue.available()).isTrue();
        assertThat(queue.count()).isEqualTo(2L);
        assertThat(queue.unavailableReasonCode()).isNull();
    }
}
