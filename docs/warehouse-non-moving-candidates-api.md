# Personal warehouse non-moving candidates

These additive APIs support the `Nolikvidlar` tab after `Movements`. They do not
change company automatic detection, existing `NonMovingStock` records, case
creation, approvals, stock posting, or `/command-view/non-moving` (open cases).

## Personal threshold

`GET /api/warehouse/non-moving/preferences/me`

The standard `ApiResponse.data` contains:

```json
{"thresholdMonths":12,"source":"COMPANY","version":0,"updatedAt":null}
```

The precedence is saved personal preference, valid company threshold, then 12
months. Sources are `PERSONAL`, `COMPANY`, `DEFAULT`. Reading a default does not
insert anything. The saved preference is keyed by tenant + current company +
authenticated user ID, across all warehouses that user can access in that company.
Login, refresh, another browser or another device do not reset it.

`PUT /api/warehouse/non-moving/preferences/me`

```json
{"thresholdMonths":24,"expectedVersion":0}
```

Use the version returned by GET or the last successful PUT. The first save returns
version 1; later saves increment it. A concurrent/stale save returns HTTP 409:
reload and let the user retry rather than silently overwriting another tab's
selection. Valid thresholds are integer months from 1 through 1200. Two years is
24 months. Neither user ID nor company ID is accepted in the body. A preference
change only affects this user's display; it is not a business-policy approval.

## Candidate list

`GET /api/warehouses/{id}/non-moving-candidates`

Optional query parameters:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `search` | empty | Literal, case-insensitive material code/name search, at most 240 characters |
| `materialGroupId` | absent | Restrict material group |
| `includeUnknownHistory` | false | Also show positive stock whose last movement cannot be proven |
| `page` | 0 | Zero-based page |
| `size` | 25 | Page size, 1..200 |
| `sortBy` | `LAST_MOVEMENT_DATE` | Also `MATERIAL_CODE`, `MATERIAL_NAME`, `QUANTITY`, `BOOK_VALUE` |
| `direction` | `ASC` | `ASC` or `DESC`; unknown dates always sort last |
| `language` | request locale | `uz`, `ru`, `en`; localizes unit labels and canonical material-name sorting |

The threshold is always resolved from the current user's saved/default preference,
not from another user's detections or an arbitrary query parameter. The response
uses the standard `ApiResponse` wrapper with:

- `effectiveThresholdMonths`, `preferenceSource`;
- `asOfDate`, `calculatedAt`, in the application's warehouse business clock;
- `positions`: `content`, `page`, `size`, `totalElements`, `totalPages`.

Each row represents one material in this warehouse, with material/group IDs and
names, unit, current total/available/reserved/quarantine quantities, current book
value and accounting currency, `lastMovementDate`, `ageReferenceDate`, `ageBasis`, `monthsWithoutMovement`,
`eligibleSince`, `historyStatus`, and latest open case ID/number/status if any.
No sums across different units are returned. Book value is current persisted
stock value, not an historical valuation or a sale-price estimate.

Only active, nondeleted TMB materials with positive net current stock qualify.
Age is measured from the latest effective date of a posted physical movement:

- Native `POSTED` movements, using movement date (posted date only if missing).
- Allocation evidence determines the actual warehouse affected by managed
  transfers. Dispatch alone does not reset the destination's age.
- Committed account-aware `STOCK` effects, using posting date.
- Mapped imported movement history, using its original movement date, without
  adding imported quantities to live stock.

Drafts, approvals alone, reservations, record edits and value-only postings do not
reset age. The anniversary is `ageReferenceDate + thresholdMonths <= asOfDate`,
including month-end/leap-year calendar rules. A newer receipt or issue therefore
removes the material from the personal list on the next request. This is material
inactivity, not individual batch aging: a recent receipt resets this material's
age in this warehouse even if some older batch stock remains.

Opening snapshot loads (`OPENING_STOCK`, `OPENING_STOCK_EXACT`, account posting
context `OPENING`) do not replace a proven actual movement date. If no actual
history exists, the documented opening date is the fallback `ageReferenceDate`,
with `ageBasis=OPENING_BASELINE` and null `lastMovementDate`. Otherwise the basis
is `MOVEMENT`. Eligibility and calendar age use `ageReferenceDate`. This keeps a
recent migration/import from hiding old goods with known historical movements.

Without dated movement evidence, the response only includes the material if
`includeUnknownHistory=true`. It is marked `INSUFFICIENT_HISTORY` with null age
and eligibility date; the UI must not label it as confirmed non-moving. A balance
row's insertion/import time is never presented as an actual movement date.

The query is read-only and count/rows share one repeatable-read snapshot. Merely
opening the tab or changing its threshold creates no detection/case and posts no
stock. Case commands continue to use the existing business workflow and authority.

## Excel export

`GET /api/warehouses/{id}/non-moving-candidates/export`

Accepts the same `search`, `materialGroupId`, `includeUnknownHistory`, `sortBy`,
`direction`, and `language` as the list. Export ignores page/size and downloads all
matching rows. `language` overrides `Accept-Language`; the application's default
locale is `uz`. No dateFrom/dateTo or arbitrary threshold parameters are supported.

The existing controller and service handle the download. Authorization and
warehouse/tenant/company checks are identical to the list. The service captures
one preference, calculation time and accounting currency in a repeatable-read
snapshot and streams rows into XLSX in batches of 200. Later batches reuse the
original count. Merely exporting does not save preferences or create cases.

The response is binary XLSX with `Content-Disposition`, `Content-Language`, and
`Cache-Control: no-store`. Headers, available names, units, review and history
labels support Uzbek, Russian and English. Codes remain strings with leading
zeros; dates and amounts are typed Excel cells. Unknown dates/ages remain blank.
The workbook also records warehouse, filters, sort, threshold, calculation date
and business timezone. Empty results still produce a workbook with headers.

The default limit is 100,000 rows, configured by
`warehouse.non-moving.export.max-rows`. Larger results return HTTP 400 and are
never silently truncated. Excel numeric cells retain Excel's usual 15 significant
digit limit. See [the frontend task prompt](warehouse-non-moving-excel-frontend-prompt.uz.md).

## Access and rollout

Allowed roles: ADMIN, MM_MANAGER, WAREHOUSE_MANAGER, WAREHOUSE_OPERATOR,
WAREHOUSE_HEAD, WAREHOUSE_KEEPER, FINANCE_CONTROLLER, ACCOUNTANT, AUDITOR, VIEWER.
The warehouse must be in the current tenant/company and satisfy assignment scope.
The PUT permission is limited to the caller's personal preference; it grants no
warehouse-master or financial mutation rights.

Deploy additive Flyway V46 before serving the new APIs. Existing users require no
backfill and continue using company/default thresholds until their first save.
Frontend integration should load the preference, display its value, save explicit
changes, and refetch the candidate list after save or a completed stock movement.

Focused verification:

```sh
mvn -o -Dtest=WarehouseNonMovingServiceTest,WarehouseNonMovingPostgresTest,WarehouseDutyHttpSecurityTest,NonMovingStockServiceTest,NonMovingStockCaseLifecycleTest,NonMovingStockDecisionServiceTest,NonMovingStockExecutionServiceTest test
```

The PostgreSQL test exercises V46, actual candidate SQL, persistence/reload,
optimistic conflicts, user/company isolation, threshold boundaries, live changes,
unknown history, transfer destination evidence, historical/account-aware dates,
literal search and paging. It requires Docker and is skipped if Docker is unavailable.
