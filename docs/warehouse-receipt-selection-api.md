# Supplier receipt: backend recommendations and optional AI advice

Applies only to **Warehouse execution → Receive materials → From a supplier**.

## Behavior and implementation

1. Authorize the warehouse, tenant, company and active, date-valid contract before returning data.
2. Read paginated MATERIAL contract positions, including positions without a usable material binding. Never silently omit these positions in V2.
3. Resolve direct materials or eligible group members/verified contract-material links using the existing binding and conversion rules.
4. Return deterministic initial checkbox advice. An explicitly configured warehouse receipt profile takes precedence. When there is no profile, positive stock in the same warehouse supplies known material groups as a conservative fallback. No profile and no matching stock knowledge means manual review, with no guessed selection.
5. Let users select any technically valid contract candidate, even when its warehouse group is not recommended. A warehouse ALLOW/DENY preference affects the recommendation, not command authorization. Material lifecycle, scope, binding, serial capability, UOM, quantities, shared limits and approval remain mandatory.
6. Call AI only from the separate advice endpoint when the user presses the button. AI cannot bind a missing material, approve a document or post stock.

Warehouse stock is evidence of groups already held there, not proof of its complete purpose. Configure `/api/warehouses/{warehouseId}/receipt-profile` for authoritative recommendations. An intentionally empty configured profile does not fall back to stock.

## Initial context (no AI calls)

`GET /api/workspaces/warehouse-execution/manual/contracts/{contractId}/receipt-context-v2?warehouseId={warehouseId}&page=0&size=25`

Returns the usual `ApiResponse` wrapper. `data` contains:

```json
{
  "contractId": "uuid",
  "contractNumber": "5073899",
  "warehouseId": "uuid",
  "warehouseName": "073.1 склад",
  "warehousePurpose": "Warehouse receipt profile description, if configured",
  "profileVersion": 1,
  "positions": {
    "page": 0,
    "size": 25,
    "totalElements": 12,
    "totalPages": 1,
    "content": [
      {
        "specificationId": "uuid",
        "version": 0,
        "kind": "MATERIAL",
        "name": "Cable",
        "status": "ACTIVE",
        "readiness": { "selectable": true, "reasonCode": null },
        "candidateCount": 1,
        "materials": [
          {
            "specificationId": "uuid",
            "materialId": "uuid",
            "materialName": "Cable",
            "remainingReceiptQuantity": 10,
            "contractQuantityPerStockQuantity": "1",
            "receiptSelection": {
              "eligibility": "ALLOWED",
              "recommendedSelected": true,
              "decisionSource": "MATERIAL_GROUP_POLICY",
              "reasonCode": "MATERIAL_GROUP_ALLOWED",
              "reasonText": "Material group matches the warehouse receipt profile",
              "policyVersion": 1
            }
          }
        ]
      }
    ]
  }
}
```

This example abbreviates existing material-line fields. Each position also returns contract quantity, price, unit, group, remaining quantity, and candidate count. Material lines include actual warehouse stock totals.

Position pagination is independent of candidate pagination. V2 previews at most 10 material candidates per position. Fetch/search other choices using the existing endpoint:

`GET /api/workspaces/warehouse-execution/manual/contracts/{contractId}/specifications/{specificationId}/materials?warehouseId={warehouseId}&search=...&page=0&size=25`

Selection interpretation:

| Field/value | Frontend behavior |
| --- | --- |
| `receiptSelection.recommendedSelected=true` | Initial checkbox default only |
| `eligibility=REVIEW_REQUIRED` | Unchecked initially; user may check it |
| `eligibility=BLOCKED` | Candidate cannot execute; show the reason |
| `readiness.reasonCode=MATERIAL_CHOICE_REQUIRED` | Resolve one or more material quantities under this specification's shared limit; the position is not an executable material itself |
| `MATERIAL_BINDING_REQUIRED` | Keep the contract position visible; do not submit an invented material ID |
| `SPECIFICATION_NOT_ACTIVE`, `INVALID_CONTRACT_PRICE`, `CONTRACT_UNIT_UNAVAILABLE`, `CONTRACT_GROUP_UNAVAILABLE` | Show the reason and repair the contract/catalog before receipt |
| `decisionSource=WAREHOUSE_STOCK_GROUP` | Recommendation inferred from current stock, without AI |

Multiple alternatives in one group specification are not all auto-selected: that would assign the same full shared contract limit to several materials. The user chooses material/quantity. This restriction does not prevent selecting several distinct contract positions automatically.

## Optional AI button

`POST /api/workspaces/warehouse-execution/manual/contracts/{contractId}/receipt-ai-advice`

```json
{ "warehouseId": "uuid", "page": 0, "size": 25, "provider": "MM_AI", "language": "uz" }
```

The backend calls **MM AI `/v1/receipt-advice/suggest`**, receipt contract `2026-11-03.1`.
The server reconstructs and authorizes the V2 context, then sends an object with
`companyId`, `tenantId`, `language`, and `backendContext` (the data object, not an
`ApiResponse` wrapper). `X-API-Key`, `X-Tenant-Id`, and `X-Request-Id` are set by the
server. Client-supplied scope or context is never forwarded.

`provider` is optional; `MM_AI` is the current engine. Legacy `CHATGPT`, `CLAUDE`,
and `GEMINI` values remain accepted for request compatibility and add
`PROVIDER_MANAGED_BY_SERVICE`. They are **not** forwarded and do not select the
external model; that is configured in MM AI. The frontend should remove its old
provider selector for this flow. Languages: `uz` (default), `ru`, `en`.

The default AI page size is **25**, matching V2. Always send the same explicit
`page` and `size` used by the table. AI accepts 1–25 positions and 10 previews per
position; a V2 page of size 100 must not be silently reinterpreted as size 25.
Use a smaller shared page size instead. Group `kind=MATERIAL_GROUP` and technical
`decisionSource=CONTRACT_READINESS` are supported by the new external contract.

The response keeps `ApiResponse.data` with:

- `status`, `message`, `backendContext`, `suggestions`;
- `complete`: coverage of eligible previews on this page only;
- `warnings`: partial results, preview limits, configuration or service failures;
- `retryAfterSeconds`: optional busy backoff hint (no automatic retry).

Each suggestion has `specificationId`, `materialId`, `recommendedSelected`,
`confidence` (0–1), `reasonCode`, `reasonText`, and `requiresMaterialChoice`.
Reason codes are `DESCRIPTION_MATCH`, `DESCRIPTION_CONFLICT`,
`INSUFFICIENT_EVIDENCE`, or `MATERIAL_CHOICE_REQUIRED`. Confidence is not an
execution permission or calibrated probability. Only `DESCRIPTION_MATCH` can
recommend selection, and a choice-required suggestion stays unselected.

Statuses remain `READY`, `NO_CANDIDATES`, `INVALID_RESPONSE`, `UNAVAILABLE`.
`READY` requires at least one accepted suggestion. Unknown/blocked pairs,
duplicate pairs, invalid confidence or malformed suggestions are discarded;
coverage is marked incomplete with `AI_OUTPUT_REJECTED`/`PARTIAL_ADVICE`.
Two positive alternatives under one specification are changed to explicit manual
choice; separate specifications may both be recommended. Missing suggestions
never mean the existing checkbox should be cleared.

The response returns the original authoritative MM context, not a trusted copy
of an upstream echo. The transport copy localizes/bounds name and purpose text
to 2,000 characters without changing catalog data. The echoed fields are checked
against this copy; extra optional fields may be added by MM AI. After the call,
MM reads the current context again. A changed profile, position, candidate, stock
or limit snapshot returns `UNAVAILABLE`, `STALE_CONTEXT`, the current context and
no suggestions. Authorization failures continue to be normal MM errors.
The frontend must still discard superseded responses and preserve touched rows.

### Configuration and failure behavior

Receipt advice inherits the existing `mm.ai.base-url` / `mm.ai.api-key`
(`AI_BASE_URL`, `AI_API_KEY`) unless receipt-specific overrides are supplied.
The pre-existing shared production AI settings are preserved for other features.
`AI_RECEIPT_ADVICE_BASE_URL` and `AI_RECEIPT_ADVICE_API_KEY` override only the
receipt endpoint and its key; they never replace credentials or URLs for RFQ,
Copilot, MXIK or other analyses. An explicitly empty receipt key disables only
receipt advice. Missing receipt URL/key returns advisory `UNAVAILABLE` /
`AI_NOT_CONFIGURED`; manual receipt remains available. Credentials never belong
in browser requests or receipt bodies.

Receipt-specific settings (other AI analyses retain their existing timeouts):

| Property | Production environment variable | Default |
| --- | --- | --- |
| `mm.ai.receipt-advice.base-url` | `AI_RECEIPT_ADVICE_BASE_URL` | inherits `mm.ai.base-url` |
| `mm.ai.receipt-advice.api-key` | `AI_RECEIPT_ADVICE_API_KEY` | inherits `mm.ai.api-key` |
| `mm.ai.receipt-advice.enabled` | `AI_RECEIPT_ADVICE_ENABLED` | `true` |
| `mm.ai.receipt-advice.timeout` | `AI_RECEIPT_ADVICE_TIMEOUT` | `120s` |
| `mm.ai.receipt-advice.max-request-bytes` | `AI_RECEIPT_ADVICE_MAX_REQUEST_BYTES` | `2097152` |

The byte setting bounds the serialized context before calling MM AI, independently
of its larger server body limit. Set the gateway/browser timeout to allow the
receipt call and local processing; the AI's 90-second model budget is not an HTTP
SLA. No DB transaction or inventory lock is held during inference. There is no
hidden retry, no `/analyze` fallback and no direct-provider prompt fallback.

Upstream auth/validation failures are not forwarded as MM user login failures.
A timeout, transport failure or upstream 4xx/5xx leaves backend/manual selection
available (`UNAVAILABLE`); malformed or changed upstream context returns
`INVALID_RESPONSE`. Busy errors return `RECEIPT_ADVICE_BUSY` and bounded
`retryAfterSeconds` (default 5). Other error bodies/messages are not exposed or
logged. Logs contain safe status/count/duration metadata only. Invalid MM input
and scope failures are not swallowed as AI failures.

Disable `AI_RECEIPT_ADVICE_ENABLED` to roll back optional advice without changing
any receipt/approval/stock data. Ordinary context GETs never call AI. No database
migration is required.

## Frontend integration and submission

- Replace the row delete action with a checkbox. Unchecking excludes a line from this receipt; it does not delete a contract position.
- Initialize checkboxes from backend advice once. Preserve user edits across refreshes and pagination; do not reapply defaults to touched rows.
- Store selections by `(specificationId, materialId)`. Do not use row indexes or material ID alone.
- Show AI suggestions separately and let the user apply them; do not silently overwrite their selections.
- Submit **only checked material lines** to the existing `/receipt-batch` endpoint with its existing request format and idempotency key. Advice fields are not authoritative command input.
- Quantities on material candidates use stock units. Group position remaining quantity uses contract units; use `contractQuantityPerStockQuantity` and the existing conversion/rounding rules. All members and all warehouses share the specification limit.
- Saving creates the existing approval document. The current approval flow rechecks technical rules and remaining limits before posting. Neither recommendations nor AI bypass approval.

The legacy `/receipt-context` response shape and the existing candidate picker remain available. They now return advisory warehouse recommendations too; V2 is required to display unresolved positions and paginate positions. This backend repository does not implement the browser checkbox or AI button; the frontend must wire these endpoints.

## Validation

Focused tests cover deterministic profile/stock recommendations, cross-group verified links, unresolved-position visibility, manual override of warehouse preference, shared limits and idempotency, approval rechecks, invalid material blocking, contract-group reference validation, and AI response validation/fallback. The H2 receipt integration fixtures mock Faktura evidence storage because its JSON schema is PostgreSQL-specific; dedicated Faktura tests remain separate.
