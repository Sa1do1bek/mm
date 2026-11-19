# MM completion — design for approval

**Warehouse operations and inventory is the first milestone. All 11 sections remain in scope.** There are 92 tracked requirements and 11 section chats, each with a source-backed design and acceptance scenarios. Approved by the user on 2026-09-29: “approved, go ahead, implement”. Planning and implementation are authorized; all requirements still need implementation evidence.

## Recommended approach

Extend the existing backend and frontend through small, working business flows. Establish shared ownership, UOM, posting and document rules first; then deliver the warehouse milestone. Preserve working procurement, quality and valuation functions.

We considered building all sections independently at once, but their shared stock, migration and frontend files would conflict. A rewrite would also discard substantial existing workflows. Coordinated extensions give us a practical route to complete the full checklist and verify each result.

## Rules to approve

- **One stock record system:** owner, holder/location, responsible employee, quality state and reservation are distinct. Moving goods into employee or contractor custody preserves owned stock and value until actual consumption or another approved disposition.
- **Separate receipt facts:** arrival, commercial acceptance, quality release and ownership transfer are recorded independently. Damaged and rejected goods remain physically accountable. Returns start on quality hold; an authorized recorded release makes them usable.
- **Controlled operations:** partial execution keeps the unfulfilled remainder. Corrections reference original postings. Retries cannot repeat a posting, and competing users cannot spend the same stock or receipt allowance.
- **Preserve approved business policies:** keep existing configured valuation and non-moving-stock rules. New financial policies require explicit configuration. No silent valuation-method change, inferred ownership or invented historical data.
- **Document controls:** use current approvals with revision-bound evidence and independent approval where required. Initial count policy is blind counting with controlled freezes and reviewed differences. Required signatures need verified provider evidence.
- **Safe migration:** extend the existing schema through additive migrations. Uncertain historical ownership/custody/value becomes a visible reconciliation case. WMS snapshots raise differences for review rather than overwrite balances.

The detailed resolutions are in [CONTRACT-DECISIONS.md](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/CONTRACT-DECISIONS.md). They take precedence over differing wording in individual drafts.

## First delivery milestone

1. Establish a reliable local PostgreSQL/browser acceptance environment and resolve relevant existing test failures.
2. Implement shared units, ownership, document controls and opening stock.
3. Complete receiving, reservations, custody/consumption, transfers and transit exceptions.
4. Complete warehouse returns, quality holds, counts and discrepancy posting.
5. Reconcile quantities, valuation, period close and warehouse reports.

A linked acceptance example starts with **100 pieces worth 10,000**. Issue 12 into custody, consume 5, return and release 3; transfer 20, receive 18 and approve loss of 2; then count one further shortage. The expected finish is **88 pieces in warehouses + 4 in custody = 92 owned pieces worth 9,200**. Every change must have approved, traceable quantity and cost evidence.

Separate scenarios cover foreign-owned stock, external storage, damaged receipts, serial identities and invoice quantity checks. For example, an invoice for 61 pieces must fail against 60 accepted pieces even if its total amount is lower.

See the concrete [warehouse acceptance contract](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/WAREHOUSE-ACCEPTANCE.md), including permissions, races, retries, reversals and failure recovery. These are proposed checks, not passing test results.

## All section designs

| № | Section design | Requirements | Main work |
|---|---|---:|---|
| 1 | [Справочники и организация учёта](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-01-design.md) | 5 | Approved UOM conversions, material policies, storage hierarchy and fully posted opening balances. |
| 2 | [Потребности и обеспечение](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-02-design.md) | 6 | Dated demand coverage, replenishment, reservation changes and fulfillment reconciliation. |
| 3 | [Поставщики и закупки](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-03-design.md) | 7 | Request split/merge, contract execution, order amendments, service line acceptance and evidenced claims. |
| 4 | [Поставки и приёмка](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-04-design.md) | 7 | Partial and direct receipts, shortages/damage, batch/serial evidence, imports and equipment handoff. |
| 5 | [Остатки и перемещения](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-05-design.md) | 8 | One stock posting path, transit, intercompany documents, status changes and exact reversals. |
| 6 | [Внешнее хранение и специальные запасы](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-06-design.md) | 7 | Separate owners/holders, external custody, consignment, contractors, packaging and independent VMI. |
| 7 | [Выдача, подотчёт и обеспечение производства](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-07-design.md) | 10 | Ordinary-material custody, responsibility handovers, consumption and production/repair returns. |
| 8 | [Возвраты, качество, инвентаризация и выбытие](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-08-design.md) | 9 | Return origins, quality/expiry holds, counts, discrepancies, non-moving stock and waste. |
| 9 | [Стоимость материалов и проверка счетов](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-09-design.md) | 7 | Cost lineage, additional costs, invoice-line matching, corrections and reconciled period close. |
| 10 | [Общие функции документов](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-10-design.md) | 12 | Consistent authorization, approval, audit, retry, prints/signatures and integration recovery. |
| 11 | [Отчёты MM](/Users/abdurahmoniy/Projects/work/MM-Navoiy/mm-backend/docs/superpowers/specs/2026-09-29-mm-completion/section-11-design.md) | 14 | Operational and historical reports reconciled with posting facts, drilldowns and exports. |

After the warehouse milestone, complete the remaining demand/procurement, special-stock, production/repair, invoice and specialist scenarios, followed by full cross-section regression. Independent work can proceed earlier once its shared contracts are ready. Each shared file and migration version has one assigned writer.

## Completion evidence

Each requirement needs the implemented backend and usable frontend, applicable PostgreSQL/migration/concurrency checks, authorization and reversal checks, and actual workflow evidence. Reports must reconcile to the same postings. Local mocks cannot establish remote integration or signing completion.

The earlier selected baseline tests had **97 passes, 2 assertion failures and 14 errors out of 113**; full acceptance has not passed. All 92 register entries remain **unverified**. Source presence and design completion do not raise those statuses.

Approval of this package authorizes the design direction and stated operational rules. The next step is executable section plans using the requested writing-plans skill, followed by warehouse-first implementation in the existing section chats. External provider details and company financial settings will be resolved when the corresponding workflow is concrete; missing evidence keeps that requirement open.

