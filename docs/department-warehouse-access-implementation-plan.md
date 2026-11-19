# Department warehouse access: backend implementation plan

## Business rules

1. A department may be granted access to zero, one, or many active warehouses in the same tenant and company.
2. A warehouse-scoped user receives warehouse grants from the employee's department. If no employee is linked, the user's directly assigned department is used.
3. Warehouse roles remain the capability gate. Department membership by itself does not grant warehouse operations.
4. `ADMIN` and `MM_MANAGER` retain their existing company/global bypass.
5. A multi-warehouse user must select one granted warehouse for single-warehouse workspaces by sending `X-Warehouse-Id`. Missing or unauthorized selection fails closed.
6. The legacy `employees.warehouse_id` remains a fallback only for employees without a department. Existing department/employee assignments are backfilled into the new relation.
7. When a user is linked to an employee, their departments must agree. If the user has no department, it is derived from the employee.

## Delivery steps

- Add `department_warehouse_access` with tenant/company foreign-key integrity, uniqueness, indexes, and legacy backfill.
- Add the JPA entity/repository and expose `warehouseIds` on department create, update, get, and search payloads.
- Synchronize mappings transactionally and validate warehouse tenant, company, active, and deleted state.
- Expand `RoleDataScopeService.DataScope` to carry the complete granted warehouse set while preserving its existing single-warehouse compatibility accessor.
- Resolve and validate the selected warehouse from `X-Warehouse-Id`; make list, read, and mutation checks use the complete grant set.
- Return granted warehouse IDs from `/api/auth/me` and make `/api/warehouses` return only granted warehouses.
- Enforce employee/user department consistency.
- Add focused unit and service tests plus migration/compile verification.

## Compatibility and rollout

- Existing users whose employee has both a department and warehouse are migrated automatically.
- An employee without a department continues to use `employees.warehouse_id`.
- Existing single-warehouse users do not need the header; their only warehouse is selected automatically.
- Multi-warehouse users require the frontend handoff documented separately.

## Security acceptance criteria

- Cross-tenant and cross-company mappings are rejected.
- Inactive or deleted warehouses cannot be newly granted.
- A warehouse role with no grants sees no warehouse-owned data.
- An unauthorized `X-Warehouse-Id` is rejected.
- Warehouse master-data search never exposes warehouses outside the grant set.
- Mutation guards accept any granted warehouse and reject all others.
