# MM completion program — design proposal

Status: user approved the consolidated design on 2026-09-29: “approved, go ahead, implement”. Executable planning and implementation are authorized. Work occurs in coordinated backend/frontend worktrees under .worktrees; all 92 requirements remain in scope. Start with REVIEW.md; CONTRACT-DECISIONS.md resolves shared terminology and policy defaults.

Workspace: `/Users/abdurahmoniy/Projects/work/MM-Navoiy`. It contains two separate Git repositories, `mm-backend` and `mm-front`; the parent is not a repository. Baselines inspected on 2026-09-29: backend `40698b1`, frontend `a5777238`; both worktrees clean before program documentation.

## Objective and completion evidence

Complete all 11 sections in REQUIREMENTS.md, preserving the full scope of the original checklist. The earlier approximate 7/10 assessment is a discovery aid, not an authoritative acceptance specification. Re-read actual code before deciding a capability exists or is missing.

Completion requires usable, integrated backend and frontend workflows; additive, verified PostgreSQL migrations; tenant/company/warehouse permissions; partial-operation and reversal behavior; audit and concurrency invariants; meaningful tests and real browser-to-local-PostgreSQL acceptance. External integrations require real provider confirmation or remain explicitly unverified. Mocks, API schemas, enum values and a menu entry cannot establish full completion. Never mark the entire goal complete from a subset of passing checks.

Preserve financial ownership of payment execution, banking, depreciation and statutory accounting. MM owns material transactions, valuation and invoice verification. Existing fixed-asset, WMS and laboratory extensions must remain compatible but are not substitutes for missing MM processes.

## Approaches considered

1. **Recommended: extend the existing application with shared domain contracts and phased vertical slices.** Reuse functioning procurement, stock, approval and costing services. Establish ownership/custody/UOM and document contracts before dependent features. This needs coordination but preserves working behavior and permits incremental verification.
2. **Build every section independently at once.** Faster initial coding, but stock keys, receipt/issue posting, common frontend registries, migration numbers and reports would conflict. Reject as the integration strategy.
3. **Replace the application.** Cleaner initial architecture is possible, but would require rebuilding the large existing workflow and permission surface. Reject without evidence that incremental extension is infeasible.

## Design boundaries

- A stock owner is independent of physical holder/location, operational status, earmarking and replenishment manager. Section 6 proposes these semantics; section 5 owns incorporation into stock mutations; section 9 owns costing/financial effects.
- Base quantity, document quantity, UOM and conversion snapshots must be consistent across receiving, reservation, issue, transfer, count and invoice matching. Section 1 owns the contract.
- Ordinary-material custody and actual consumption are separate from warehouse issue and fixed-asset custody. Section 7 owns the accountability model and reconciles issued = consumed + returned + still held, including reversals.
- Business documents reference immutable posting evidence. Corrections produce linked reversals or adjustments, not silent mutation of posted history.
- Intercompany transfer requires independently authorized sending/receiving documents and financial handoff. A cross-company query or unilateral stock edit is not completion.
- Invoice verification is line-based and distinguishes order quantity, accepted receipt/service evidence, prior invoicing, price/tax/currency/UOM and tolerance decisions. Document amount limits alone are insufficient.
- External events are authenticated, idempotent, observable and reconciled to business documents. Fetching/staging an inbound event does not count as posting production demand or receipts.
- Section 11 derives reports from the operational ledgers and shares definitions with drilldowns/exports. It cannot invent facts absent from sections 6–9.
- Use current React/Ant Design patterns and RU/UZ/EN localization. New workflows require clear actions, validation, stale-state handling and recovery.

## Section ownership and sequence

Each section has one user-visible chat and owns its section design file. The coordinator owns this program, REQUIREMENTS.md and THREADS.json. During this initial phase the chats perform source research and write only `section-NN-design.md`; no implementation edits, migration changes, commits or shared build output.

After design approval, each section receives an implementation plan using the requested writing-plans skill and explicit file ownership. Shared files must have one writer per integration window. In particular StockBalance/StockMovement/costing contracts, the frontend modules/navigation/endpoints registries and Flyway version numbers must be coordinated. Do not reset, clean or commit another chat's work.

The user selected **warehouse operations and inventory** as the first implementation milestone. Receiving, stock movements, physical counts, returns and warehouse reporting take priority once the contracts they require are ready; all remaining requirements stay in scope.

Dependency stages:

1. Section 10 verification/document-control baseline, section 1 UOM/master/opening foundations, section 6 ownership contracts, section 5 stock-key/posting contracts.
2. First implementation milestone: section 4 receiving, section 5 transfers/stock, section 8 counting/returns/quality, with required portions of sections 6–7 custody, section 9 cost reconciliation and section 11 warehouse reports.
3. Complete sections 6–7 special-stock/custody, section 9 invoice matching, sections 2–3 demand/procurement and the remaining specialist scenarios in sections 4–8. Independent slices can proceed earlier when their dependencies are absent.
4. Complete section 11 reporting, cross-section acceptance and full regression closure.

## Evidence already available, to revalidate when relevant

Prior review compiled the backend using Java 21 and ran 113 selected tests: 97 passed, 2 failed assertions and 14 errors. Material update had two NOT NULL failures; several demand/procurement fixtures no longer satisfy current origin/naming/editor contracts. Do not hide these failures by weakening tests. Frontend node_modules was absent during that review. No full PostgreSQL/browser/external-provider acceptance has been established.

## Design-phase work checklist

- [x] Inspect current repository state, prior assessment and requested skills.
- [x] Decompose all 11 sections into requirements and ownership boundaries.
- [x] Create and start 11 section chats with bounded design deliverables.
- [x] Collect section designs, exact source evidence, contract dependencies and acceptance scenarios.
- [x] Reconcile contradictions and review scope/ambiguity in the combined design.
- [x] Present the complete reviewable design to the user and obtain the brainstorming skill's design approval.
- [ ] Produce executable section implementation plans using writing-plans after approval.
- [ ] Implement, integrate, verify and close every requirement with evidence.

The current design activity concerns workflows and data contracts, not visual layout selection; a visual companion is not needed for this phase. The user's priority is warehouse operations and inventory; all 11 sections remain in scope.
