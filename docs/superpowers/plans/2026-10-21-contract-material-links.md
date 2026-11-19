# Verified contract material links implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement this backend task and review it. The user has approved preserving group lines and recovering exact materials, and requested making the remaining active group lines usable for income.

**Goal:** Permit explicitly verified materials on imported group contract lines without changing the source group, material masters, historical stock, or financial terms.

**Architecture:** Immutable per-specification/material bindings store material stock unit, contract unit, positive exact conversion factor (contract quantity per stock quantity), source evidence and manifest hash. Existing exact-group/exact-unit eligibility remains. Bound members use their stock units in receipt responses. The shared limit sums effective receipts converted to contract units using immutable bindings. New ADMIN-only migration endpoint inserts source-bound bindings atomically with row/chunk idempotency and audit. No general fuzzy matching or automatic master-data reparenting runs in the backend.

**Tech stack:** Spring Boot/JPA/Postgres/Flyway, existing migration DTOs/idempotency/audit, JUnit.

## Backend task

- [x] Add failing tests for cross-group bound membership, duplicate unit IDs (factor 1), kg/tonne conversion (0.001/1000), mixed linked/native members sharing one limit, posted reversal exclusion, serialized capability restriction, cross-company/deleted material rejection, incompatible or changed unit snapshots, immutable duplicate links, replay and exact source/version/manifest validation.
- [x] Add a dedicated binding entity/repository and new Flyway migration (next available number). Scope all rows by tenant/company/specification/material; unique binding; factor limited to 1, 0.001, 1000 initially. Retain exact evidence JSON + source/prepared manifest SHA, contract/material unit snapshots. Do not alter stock tables or protected files.
- [x] Add ADMIN-only POST /api/migration/contracts/material-links and read-only GET bindings for a specification. Reuse MasterChunkRequest/Response and strict allowlisted payload, maximum 200 rows, distinct targets, zero-ID and malformed source rejection. Source identity is Document9197Materials/Goods + original source ID, expectedMasterId specification ID; row idempotency must include material ID because several members can be bound to a group.
- [x] Require live company/tenant, original migration marker, exact specification version/contract/group/unit, ACTIVE group spec and ACTIVE parent contract. Validate linked material source code/marker, active TMB and own scope; material/contract unit snapshots resolve ACTIVE scoped units; factor is backed by explicit reviewed source unit identity. Equal units require factor 1. Different unit IDs: validate physical equality or kg/tonne conversion against stored unit names/labels using a small explicit vocabulary, never arbitrary numeric factors. Unknown packaging conversions are rejected. No stock effects. Existing binding must be identical to be accepted, otherwise reject. Reject adding a factor-changing binding for a material that has already received against that specification.
- [x] Extend ContractGroupReceiptService and MaterialRepository eligibility query to include explicit bindings as well as original exact-group/exact-unit members; same active TMB/serialized filters. Validate eligibility again during receipt while existing specification lock is held. Unit snapshot drift fails closed.
- [x] Update effective receipt sum to use immutable link factor or 1 for unchanged native members. Do not rewrite historical movements. Convert candidate remaining quantities and limits into stock units with downward rounding to supported quantity precision; convert displayed contract price accordingly. Group header stays in contract units. Validate requested stock quantity * factor against remaining contract quantity. Maintain group line identity and all financial source values.
- [x] Build and run focused backend tests (JDK21), verify protected-file diff empty, inspect query behavior on a disposable test database if integration resources are available.
- [x] Spec compliance review, then code-quality review; fix findings before committing. No deploy from implementer.

## Controller data task

- [x] Query readonly 1C receipts with posted/unmarked headers, exact company/supplier/contract links, material/UOM and decimal VAT amounts.
- [x] Build reviewed mappings only from explicit source group membership plus verified unit aliases, or same-contract receipt evidence corroborated by material identity, compatible unit and price. Reject ambiguous competing lines/materials and unsupported conversions. Name similarity and price alone remain candidates.
- [x] Prepare immutable payload/evidence manifests and durable journal, take post-V43 target/stock baseline, verify every material binding scope/source code/unit and every target specification version before writes.
- [ ] Run exact-number importer tests including replay, payload mutation and partial recovery. Create/attach reviewable MR, deploy only within authorized workflow. Import bindings and verify every linked line appears in receipt member API, all contract/spec/master/stock snapshots unchanged. UI preview only; no receipt submission.
- [ ] Record resolved totals and a concrete evidence gap list for remaining lines; sync main safely preserving existing user edits and protected files.

## Verified implementation checkpoint — 2026-10-21

Backend is based on remote main `79c87e30`; V45 is additive and protected files are unchanged. The final package passed 108 focused tests, including 21 PostgreSQL tests. Specification and quality reviews passed after reproducing and fixing company/warehouse/specification/material lock-order cycles. Importer/evidence tests passed (8); the immutable v5 package contains 604 verified links across 413 active contracts, with 738 unresolved lines. Frontend MR !75 contains the compatible batch-income selector and passed 33 tests plus its build. Production deployment and link import remain pending at this checkpoint.
