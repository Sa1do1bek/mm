package com.enterprise.mm;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.repository.*;
import java.util.*;

/** Minimal masters for inventory tests; avoids the all-capability demo business seeder. */
public final class B09InventoryMasterFixture {
    private B09InventoryMasterFixture() {}
    public static void ensure(MaterialRepository materials, UnitOfMeasureRepository units, UserRepository users) {
        if (materials.findAll().stream().filter(m -> !m.isDeleted()
            && CompanyContext.currentCompany().equals(m.getCompanyId()) && m.getMaterialType() == MaterialType.TMB).count() < 2) {
            var unit = new UnitOfMeasure(); unit.setCode("COUNT-EA"); unit.setName("Each");
            unit.setMeasurementNameShort("EA"); unit.setUnitType(MeasurementUnitType.QUANTITY); unit = units.saveAndFlush(unit);
            for (int n = 1; n <= 2; n++) {
                var material = new Material(); material.setCode("COUNT-MAT-" + n); material.setName("Count material " + n);
                material.setStatus("ACTIVE"); material.setMaterialType(MaterialType.TMB);
                material.setUnitOfMeasure(unit); material.setUnitOfMeasureId(unit.getId());
                material.setValuationMethod(ValuationMethod.ACTUAL_COST); materials.saveAndFlush(material);
            }
        }
        if (users.findAll().stream().noneMatch(u -> "admin".equals(u.getUsername()))) {
            var user = new User(); user.setUsername("admin"); user.setName("Inventory fixture administrator");
            user.setPasswordHash("fixture-only"); user.setEnabled(true); user.setRoles(Set.of(RoleName.ADMIN)); users.saveAndFlush(user);
        }
    }
}
