# First milestone: warehouse operations and inventory

Status: proposed acceptance contract for design approval. No test below is claimed to pass. All 92 requirements remain in scope; this file defines the user's first delivery milestone, not a reduced objective.

## Test environment and authority

Use a new local PostgreSQL 16 database created for acceptance; never infer safety from a frontend localhost URL. Explicitly select the test/local application profile, fresh company/users, loopback database/API endpoints and disabled outbound provider dispatch. The current application.yml default is prod, so runtime profile must be explicit. Do not use old fixed administrator credentials or a production database.

Apply the checked-in V1 baseline and all new additive migrations; validate entity/schema consistency. The current H2 test profile disables Flyway and cannot prove this requirement. Historical upgrade tests referring to migrations 307/308 must be reconciled with the new schema-only baseline rather than treated as a passing migration proof.

Use separate users for warehouse actions, requester/recipient, independent approval and finance; add a user from another company and a user without access to the selected warehouse. Exercise UI operations against the actual local backend. Read-only SQL and API readback verify posting facts; they must not bypass normal approval/posting prerequisites.

## W01 — Governed master data and opening stock

Create a material in pieces, with a approved conversion of 1 box = 10 pieces, controlled tracking policy and an allowed analog. Reject a duplicate identifier and incompatible conversion. Prepare an opening document of 10 boxes at 100 currency units per piece: 100 base pieces, 10,000 book value. Submit and independently approve; post once and retry the same command.

Expected: 100 pieces and value 10,000 exactly once; original quantity/UOM/conversion/date/responsibility remain visible. A changed retry payload, stale version, wrong-company location, disallowed material or missing valuation must not post. A failed row must follow the explicitly approved atomicity model, with no unexplained partial opening balance.

Coverage: MM-01-01–05, MM-05-01, MM-09-01, common document requirements.

## W02 — Ownership is independent of location

At the same physical warehouse/location/bin, receive 100 company-owned pieces and 20 pieces owned by supplier A of the same material/batch identity where permitted. Separately record 15 pieces of company-owned material at an external holder. Create replenishment-managed and buyer-managed agreements for both owned and consigned stock.

Expected: balances preserve distinct owner/holder/status/earmark identities. Physical stock on our premises is 120 pieces; our stock across locations is 115 pieces. Supplier-owned stock must not appear as owned inventory value or satisfy unrestricted own-stock reservations. VMI responsibility can be enabled independently of ownership. An inventory count and report must show the correct scope, not collapse the rows.

Coverage: MM-06-01–03, MM-06-07, MM-11-01–03.

## W03 — Partial receipt with UOM, quality and invoice evidence

Use an approved PO for 10 boxes (100 pieces), price 10 per piece. Confirm the required supplier/ASN prerequisites. Physically receive 65 pieces: 60 accepted and 5 damaged; the remaining 35 have not arrived. Supply evidence and preserve the 5 damaged pieces under a blocked/quarantine disposition appropriate to ownership terms.

Expected: receipt, batch/unit facts, accepted availability, damaged custody, PO open quantity and financial invoiceable quantity have explicit, consistent definitions. Retry creates no extra receipt or stock. A later replacement and correction link to the initial receipt; neither silently overwrites its facts. A serialized material requires matching unique serial count.

For an accepted evidence balance of 60 pieces at 10 each, a proposed invoice for 61 pieces at 9.80 must fail quantity matching even though 597.80 is below 600. A valid 40-piece invoice consumes only its matched receipt allowance. Duplicate supplier invoice reference and concurrent claims on the remaining allowance are rejected or explicitly routed to governed review.

Coverage: MM-04-01–04, MM-09-04–06, MM-08-02.

## W04 — Reservation, employee custody, consumption and return

Continue the 100 owned pieces at 100 each posted in W01; W02/W03 use separate fixtures. Reserve 30. Issue 12 into employee custody, consume 5 with a separate report and return 3 pieces. Record an authorized quality release for those 3 returned pieces before asserting that they are available.

Expected warehouse after reserve: physical 100, reserved 30, available 70. After partial issue: warehouse 88, reserved 18, available 70; custody 12. After actual consumption and return: warehouse 91, reserved 18, available 73; employee holds 4; actual consumed 5. Accountability reconciles 12 issued = 5 consumed + 3 returned + 4 still held. Total owned remaining is 95 and book value 9,500; custody transfer itself did not expense all 12.

Reject over-issue, over-consumption, over-return, wrong employee, expired/blocked stock and unauthorized responsibility transfer. A due-back date and handover to another accountable employee remain traceable. Retry and reversal preserve the same reconciliation. Serialized items follow individual identity; fixed assets are not used as a substitute for ordinary material custody.

Coverage: MM-02-05, MM-07-01–05, MM-08-01, MM-11-08.

## W05 — Transfer, transit exceptions and exact value

Continue W04, dispatch 20 unreserved pieces from the warehouse to a second warehouse. Before receipt, source is 71, destination has no new available stock and transit is 20. Receive 18 and independently resolve the remaining 2 as confirmed loss.

Expected: transit becomes zero only when 18 received + 2 approved loss accounts for the shipment. Warehouse source 71 + destination 18 + employee custody 4 = 93 owned pieces; book value 9,300. Actual consumption expense 500 and approved loss 200 explain the reduction from the 10,000 opening value. Damage follows quarantine, not automatic loss. The recipient may refuse and return allocated quantities; original batch/serial and cost lineage survive.

Two receivers racing for the same remaining shipment allocation cannot both post. Cancellation cannot erase a posted shipment, and an adjustment cannot consume a different batch merely to make totals match.

Coverage: MM-05-02–03, MM-05-06–08, MM-09-01–03.

## W06 — Physical inventory, true zero, surplus and shortage

On the W05 destination, freeze the approved count scope and count 17 against 18. Obtain independent discrepancy/finance approval and post the 1-piece shortage. Other independently scoped stock remains operational according to the approved freeze policy.

Expected: source 71 + destination 17 + custody 4 = 92 owned pieces, book value 9,200; one additional 100 loss entry is linked to the count. Posting retry produces no second loss. Closed lines and approved count snapshots cannot be edited or deleted. Recount invalidates superseded review/approval as required.

In separate fixtures prove true zero differs from uncounted, found-stock surplus cannot post without identity and approved valuation, unknown stock is resolved through master-data governance, and expiry/damage review does not automatically create fictitious usable stock. Simultaneous count/issue obeys the freeze and version contract on PostgreSQL.

Coverage: MM-08-04–06, MM-01-05, MM-05-08, MM-11-10.

## W07 — Period and report reconciliation

Reconcile W01/W04/W05/W06 documents and cost ledger to opening 10,000 − consumed 500 − transit loss 200 − count shortage 100 = closing owned value 9,200. Show warehouse stock 88 pieces (71 + 17), custody 4 and total owned 92, separately from any consigned/external fixtures.

Expected report totals match filters, drilldowns and exports; historical as-of results use posting dates and retained evidence. Finance approves the required reconciliation before close. Backdated movements, unposted relevant cost allocations, missing costs and unresolved blockers prevent closure or posting according to the period contract. Controlled reopening retains reason and approval. Foreign stock is quantitatively visible but excluded from owned valuation.

Coverage: MM-09-01–03, MM-09-07, MM-11-01–04, MM-11-08, MM-11-10–11, MM-11-14.

## W08 — Recoverable errors and authorization

For each posting path above, exercise network uncertainty followed by same-key retry, changed-payload key reuse, stale version, duplicate serial, insufficient available quantity, database transaction failure, provider rejection, expired signing evidence where signing is required, wrong company and unauthorized warehouse/department.

Expected: no duplicate or half-posted ledger facts; a denied user sees no protected stock/cost data; the UI retains enough context to correct or retry; operator-visible integration failures can be retried/reconciled without inventing remote success. Scheduled notifications are deduplicated and respect company/recipient scope.

Coverage: all MM-10 requirements relevant to the milestone. Real remote exchange/signing remains a separate completion gate if no provider test environment is available.

## Delivery order and exit criteria

After design approval, produce small executable plans in this order: reliable local acceptance environment and baseline failures; UOM/opening/ownership contracts; receiving and posting; custody and transfer; counts/returns/quality; valuation and reporting reconciliation. Each slice must include the appropriate frontend actions and error recovery, not only service methods.

The first milestone exits only when these linked business scenarios pass through the required UI/API/PostgreSQL paths and their ledgers reconcile. The overall goal remains active until every other requirement in REQUIREMENTS.md also has direct evidence.
