# Shared contract decisions — proposal for user approval

This document resolves differences between independently drafted section designs. It is a proposed consolidated design, not approval to implement. On terminology/default conflicts it takes precedence over the section drafts; requirement scope remains REQUIREMENTS.md. Implementation plans must apply these decisions consistently.

## D01. One stock identity and one quantity authority

Use the canonical name `StockContext` rather than a second independent StockAccount concept. The immutable context contains ownerPartyId, a typed holder reference (COMPANY / EXTERNAL_PARTY / EMPLOYEE / DEPARTMENT plus scoped ID), optional custodyAgreementId and optional typed earmarkId. StockParty identifies legal owners (our company or external counterparty); an employee user account is not a legal-owner shortcut. Responsible employee/MOL assignment is separate from physical holder, so changing warehouse responsibility need not invent a physical move.

An exact stock position includes tenant, ledger company, material, location hierarchy, batch and StockContext. Serial identity follows exactly one position/context. Section 6 owns owner/holder/agreement/earmark semantics; section 5 owns posting, locks, projections and key migration. Section 7 accountability events are atomic evidence/projections of those postings, not an independently editable quantity ledger. Section 9 owns associated cost effects. No other service directly writes the balance projection.

Operational state remains independent: USABLE, QUARANTINE, BLOCKED, REJECTED and approved additional states. Physical = all state quantities; free = eligible USABLE minus active reservations. Available foreign-owned quantity is never interpreted as permission to consume it. Transit is separate from on-hand and counted once. VMI responsibility is independent metadata, not a mutually exclusive ownership type or a stock-key dimension.

## D02. Preserve actual facts and money

Warehouse-to-custody transfer preserves company-owned value. Actual consumption, loss and disposal have distinct approved evidence and cost effects. A deliberately approved immediate-consumption action may compose transfer/acceptance and consumption atomically while retaining both facts. Every custody cohort reconciles issued = consumed + returned + held, with disposal, handover and reversal terms explicit where present.

Do not silently change existing valuation methods or invent a universal new carrying-cost default. Use the approved company/material policy version. New policy setup must explicitly choose and identify its behavior; unmapped legacy ACTUAL_COST remains visible and must be reconciled before migration. Analytical valuation is not automatically a carrying-value posting.

No general tax/legal rule is being approved by these engineering documents. Finance-configured ownership terms, tax classifications, currencies/rates, cost methods, tolerances and period policy remain explicit versioned inputs. Defaults must not substitute for required accounting configuration.

## D03. UOM and original evidence

Document/base quantities and the exact approved conversion snapshot are captured per line. Server calculations are authoritative. Reject unrepresentable stock quantities; no rounding creates/destroys units. Base UOM cannot silently change after use. Serial opening origins may be opening-document evidence rather than fabricated supplier receipts; production and legitimate customer-return origins require their own verified provenance adapters.

Use one canonical expiry API value with migration/compatibility mapping for existing expiresOn/expirationDate fields. Preserve unknown/conflicting history explicitly. Retest does not silently change manufacturer expiry.

## D04. Receipt, return, demand and quality meanings

Physical arrival, commercial acceptance, quality release, ownership transfer and actual consumption are separate facts. Count physical rejected/damaged/excess quantities at their actual holder under restrictions; never drop them because they are not invoiceable. Replacement and overdelivery require bounded authorization and cannot expand commercial obligations twice.

Quarantine status alone does not decide commercial fulfillment: use the approved acceptance policy and actual acceptance evidence. Accepted-but-quarantined stock may fulfill a commercial obligation while remaining unavailable for use. Rejected/unaccepted stock does not fulfill that obligation. Invoice eligibility applies its own approved receipt/quality/contract rules.

Returns default to quality hold; an authorized, recorded quality release can be completed as part of an approved receipt workflow when policy permits. The W04 accepted usable return includes that release before its available quantity assertion. An originally usable condition flag alone is not release evidence. Returned unused material does not automatically generate replacement procurement: section 2 records changed net fulfillment and requires a decision to preserve or reduce remaining demand.

Intercompany acceptance is required for the group companies hosted in the current application. Cross-tenant federation is conditional on the actual approved deployment, not an extra universal requirement. Each company authorizes its own documents and receives appropriate financial evidence; ordinary current-company security is not relaxed.

## D05. Counting, reports and history

Uncounted is null; actual zero is an observation. Count freeze, blind visibility, commission and discrepancy permissions are policy-controlled, with independent approval and append-only history. Safety holds may immediately restrict eligibility without rewriting the frozen count baseline; subsequent ledger changes follow approved freeze/exception rules.

Operational and report definitions come from the same policy versions and posting facts. Reports cannot introduce a new hidden aging/non-moving threshold. Existing configured policies are preserved; new policies are explicit settings. Historical as-of quantity/value must reconstruct or snapshot valid posting evidence, not relabel current totals as historical. Query-level permissions apply before aggregation, drilldown and export.

## D06. Migration and integration ownership

All migrations are additive after the current V1 baseline; do not rewrite it. Each migration version has one assigned writer. Ambiguous legacy owner/custody/serial/cost facts require visible reconciliation, not assumed own-stock backfill. Preserve prior IDs and origin history through compatibility adapters.

WMS snapshots are reconciliation evidence, not permission to overwrite stock. Production/provider messages require authenticated source IDs, idempotent business commands, correct quantities and valid prerequisites. Queued/staged messages, mock signatures and skipped database tests never count as real completion evidence.

Shared frontend registries and posting/cost/security files have one writer per integration window. Section chats implement bounded slices only after approved design and an executable plan with assigned files. The coordinator integrates and verifies cross-section acceptance.
