# Standalone Treasury native exchange, protocol 1

Treasury and the COMPACT warehouse have independent keys, workers, queues, native mappings and scopes. Treasury initiates every HTTP request. This release creates an ordinary supplier-payment **draft** in configuration `Договоры 1.1.1.01`; it does not post, approve or send payments to a bank. IFRS and warehouse transports are unchanged.

## Deployment and authentication

Enable explicitly with `mm.treasury.native-enabled=true`. The default is false for existing deployments. Configure `mm.treasury.key` (at least 32 characters, different from the warehouse key), `tenant`, `company`, `source-base-id` and `native-organization-id`. `allowed-operation-types` defaults to `ОплатаПоставщику`; payroll and other operations remain outside the native adapter's supplier-only scope. Missing scope prevents execution.

All machine endpoints require `X-MM-Treasury-Key`, `X-Tenant-Id`, `X-Company-Id`, and the pinned `sourceBaseId` in the body. Human JWT authorities do not authorize them. Use TLS; scoped service-key authentication and TLS integrity protect the entire request body. This consciously replaces the draft plan's additional per-message HMAC proposal. The server persists complete-body hashes for immutable event identity. Never use the legacy Treasury status callback as evidence.

`allow-local-http=true` permits loopback-only test traffic. `allow-test-evidence=true` additionally permits `TEST_FIXTURE` financial evidence only when local HTTP is enabled and the configured source starts with `test-`. Both default false. Test facts remain clearly labelled and are not evidence of real banking execution.

When native mode is enabled, scheduled/manual old Treasury dispatch, generic Treasury actions, Rabbit/webhook delivery, provider status/download/health requests, legacy callbacks, mock acknowledgements and manual `markPaid` cannot execute Treasury payments. Existing pending Treasury integration events are **not adopted automatically**: retain their audit, reconcile any existing native document, and explicitly cancel/resolve each old event before switching transport. Do not switch back to legacy while native tasks or acknowledgements remain unresolved.

## Queue

`POST /api/integrations/treasury/tasks/poll`

```json
{"protocolVersion":1,"workerId":"treasury-worker","sourceBaseId":"test-treasury","limit":1}
```

Response: `{"protocolVersion":1,"tasks":[...]}`. A task contains `taskId`, `leaseToken`, `type:"PAYMENT_REQUEST"`, `eventId`, `payload`, `payloadJson`, `payloadHash`, `leaseExpiresAt`. The hash is lowercase SHA-256 of the exact UTF-8 bytes of `payloadJson`; native code executes that verified JSON, not a second unverified representation. One worker owns a 120-second lease; expired leases rotate the token and do not create a new task/event. Same-worker polling returns its current lease.

Payload: `mmPaymentId`, `contractPaymentId`, `contractInvoiceId`, optional `purchaseOrderId`, decimal-string `amount`, ISO uppercase `currency`, `purpose`, and `native` with explicit `organizationId`, `counterpartyId`, `contractId`, `bankAccountId`, `organizationBankAccountId`, `currencyId`, `expenseArticleId`, `initiatorId`, `operationTypeId`. For non-UZS currencies an explicit positive decimal-string `exchangeRate` is required. No first-match contract/account selection is allowed.

`POST /api/integrations/treasury/tasks/{taskId}/result`: common scope/version plus `workerId`, `leaseToken`, `eventId`, `payloadHash`, `status:"CREATED"|"REJECTED"`, `payload`. CREATED requires a UUID `nativeRequestId`, `nativeNumber`, and the complete snapshot below. It acknowledges a native draft, never approval or payment. REJECTED requires a bounded `message`; it creates a visible FAILED task. Identical results are idempotent; changed payload, wrong worker/hash/event or a stale lease is rejected. A CREATED acknowledgement marks only its scoped, correlated source integration event DELIVERED and stores the native request GUID; REJECTED marks it FAILED. Native retry returns that same source event to NEW without creating another identity. Replaying an identical cached acknowledgement repairs historical source-event delivery bookkeeping without changing request snapshots, executions or invoice totals. DELIVERED is a terminal transport state even after a transport configuration change. Operator retry preserves task/event/payload identity and rotates the lease. Native CREATED results are reused; a previously rejected attempt may be retried without creating duplicate documents.

## Inbound evidence

`POST /api/integrations/treasury/events`: `protocolVersion`, `sourceBaseId`, stable `eventId`, `eventType`, UUID `nativeRequestId` (except master data), positive integral `revision`, and `payload`.

`REQUEST.UPSERT` snapshot includes decimal-string `amount`, `currency`, `number`, `purpose`, `organizationId`, `operationTypeId`, `counterpartyId`, `contractId`, `approvalStatus`, `bankStatus`, and native field/evidence snapshots. `nativeFields.РасчетныйСчет.nativeId` and `nativeFields.РасчетныйСчетОрганизации.nativeId` identify the two accounts. Native-origin requests are stored independently with no fabricated invoice/order. Optional `mmPaymentId` alone does not attach them to a payment: an authorized operator must confirm saved source/org/supplier/contract/account mappings and equal amount/currency. Already paid/cancelled targets cannot be linked.

`MASTERDATA.UPSERT` payload requires `objectType`, `nativeId`; it is retained in the scoped durable inbox. The native exporter only sends explicitly allowlisted references. Admins configure confirmed payment mappings rather than inferring cross-base GUID equality.

`PAYMENT.SETTLED` and `PAYMENT.REVERSED` payloads require stable `nativePaymentId`, positive decimal-string `amount` (up to four decimal places), `currency`, ISO `paymentDate`, `finality:"FINAL"`, `sourceKind:"BANK_STATEMENT"|"BANK_EXECUTION"`, and optional `bankReference`. Reversals also require `originalPaymentId` belonging to an execution of that same request; total reversals cannot exceed it. IDs normalize trimmed case; an execution delivered through another event/channel cannot increase its amount again. Changed economic facts for an existing execution identity are rejected for reconciliation.

Each execution/reversal is immutable and separate. MM payment and invoice actual totals come from the ledger, including partial payments and reversals. Delivery, approval, sent-to-bank, SUCCESS and paid-looking native statuses never create money. Cumulative `ФактическиеВыплаты`/bank-journal snapshots remain observations; they are never added to individual transactions.

**Production settlement-source limitation:** current native capture has not established a transaction-complete, final bank source with durable execution IDs. It exports cumulative/status evidence only. Therefore real automatic settlement is not enabled by this adapter until that source is verified and implemented. Isolated `TEST_FIXTURE` evidence exercises protocol/accounting only.

Incoming changes to linked supplier/contract/account or amount/currency persist an audited reconciliation quarantine. The previous approved snapshot and financial totals remain intact; future bank facts cannot pay the old invoice. Same-revision contradictory snapshots are also quarantined. The current operator UI exposes the reason/history; clearing such a quarantine requires an explicitly reviewed administrative repair. There is no general-purpose clear flag or deletion API.

## Operator API and rights

`GET /api/treasury/config`, `/requests?mmPaymentId=&externalOnly=`, `/requests/{id}`, `/readiness/{mmPaymentId}`, `/reconciliation`, `/mappings?mmPaymentId=`. Request cards separate delivery/approval/bank status and include settled/reversed/net/remaining decimal amounts, native number, history, settlements and blocking reasons.

`PUT /mappings/{mmPaymentId}` is ADMIN-only. Flat fields: `sourceBaseId`, `nativeOrganizationId`, `nativeSupplierId`, `nativeContractId`, `nativeSupplierBankAccountId`, `nativeOrganizationBankAccountId`, `nativeCurrencyId`, `nativeExpenseItemId`, `nativeInitiatorId`, `nativeOperationTypeId`, `purpose`, optional `nativeExchangeRate`. Once queued, mappings are immutable.

`POST /requests/{id}/link {"mmPaymentId":"..."}` and `/tasks/{taskId}/retry` require ADMIN or FINANCE_CONTROLLER. Reads require finance/procurement management/specialist, accountant, budget controller or auditor roles; VIEWER alone is insufficient. Tenant/company filtering applies independently of role.

## Verification

Run TreasuryNativeProtocolTest, TreasuryNativeSecurityTest, TreasuryNativePostgresTest, TreasurySpringProxyPostgresTest, TreasurySettlementServiceTest plus existing Treasury callback/acknowledgement policies and warehouse pull/inbound/processor tests. PostgreSQL tests use unique temporary schemas in `MM_PERIOD_PG_URL`; actual migrated contract table definitions are cloned, and no public business rows are changed. The complete protocol suite also runs through real Spring CGLIB transaction proxies, including direct service transaction commit/rollback checks. Tests cover 100 → 40 → 100 → duplicate still 100 → reversal 90, scope isolation, worker concurrency/lease fencing, immutable identities, rejection retry, unmatched native import, source-correction quarantine, currencies, and legacy transport blocking. Native/browser end-to-end evidence must be reported separately from Java tests and from real bank execution.
