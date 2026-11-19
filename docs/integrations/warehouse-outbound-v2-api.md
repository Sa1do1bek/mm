# Warehouse v2: MM outbound operations

The MM source journal preserves native MM valuation. Native 1C initiates every transport request. Capturing or reconciling an operation creates exchange evidence only; it does not create another MM business operation or modify MM balances/FIFO. `OBSERVED` confirms storage of evidence in 1C, not additional native stock posting.

## Deployment configuration

Existing `mm.one-c-api.tenant`, `.company`, `.key` and pull-mode publication settings still authorize the native client. The destination is additionally pinned by:

```properties
mm.one-c-warehouse.enabled=true
mm.one-c-warehouse.source-instance-id=<native-instance>
mm.one-c-warehouse.organization-id=<native-organization-uuid>
mm.one-c-warehouse.outbound-enabled=false
mm.one-c-warehouse.allowed-contra-accounts=
```

Outbound capture is disabled by default and applies only to the configured tenant/company. Set `outbound-enabled=true` to capture committed source facts and enable native polling. Native correction approval additionally requires an explicit comma-separated allowlist of native contra-account UUIDs; its default is empty. The native receiver independently validates stock accounts, contra accounts, analytic types and document identity.

## Sources and ownership

`STOCK_MOVEMENT:<UUID>` and `POSTING_BATCH:<UUID>` identify immutable posted source evidence. Repeated capture compares a canonical source hash and reuses the existing operation. Holding state uses revisioned `HOLDING:<UUID>` snapshots with an empty `facts` array: material already expensed at issue does not become another expense. `TRANSIT_LOSS:<UUID>` contains signed TRANSIT facts from immutable transit entries and never invents a warehouse issue allocation. Native 1C imports and exact/opening-stock source movements are excluded from echo.

Stock facts use signed persisted allocation quantity/value deltas. Account-aware facts choose STOCK physical quantity and one valuation channel per position: ACCOUNT, otherwise VALUE_ONLY, otherwise COST. Raw effects and account/owner/disposition/location/batch context remain in `rawEffects`; the three channels are never added as three expenses. Parent `resourceId` and typed source context group related production/transfer facts without converting them to supplier receipts.

At delivery, an existing v1 envelope linked through movement/document/reference/resource UUIDs changes a supported receipt/issue/transfer event to `LEGACY_LINKED`. An immutable new exchange revision records this decision. Advanced loss, write-off, production and other contexts remain under review even when an older envelope exists for the same parent transfer/document: document association does not prove that v1 posted the new action.

## Operator APIs

All responses use the existing `ApiResponse.data` wrapper. Reads require ADMIN, company-wide MM_MANAGER, FINANCE_CONTROLLER, ACCOUNTANT or AUDITOR. Warehouse-restricted scope is denied even with an otherwise permitted role.

- `GET /api/erp-exchange/warehouse-v2/outbound?limit=100&offset=0`: bounded head array. Fields: `id`, `operationId`, `revision`, `sourceKey`, `sourceEngine`, `operationType`, `resourceId`, `postingDate`, `policy`, `state`, `pendingReason`, `eligibleContext`, `nativeDocumentId`, `leaseUntil`, `attempts`, `createdAt`, `updatedAt`.
- `GET /api/erp-exchange/warehouse-v2/outbound/{operationId}`: `{operation,versions,operationalApplication:"PRESERVE_MM_NATIVE"}`. A version contains `revision,eventId,payloadHash,payloadJson,state,result,approvedBy,createdAt,resultAt`. `payloadJson` is the exact immutable JSON string hashed for delivery. `result` is parsed native proof when available.
- `GET /api/erp-exchange/warehouse-v2/outbound/coverage`: `enabled,total,pending,leased,observed,reviewRequired,applied,failed,byPolicy:[{policy,count}],byEngine:[{sourceEngine,count}],valuationPolicy` and configured source identity.
- `POST /api/erp-exchange/warehouse-v2/outbound/reconcile` (ADMIN): `{"cursor":"","limit":100}`. Returns `{cursor,nextCursor,scanned,captured,complete,operationalApplication}`. Continue with `nextCursor` until `complete=true`. Repeating a page is idempotent through immutable source identities. This scans existing committed stock movements, posting batches, holdings and transit losses; it creates no source business data. Completion means the committed source scan reached its current end, not that native mappings or posting completed. Concurrent new business transactions are covered by the source hooks.

Policies: `LEGACY_LINKED`, `REGISTRY_ONLY`, `NATIVE_MAPPING_REQUIRED`, `NATIVE_CORRECTION`.
States: `PENDING`, `LEASED`, `OBSERVED`, `REVIEW_REQUIRED`, `APPLIED`, `FAILED`. Historical undelivered versions can be `SUPERSEDED`. Pending reasons include missing immutable allocation evidence, native mapping requirements, registry-only expense policy, and native result messages. Missing evidence is visible; positive document quantity is never guessed into a signed stock fact.

## Native transport

Every request includes `X-MM-API-Key`, `X-Tenant-Id`, `X-Company-Id` using the existing pinned native integration credential and company context.

`POST /api/integrations/one-c/warehouse/operations/poll`:

```json
{"protocolVersion":2,"sourceInstanceId":"<native-instance>","organizationId":"<native-org>","workerId":"warehouse-worker","limit":1}
```

Response: `{ "operations": [] }` or one `{operationId,eventId,revision,leaseToken,payloadJson,payloadHash}`. `payloadHash` is lowercase SHA-256 of **exact `payloadJson` UTF-8 bytes**, not a reserialized JSON object. Lease duration is 120 seconds; expired leases receive a fresh token. Revisions retain operation identity and receive a new immutable event UUID.

The native payload limit is 8,000,000 UTF-8 bytes. Larger immutable source evidence remains stored but is blocked before leasing as REVIEW_REQUIRED / SOURCE_PAYLOAD_EXCEEDS_NATIVE_LIMIT, with no fabricated native result. Subsequent polls can deliver other operations. Approval rejects an oversized source or proposed revision before changing the journal.

Payload fields: `protocolVersion,sourceInstanceId,organizationId,operationId,eventId,revision,sourceKey,sourceEngine,operationType,resourceId,postingDate,contextName,policy,facts,rawEffects,nativeCommand?`. Signed fact quantities/amounts are exact decimal strings. Fact domains are STOCK or TRANSIT; raw holding state has no stock facts.

`POST /api/integrations/one-c/warehouse/operations/{operationId}/result`:

```json
{"protocolVersion":2,"sourceInstanceId":"<native-instance>","organizationId":"<native-org>","workerId":"warehouse-worker","revision":1,"leaseToken":"<leased-token>","payloadHash":"<exact-payload-hash>","status":"REVIEW_REQUIRED","message":"Native mapping required"}
```

Optional `nativeDocumentId` is a native UUID. The current worker, token, revision and payload hash must match an unexpired lease. Exact repeated result bodies replay the cached acknowledgement. Changed results conflict. OBSERVED is accepted only for LEGACY_LINKED/REGISTRY_ONLY; APPLIED requires an approved NATIVE_CORRECTION revision and a nonzero native document UUID. Other unmapped operations remain REVIEW_REQUIRED or FAILED rather than falsely applied.

## Explicit native correction approval

`POST /api/erp-exchange/warehouse-v2/outbound/{operationId}/approve-native-command` requires ADMIN or FINANCE_CONTROLLER with company-wide scope and an `Idempotency-Key` header. Inspect the stored source facts and `eligibleContext` first. Example body:

```json
{
  "expectedRevision":1,
  "nativeCommand":{
    "documentType":"Документ.КоррекцияНоменклатуры",
    "context":"INVENTORY_CORRECTION",
    "organizationId":"<pinned-native-org>",
    "rows":[{
      "domain":"STOCK",
      "mmMaterialId":"<MM-material-uuid>",
      "mmWarehouseId":"<MM-warehouse-uuid>",
      "materialId":"<native-material-uuid>",
      "warehouseId":"<native-warehouse-uuid>",
      "stockAccountId":"<native-stock-account-uuid>",
      "contraAccountId":"<explicitly-allowed-native-contra-account-uuid>",
      "quantity":"-2",
      "amount":"-20",
      "contraAnalytics":[{"index":1,"catalog":"СтатьиЗатрат","id":"<native-analytic-uuid>"}]
    }]
  }
}
```

Allowed generated contexts are INVENTORY_CORRECTION for approved inventory ADJUSTMENT/WRITE_OFF sources, WRITEOFF for eligible actual write-offs, and VALUE_CORRECTION for eligible zero-quantity account-aware valuation facts. Every command row must prove the complete exact MM material/warehouse/domain/quantity/value contribution; changing totals or context is rejected. Only owned STOCK facts in UZS are eligible for the current native correction adapter. Production, transformations, returns, custody, installation, holding, packaging, transit and reversals require dedicated mappings and cannot use this shortcut.

Approval creates the next revision of the same operation and resets delivery to PENDING; it does not post directly. Applied or currently leased operations cannot be reapproved. The native operation/document guard must preserve an already applied native document and require explicit reversal/correction rather than recreating it. Contra analytics use indexed native catalog references or explicit typed String/Number descriptors; native account analytic requirements remain authoritative.
