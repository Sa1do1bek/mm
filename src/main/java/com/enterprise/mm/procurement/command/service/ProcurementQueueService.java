package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.procurement.command.enums.ProcurementQueueKey;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.QueueDefinition;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandSearchRequest;
import com.enterprise.mm.procurement.command.repository.ProcurementCommandQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class ProcurementQueueService {

    private final ProcurementCommandQueryRepository repository;

    public ProcurementQueueService(ProcurementCommandQueryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long count(ProcurementQueueKey key, ProcurementCommandSearchRequest filters) {
        ProcurementCommandSearchRequest safeFilters = filters == null
            ? ProcurementCommandSearchRequest.builder().build()
            : filters;
        return repository.count(safeFilters.withQueue(key));
    }

    @Transactional(readOnly = true)
    public long countForCompany(ProcurementQueueKey key, ProcurementCommandSearchRequest filters) {
        ProcurementCommandSearchRequest safeFilters = filters == null
            ? ProcurementCommandSearchRequest.builder().build()
            : filters;
        return repository.countForCompany(safeFilters.withQueue(key));
    }

    @Transactional(readOnly = true)
    public QueueDefinition definition(ProcurementQueueKey key) {
        return definition(key, null);
    }

    @Transactional(readOnly = true)
    public QueueDefinition definition(ProcurementQueueKey key, ProcurementCommandSearchRequest filters) {
        ProcurementCommandSearchRequest appliedFilters = (filters == null
            ? ProcurementCommandSearchRequest.builder().build()
            : filters).withQueue(key);
        return new QueueDefinition(
            key,
            "procurement.command.queue." + key.name(),
            repository.count(appliedFilters),
            true,
            null,
            appliedFilters);
    }

    @Transactional(readOnly = true)
    public List<QueueDefinition> definitions() {
        return definitions(null);
    }

    @Transactional(readOnly = true)
    public List<QueueDefinition> definitions(ProcurementCommandSearchRequest filters) {
        return Arrays.stream(ProcurementQueueKey.values()).map(key -> definition(key, filters)).toList();
    }
}
