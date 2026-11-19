# Frontend handoff: department warehouse access

## Department administration

Department create/update payloads now accept:

```json
{
  "warehouseIds": ["uuid", "uuid"]
}
```

Department search/get responses return the same `warehouseIds` array.

Add a multi-select named **Accessible warehouses** to the department form. Load its options from `GET /api/warehouses`. Send:

- omitted `warehouseIds` when an older edit flow must preserve the existing mapping;
- `warehouseIds: []` when the administrator explicitly removes every mapping;
- the complete selected list for replacement updates.

The backend rejects deleted, inactive, cross-tenant, and cross-company warehouses.

## Session contract

`GET /api/auth/me` now adds:

```json
{
  "accessibleWarehouseIds": ["uuid", "uuid"],
  "assignedWarehouseId": null,
  "assignedWarehouseName": null,
  "isWarehouseRestricted": true
}
```

For a restricted user with exactly one grant, `assignedWarehouseId` remains populated for backward compatibility. For multiple grants it is `null` until the request selects a warehouse.

## Active warehouse selection

When `isWarehouseRestricted` is true and `accessibleWarehouseIds` contains multiple entries:

1. Load warehouse labels from `GET /api/warehouses`; this endpoint is filtered to the granted set.
2. Ask the user to choose an active warehouse in the application shell.
3. Persist the choice per company/user, not globally across accounts.
4. Add the header below to every warehouse-owned API request:

```http
X-Warehouse-Id: <selected warehouse UUID>
```

5. Clear or revalidate the selection after login, company switch, or `/api/auth/me` refresh.

An invalid or no-longer-granted header returns access denied. A malformed UUID returns a validation error. A restricted multi-warehouse request without a selection fails closed for single-warehouse services.

## UX behavior

- Hide the selector for unrestricted users.
- Show a fixed warehouse label for a single-grant user.
- Show a selector for two or more grants.
- If the grant list is empty, show “No warehouse access assigned” and disable warehouse actions.
- Do not use client filtering as authorization; the backend remains authoritative.

## User form

When linking an employee to a user, the user's department must match the employee department. The backend derives the user department when it was empty and rejects a conflicting department. Surface that backend validation message next to the employee/department fields.
