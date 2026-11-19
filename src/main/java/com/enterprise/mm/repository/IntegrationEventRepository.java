package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IntegrationEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface IntegrationEventRepository extends JpaRepository<IntegrationEvent, UUID> {
    Optional<IntegrationEvent> findAnyById(UUID id);
    Optional<IntegrationEvent> findByIdAndIsDeletedFalse(UUID id);
    Optional<IntegrationEvent> findByTenantIdAndIdAndIsDeletedFalse(String tenantId, UUID id);

    Optional<IntegrationEvent> findByTenantIdAndIdempotencyKeyAndIsDeletedFalse(String tenantId, String idempotencyKey);

    Optional<IntegrationEvent> findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(String tenantId, UUID companyId, String idempotencyKey);

    Optional<IntegrationEvent> findFirstByTenantIdAndAggregateTypeAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, String aggregateType);

    Optional<IntegrationEvent> findFirstByTenantIdAndCompanyIdAndAggregateTypeAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, String aggregateType);

    List<IntegrationEvent> findByTenantIdAndCompanyIdAndAggregateTypeAndIsDeletedFalseOrderByCreatedAtDesc(String tenantId, UUID companyId, String aggregateType);

    long countByTenantIdAndAggregateTypeAndStatusInAndIsDeletedFalse(String tenantId, String aggregateType, List<String> statuses);

    long countByTenantIdAndCompanyIdAndAggregateTypeAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, String aggregateType, List<String> statuses);

    long countByTenantIdAndAggregateTypeAndAggregateIdInAndStatusInAndIsDeletedFalse(
        String tenantId, String aggregateType, List<UUID> aggregateIds, List<String> statuses
    );

    @Query("""
        select count(distinct event.aggregateId)
        from IntegrationEvent event
        where event.tenantId = :tenantId
          and event.aggregateType = :aggregateType
          and event.aggregateId in :aggregateIds
          and event.status in :statuses
          and event.isDeleted = false
        """)
    long countDistinctCaseAggregatesWithStatuses(
        @Param("tenantId") String tenantId,
        @Param("aggregateType") String aggregateType,
        @Param("aggregateIds") List<UUID> aggregateIds,
        @Param("statuses") List<String> statuses
    );

    List<IntegrationEvent> findByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, String aggregateType, UUID aggregateId
    );

    List<IntegrationEvent> findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, UUID aggregateId
    );

    boolean existsByTenantIdAndCompanyIdAndAggregateTypeAndAggregateIdAndEventTypeAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String aggregateType,
        UUID aggregateId,
        String eventType
    );

    @Query("""
        select count(event)
        from IntegrationEvent event
        where event.tenantId = :tenantId
          and event.companyId = :companyId
          and event.aggregateType = 'TREASURY'
          and event.aggregateId = :aggregateId
          and event.externalId = :externalReference
          and event.direction = 'INBOUND'
          and event.status = 'PUBLISHED'
          and upper(event.description) in :successfulStatuses
          and upper(event.eventType) like 'TREASURY.%PAYMENT%'
          and event.isDeleted = false
          and exists (
              select outgoing.id
              from IntegrationEvent outgoing
              where outgoing.tenantId = event.tenantId
                and outgoing.companyId = event.companyId
                and outgoing.aggregateType = 'TREASURY'
                and outgoing.aggregateId = event.aggregateId
                and outgoing.eventType = 'TREASURY.PAYMENT_REQUEST'
                and outgoing.direction = 'OUTBOUND'
                and outgoing.createdAt <= event.createdAt
                and outgoing.isDeleted = false
          )
        """)
    long countSuccessfulTreasuryPaymentAcknowledgements(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("aggregateId") UUID aggregateId,
        @Param("externalReference") String externalReference,
        @Param("successfulStatuses") List<String> successfulStatuses
    );

    @Query("""
        select event from IntegrationEvent event
        where event.isDeleted = false
          and event.status in :statuses
          and (event.nextRetryAt is null or event.nextRetryAt <= :now)
        """)
    List<IntegrationEvent> findDue(@Param("statuses") List<String> statuses, @Param("now") LocalDateTime now);

    List<IntegrationEvent> findByTenantIdAndStatusInAndIsDeletedFalse(String tenantId, List<String> statuses);

    List<IntegrationEvent> findByTenantIdAndCompanyIdAndStatusInAndIsDeletedFalse(String tenantId, UUID companyId, List<String> statuses);

    List<IntegrationEvent> findTop50ByTenantIdAndCompanyIdAndEventTypeStartingWithAndIsDeletedFalseOrderByCreatedAtDesc(
        String tenantId, UUID companyId, String eventTypePrefix
    );

    @Query("""
        select event from IntegrationEvent event
        where event.tenantId = :tenantId
          and (:companyId is null or event.companyId = :companyId)
          and event.id = :id
          and event.isDeleted = false
        """)
    Optional<IntegrationEvent> findWorkspaceEvent(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select event from IntegrationEvent event
        where event.tenantId = :tenantId
          and event.companyId = :companyId
          and event.id = :id
          and event.isDeleted = false
        """)
    Optional<IntegrationEvent> findCommandEventForUpdate(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("id") UUID id
    );

    List<IntegrationEvent> findByTenantIdAndCompanyIdAndReplayOfEventIdAndIsDeletedFalseOrderByCreatedAtAsc(
        String tenantId,
        UUID companyId,
        UUID replayOfEventId
    );

    @Query("""
        select event from IntegrationEvent event
        where event.tenantId = :tenantId
          and (:companyId is null or event.companyId = :companyId)
          and event.isDeleted = false
          and (
            cast(:search as string) is null
            or lower(coalesce(event.code, '')) like lower(concat('%', cast(:search as string), '%'))
            or lower(coalesce(event.name, '')) like lower(concat('%', cast(:search as string), '%'))
            or lower(coalesce(event.eventType, '')) like lower(concat('%', cast(:search as string), '%'))
            or lower(coalesce(event.aggregateType, '')) like lower(concat('%', cast(:search as string), '%'))
            or lower(coalesce(event.externalId, '')) like lower(concat('%', cast(:search as string), '%'))
            or lower(coalesce(event.lastError, '')) like lower(concat('%', cast(:search as string), '%'))
          )
          and (
            :queue = 'ALL_ACTIVITY'
            or (
              :queue = 'FAILED'
              and (
                event.status = 'FAILED'
                or (event.status = 'RETRY' and (event.nextRetryAt is null or event.nextRetryAt <= :now))
              )
              and coalesce(event.retryCount, 0) < :deadThreshold
              and lower(coalesce(event.lastError, '')) not like '%no integration transport%'
              and lower(coalesce(event.lastError, '')) not like '%not configured%'
              and lower(coalesce(event.lastError, '')) not like '%configuration%'
            )
            or (
              :queue = 'RETRY_SCHEDULED'
              and event.status = 'RETRY'
              and event.nextRetryAt > :now
              and coalesce(event.retryCount, 0) < :deadThreshold
              and lower(coalesce(event.lastError, '')) not like '%no integration transport%'
              and lower(coalesce(event.lastError, '')) not like '%not configured%'
              and lower(coalesce(event.lastError, '')) not like '%configuration%'
            )
            or (
              :queue = 'NO_TRANSPORT'
              and event.status in ('FAILED', 'RETRY', 'DEAD')
              and (
                lower(coalesce(event.lastError, '')) like '%no integration transport%'
                or lower(coalesce(event.lastError, '')) like '%not configured%'
                or lower(coalesce(event.lastError, '')) like '%configuration%'
              )
            )
            or (
              :queue = 'AWAITING_RECONCILIATION'
              and event.status = 'PUBLISHED'
              and event.externalId is null
            )
            or (
              :queue = 'DEAD_MANUAL_DECISION'
              and (event.status in ('DEAD', 'CANCELLED') or coalesce(event.retryCount, 0) >= :deadThreshold)
            )
          )
        """)
    Page<IntegrationEvent> searchWorkspace(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("queue") String queue,
        @Param("search") String search,
        @Param("now") LocalDateTime now,
        @Param("deadThreshold") int deadThreshold,
        Pageable pageable
    );

    long countByTenantIdAndCompanyIdAndStatusAndCreatedAtGreaterThanEqualAndIsDeletedFalse(
        String tenantId,
        UUID companyId,
        String status,
        LocalDateTime createdAt
    );

    @Query("""
        select count(event) from IntegrationEvent event
        where event.tenantId = :tenantId
          and (:companyId is null or event.companyId = :companyId)
          and event.status = 'PUBLISHED'
          and event.createdAt >= :createdAt
          and event.isDeleted = false
        """)
    long countWorkspacePublishedSince(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("createdAt") LocalDateTime createdAt
    );

    @Query("""
        SELECT e
        FROM IntegrationEvent e
        WHERE
            e.tenantId = :tenantId
            AND (:companyId IS NULL OR e.companyId = :companyId)
            AND (:includeDeleted = true OR e.isDeleted = false)
            AND (:search IS NULL OR
                 LOWER(COALESCE(e.code, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(COALESCE(e.name, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (CAST(:dateFrom AS timestamp) IS NULL OR e.createdAt >= :dateFrom)
            AND (CAST(:dateTo AS timestamp) IS NULL OR e.createdAt <= :dateTo)
            AND (:statusEmpty = true OR e.status IN :status)
        """)
    Page<IntegrationEvent> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        Pageable pageable
    );
}
