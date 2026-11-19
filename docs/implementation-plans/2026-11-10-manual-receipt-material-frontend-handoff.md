# Manual receipt material creation — frontend handoff

Date: 2026-11-10
Backend status: implemented in the local `mm-backend` working tree; not deployed. The database migration requires an audit of live data before release.

## Goal

Let an authorized manager create an active inventory material from either material selector in a manual stock receipt, then select it in the same row without losing receipt inputs. MXIK is a visible, optional main field. Creating the catalog material must not post stock.

## Backend contract now available

Base path: `/api/workspaces/warehouse-execution/manual/materials`.

| Method and path | Request | Successful response |
| --- | --- | --- |
| `GET /create-context?warehouseId=<uuid>&materialGroupId=<uuid>` | `materialGroupId` is optional. Call again when the selected group changes. | `ApiResponse.data`: `canCreate`, `materialType: "TMB"`, `defaultSubtype: "MATERIAL"`, `defaultTrackingMode: "QUANTITY"`, `mxikOptional: true`, `materialGroupId`, `requiredAndOptionalAttributes[]` with `key`, `required`, `dataType`, `allowedValues`. |
| `GET /code-availability?warehouseId=<uuid>&code=<text>` | Only for manual code mode. Exact trim-and-uppercase check in the authenticated tenant and company. | `ApiResponse.data`: `normalizedCode`, `status: "AVAILABLE" | "DUPLICATE"`. |
| `POST /` | JSON body below and required `Idempotency-Key` header. | HTTP 201 with `ApiResponse.data` containing the complete saved `MaterialResponse`, including ID, code, name, unit, MXIK, tracking policy, and attributes. |

Example minimal body (no MXIK):

```json
{
  "warehouseId": "<selected warehouse UUID>",
  "name": "Example material",
  "materialGroupId": "<active group UUID>",
  "unitOfMeasureId": "<valid unit UUID>"
}
```

Optional fields accepted by this scoped create route: `code`, `mxikId`, `mxikCode`, `materialSubtype`, `manufacturer`, `manufacturerId`, `article`, `barcode`, `trackingMode`, `batchTrackingPolicy`, `serialTrackingPolicy`, `incomingInspectionRequired`, and `attributes` (`[{ "key": "...", "value": "..." }]`). An empty or omitted code is generated. The server forces `TMB` and `ACTIVE` and takes tenant and company from the authenticated request. It checks the selected warehouse and does not accept receipt lines, files, stock movements, or arbitrary catalog fields.

Use the existing `GET /api/material-groups`, `GET /api/uom`, and `GET /api/mxik` lookups. Restrict MXIK results to goods. Use the current `GET /api/materials` search for eligible existing-material suggestions. The new create-context call supplies group characteristics after a group is chosen.

The general `POST /api/materials` remains restricted to catalog writers. The scoped route permits `ADMIN`, `MM_MANAGER`, and `WAREHOUSE_MANAGER` authorities; the backend's existing role inheritance also grants that authority to warehouse and department heads. A failed warehouse/company scope check returns not found.

### Error handling

Use `ApiResponse.errorCode` to attach errors to fields. In particular:

| Error code | Field/action |
| --- | --- |
| `material.code.duplicate` | Material code; includes a save-time database collision. Keep both drafts and offer a new code or Automatic. |
| `material.code.required`, `material.code.too_long` | Manual code availability input. |
| `material.name.required`, `material.group.required`, `material.unit.required` | Main required fields. |
| `material.group.active`, `material.group.unknown`, `material.unit.unknown` | Reopen or clear the affected reference selector. |
| `material.mxik.inconsistent`, `material.mxik.unknown_id`, `material.mxik.unknown_code`, `material.type.mxik_mismatch` | MXIK selection. Do not treat search text as a selected MXIK. |
| `MATERIAL_ATTRIBUTE_TEMPLATE_REQUIRED`, template value errors | Group characteristic controls. |

The backend localizes error messages in English, Uzbek, and Russian. The frontend should localize its own field labels, help text, availability states, and recovery instructions.

## Frontend work

1. Extend `StockFactMaterialSelect.tsx` with **+ New material** in both code and name dropdowns, including no-results state. Expose the originating field and current search text. Show it only for receipt rows when create-context grants capability. Keep issue selectors unchanged.
2. Add a compact creation panel inside `WarehouseStockFactModal.tsx`; keep the receipt and staged `File` objects mounted. Use its own form without nesting HTML forms. Main fields: name, group, unit, automatic/manual code, optional MXIK. Focus name first. Put optional subtype, manufacturer/article, barcode, tracking, inspection, and optional characteristics under Additional details. Keep required group characteristics visible.
3. Preserve the receipt row's stable `key` when opening the panel. On create success or **Use existing**, merge the chosen material into that row. Do not call the existing `selectMaterial()` reset path: it currently starts with `newLine()` and clears quantity, unit value, and serial drafts. Preserve all other rows, receipt metadata, files, unrelated errors, and totals.
4. Prime the scoped `warehouse-stock-fact-material` detail cache with the full POST response and invalidate material-option searches. Revalidate preserved quantity and serials against the new unit/tracking rules. Flag incompatibilities without silently editing user input. Focus the target quantity after selection.
5. Debounce manual-code availability checks, check on blur, and discard stale results after code/company/session changes. Disable save while a manual-code check is pending, failed, or duplicate. The server's save-time check is authoritative.
6. Generate one idempotency key per material-create attempt and keep that exact key and payload for uncertain retries. After a successful material save, a failed receipt post should retry only the receipt. Prevent parent receipt submission and conflicting row actions while the child panel saves. Ignore late results when the receipt/company/session changes.
7. Verify cancellation, lookup and save failures, duplicate collisions, keyboard focus, narrow screens, long names, themes, and Uzbek/Russian/English copy. Test both selector columns and the empty-result state. Confirm material creation alone creates no stock movement.

## Release dependency and verification

The backend migration `V72__material_active_normalized_code_unique.sql` adds a unique active material-code index on tenant, company, and trimmed uppercase code. It stops if existing active rows collide; it does not rename or merge data. Run the read-only audit below against the target database, resolve any collisions through an explicit data decision, and deploy the backend and migration before exposing the frontend action.

```sql
SELECT tenant_id, company_id, upper(btrim(code)) AS normalized_code,
       count(*) AS row_count, array_agg(id ORDER BY id) AS material_ids
FROM public.materials
WHERE is_delete = false AND code IS NOT NULL AND btrim(code) <> ''
GROUP BY tenant_id, company_id, upper(btrim(code))
HAVING count(*) > 1;
```

Local verification: backend compilation passed; the four new `ManualReceiptMaterialServiceTest` tests passed. An existing `MaterialCatalogGovernanceTest` run reported Mockito unused-stubbing errors in tracking-combination cases (0 assertion failures); that class needs a separate test-fixture cleanup before using it as a release gate. No browser or live-database verification was performed.
