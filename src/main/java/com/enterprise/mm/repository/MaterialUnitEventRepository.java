package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialUnitEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialUnitEventRepository extends JpaRepository<MaterialUnitEvent, UUID> {
}
