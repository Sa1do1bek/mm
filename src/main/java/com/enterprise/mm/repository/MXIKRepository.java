package com.enterprise.mm.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.payload.mxikImport.MXIKNameCodeCountProjection;
import com.enterprise.mm.payload.mxikImport.MXIKNameCountProjection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MXIKRepository extends JpaRepository<MXIK, Long> {
    Optional<MXIK> findAnyById(Long id);

    boolean existsByKod(String kod);

    boolean existsByKodAndType(String kod, String type);

    boolean existsByKodAndIdNot(String kod, Long id);

    Optional<MXIK> findByKod(String kod);

    Optional<MXIK> findByIdAndTypeNotNull(Long id);

    Optional<MXIK> findByKodAndTypeNotNull(String code);

    @Query(
            value = """
                    select m.*
                    from mxik m
                    where (:type is null or upper(cast(m.type as text)) = upper(:type))
                      and (cast(:dateFrom as timestamp) is null or m.created_at >= :dateFrom)
                      and (cast(:dateTo as timestamp) is null or m.created_at < :dateTo)
                    order by m.created_at desc, m.id desc
                    """,
            nativeQuery = true
    )
    Slice<MXIK> listByTypeAndCreatedAt(
            @Param("type") String type,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query(
            value = """
                    select m.*
                    from mxik m
                    where (:type is null or upper(cast(m.type as text)) = upper(:type))
                      and (cast(m.kod as text) ilike :searchPattern
                           or cast(m.name as text) ilike :searchPattern
                           or lower(cast(m.name_ru as text)) like :searchPattern
                           or cast(m.barcode as text) ilike :searchPattern
                           or cast(m.brand_name as text) ilike :searchPattern
                           or cast(m.group_name as text) ilike :searchPattern
                           or cast(m.class_name as text) ilike :searchPattern
                           or cast(m.position_name as text) ilike :searchPattern
                           or cast(m.sub_position_name as text) ilike :searchPattern
                           or cast(m.attribute_name as text) ilike :searchPattern)
                      and (cast(:dateFrom as timestamp) is null or m.created_at >= :dateFrom)
                      and (cast(:dateTo as timestamp) is null or m.created_at < :dateTo)
                    order by m.created_at desc, m.id desc
                    """,
            nativeQuery = true
    )
    Slice<MXIK> searchByTypeTermAndCreatedAt(
            @Param("searchPattern") String searchPattern,
            @Param("type") String type,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    @Query(
            value = """
                    select max(m.groupName) as name, count(m) as count
                    from MXIK m
                    where m.groupName is not null and trim(m.groupName) <> ''
                    group by m.groupName
                    order by name
                    """,
            countQuery = """
                    select count(distinct m.groupName)
                    from MXIK m
                    where m.groupName is not null and trim(m.groupName) <> ''
                    """
    )
    Page<MXIKNameCountProjection> findGroupCounts(Pageable pageable);

    @Query(
            value = """
                    select coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name) as name,
                           count(*) as count
                    from mxik m
                    where coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name) is not null
                      and btrim(coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name)) <> ''
                    group by coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name)
                    order by name
                    """,
            countQuery = """
                    select count(distinct coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name))
                    from mxik m
                    where coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name) is not null
                      and btrim(coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name)) <> ''
                    """,
            nativeQuery = true
    )
    Page<MXIKNameCountProjection> findGroupCountsRu(Pageable pageable);

    @Query("""
            select max(m.className) as name, count(m) as count
            from MXIK m
            where m.groupName = :groupName
              and m.className is not null and trim(m.className) <> ''
            group by m.className
            order by name
            """)
    List<MXIKNameCountProjection> findClassCounts(@Param("groupName") String groupName);

    @Query(value = """
            select coalesce(nullif(btrim(m.class_name_ru), ''), m.class_name) as name,
                   count(*) as count
            from mxik m
            where coalesce(nullif(btrim(m.group_name_ru), ''), m.group_name) = :groupName
              and coalesce(nullif(btrim(m.class_name_ru), ''), m.class_name) is not null
              and btrim(coalesce(nullif(btrim(m.class_name_ru), ''), m.class_name)) <> ''
            group by coalesce(nullif(btrim(m.class_name_ru), ''), m.class_name)
            order by name
            """, nativeQuery = true)
    List<MXIKNameCountProjection> findClassCountsRu(@Param("groupName") String groupName);

    @Query("""
            select max(m.positionName) as name, count(m) as count
            from MXIK m
            where m.className = :className
              and m.positionName is not null and trim(m.positionName) <> ''
            group by m.positionName
            order by name
            """)
    List<MXIKNameCountProjection> findPositionCounts(@Param("className") String className);

    @Query(value = """
            select coalesce(nullif(btrim(m.position_name_ru), ''), m.position_name) as name,
                   count(*) as count
            from mxik m
            where coalesce(nullif(btrim(m.class_name_ru), ''), m.class_name) = :className
              and coalesce(nullif(btrim(m.position_name_ru), ''), m.position_name) is not null
              and btrim(coalesce(nullif(btrim(m.position_name_ru), ''), m.position_name)) <> ''
            group by coalesce(nullif(btrim(m.position_name_ru), ''), m.position_name)
            order by name
            """, nativeQuery = true)
    List<MXIKNameCountProjection> findPositionCountsRu(@Param("className") String className);

    @Query("""
            select max(m.subPositionName) as name, count(m) as count
            from MXIK m
            where m.positionName = :positionName
              and m.subPositionName is not null and trim(m.subPositionName) <> ''
            group by m.subPositionName
            order by name
            """)
    List<MXIKNameCountProjection> findSubPositionCounts(@Param("positionName") String positionName);

    @Query(value = """
            select coalesce(nullif(btrim(m.sub_position_name_ru), ''), m.sub_position_name) as name,
                   count(*) as count
            from mxik m
            where coalesce(nullif(btrim(m.position_name_ru), ''), m.position_name) = :positionName
              and coalesce(nullif(btrim(m.sub_position_name_ru), ''), m.sub_position_name) is not null
              and btrim(coalesce(nullif(btrim(m.sub_position_name_ru), ''), m.sub_position_name)) <> ''
            group by coalesce(nullif(btrim(m.sub_position_name_ru), ''), m.sub_position_name)
            order by name
            """, nativeQuery = true)
    List<MXIKNameCountProjection> findSubPositionCountsRu(@Param("positionName") String positionName);

    @Query("""
            select max(m.name) as name, max(m.kod) as code, count(m) as count
            from MXIK m
            where m.subPositionName = :subPositionName
            group by m.name
            order by name
            """)
    List<MXIKNameCodeCountProjection> findMxikCounts(@Param("subPositionName") String subPositionName);

    @Query(value = """
            select max(coalesce(nullif(btrim(m.name_ru), ''), m.name)) as name,
                   max(m.kod) as code,
                   count(*) as count
            from mxik m
            where coalesce(nullif(btrim(m.sub_position_name_ru), ''), m.sub_position_name) = :subPositionName
            group by coalesce(nullif(btrim(m.name_ru), ''), m.name)
            order by name
            """, nativeQuery = true)
    List<MXIKNameCodeCountProjection> findMxikCountsRu(@Param("subPositionName") String subPositionName);

    List<MXIK> findByKodIn(Collection<String> kod);

    @Query("""
                select m from MXIK m
                where lower(m.name) like lower(concat('%', :search, '%'))
                   or lower(m.nameRu) like lower(concat('%', :search, '%'))
            """)
    Page<MXIK> findByName(@Param("search") String search,Pageable pageable);
}

