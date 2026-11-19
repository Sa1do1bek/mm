# Contract line recovery implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for backend implementation and review. Frontend AGENTS.md overrides automatic skill usage. Work in the existing isolated backend migration worktree and the dedicated frontend contract-lines worktree.

**Goal:** Migrate source contract lines faithfully, including group references, and make eligible ACTIVE contract lines usable for material receipt.

**Architecture:** One source line remains one specification. Exact items and group references share the MATERIAL line type but have mutually exclusive identities; group receipt resolves a real member at execution time and enforces a shared quantity. Unresolved or inconsistent rows remain DRAFT and cannot post stock.

**Tech Stack:** Java 21/Spring/JPA/PostgreSQL/Flyway; React/TypeScript; Python/SQLite migration journal.

## 1. Backend representation and endpoint (delegated)

- [ ] Add regression tests in `ContractMigrationServiceIntegrationTest` for importing ACTIVE-parent group lines, exact references, unresolved DRAFT retention, malformed scope/evidence, decimal preservation, batch rollback and identical replay.
- [ ] Run the focused tests before implementation and observe the missing-capability failure.
- [ ] Add a new schema migration, entity/DTO/mapper fields and isolated `lines` migration operation. Preserve the original import's validation and tests.
- [ ] Expose `contractLineVersion: 1`; accept existing `MasterChunkRequest` with original parent-manifest evidence in each line payload. Remove evidence-only fields before DTO conversion.
- [ ] Add group reference validation and strict normal-create/update validation. Preserve raw source evidence in the description and only admit unresolved references as DRAFT through the migration operation.

Example line shape:

```json
{"sourceEntityType":"Document9197Materials","sourceId":"<32 hex>","payload":{"code":"1C-DOCUMENT9197MATERIALS-<32 hex>","name":"Source group name","status":"ACTIVE","description":"Raw source evidence plus current package marker","contractId":"<MM UUID>","lineType":"MATERIAL","materialGroupId":"<MM group UUID>","unitOfMeasureId":"<MM unit UUID>","unit":"kg","quantityLimit":"50.0000","price":"100.25","originalPreparedManifestSha256":"<original contract package hash>"}}
```

## 2. Receipt execution and context (same backend task)

- [ ] Add tests for group/member and unit mismatch, DRAFT/expired-parent rejection, cumulative use across two materials, replay and reversal/cancellation exclusion.
- [ ] Extend receipt context with group metadata and remaining quantity; keep issue/asset contexts from offering unsupported group operations.
- [ ] Add the paginated scoped member endpoint specified in the design; use context-line response objects to minimize client divergence.
- [ ] Persist `contractSpecificationId` on stock movements. Lock the group specification, aggregate existing posted non-reversed receipts and reject any overrun before stock posting. Keep the lock through the existing transaction.
- [ ] Run focused tests and package with `JAVA_HOME=/Users/abdurahmoniy/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home`.

## 3. Immutable data recovery (root)

- [ ] Add Python tests for explicit SKU, preserved group, missing reference, unit/financial review cases, source identity deduplication and replay with a lost response.
- [ ] Build a package from the immutable contract and catalog/profile exports; use original source line keys. Verify profile/material/group/unit bindings before any write.
- [ ] Preserve all raw source rows and record a reason for DRAFT rows. Prioritize ACTIVE contracts without changing source quantities, prices, amounts or parent states.
- [ ] Add a durable journal and exact pending-command replay around the new endpoint. Start with one row, verify it, then bounded chunks.

## 4. Frontend (root, direct workflow per its AGENTS.md)

- [ ] Extend warehouse context types and add the scoped member lookup client.
- [ ] In `ManualWarehouseOperationModal.tsx`, distinguish the selected specification from the selected member context. Keep group selection keyed by specification and material selection keyed by material ID.
- [ ] Reset dependent fields on contract/specification changes and make empty-group/review states explicit. Preserve existing issue/asset flows and staged uploads.
- [ ] Add group fields to contract specification module/detail displays. Do not change protected files or unrelated local edits.
- [ ] Test group selection, scoped lookup, reset, remaining quantity, empty group and concrete-material compatibility; run the build.

## 5. Review, deployment and migration (root)

- [ ] Independently review specification compliance, then code quality; fix findings before creating concrete MRs.
- [ ] Confirm protected-file diffs are empty, attach created MRs to this chat, and use the approved existing CI deployment workflow.
- [ ] Apply the immutable prepared package only after capabilities are verified. Reconcile every line and parent contract, stock counts and journal completion.
- [ ] Verify a real ACTIVE contract's group specification/member selector in the signed-in UI, save proof and report exact imported/review counts.
