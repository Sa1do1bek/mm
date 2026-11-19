package com.enterprise.mm.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.DemandPlanningCycle;
import com.enterprise.mm.entity.Employee;
import com.enterprise.mm.entity.User;
import org.junit.jupiter.api.Test;

class DemandOwnerDisplayMapperTest {
    @Test
    void demandPlanningCycleResponseReturnsOwnerFullNameInOwnerUsernameField() {
        User owner = new User();
        owner.setUsername("planner.owner");
        owner.setFullName("Aziza Karimova");
        Employee employee = new Employee();
        employee.setName("Planning employee record");
        employee.setUser(owner);

        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setOwnerUsername("planner.owner");
        cycle.setOwnerEmployee(employee);

        var response = new DemandPlanningCycleMapper().toResponse(cycle);

        assertThat(response.getOwnerUsername()).isEqualTo("Aziza Karimova");
        assertThat(response.getOwnerEmployeeName()).isEqualTo("Planning employee record");
    }

    @Test
    void demandPlanResponseReturnsPlanningCycleOwnerFullNameInOwnerUsernameField() {
        User owner = new User();
        owner.setUsername("department.planner");
        owner.setFullName("Dilshod Ergashev");
        Employee employee = new Employee();
        employee.setName("Demand planning owner");
        employee.setUser(owner);
        DemandPlanningCycle cycle = new DemandPlanningCycle();
        cycle.setOwnerUsername("department.planner");
        cycle.setOwnerEmployee(employee);

        DemandPlan plan = new DemandPlan();
        plan.setOwnerUsername("department.planner");
        plan.setPlanningCycle(cycle);

        var response = new DemandPlanMapper().toResponse(plan);

        assertThat(response.getOwnerUsername()).isEqualTo("Dilshod Ergashev");
    }
}
