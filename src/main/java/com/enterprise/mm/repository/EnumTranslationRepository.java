package com.enterprise.mm.repository;

import com.enterprise.mm.entity.EnumTranslation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnumTranslationRepository extends JpaRepository<EnumTranslation, UUID> {
    List<EnumTranslation> findByEnumNameIn(Collection<String> enumNames);
}
