# Management opportunity analytics operations

Management opportunities are management estimates for procurement price variance,
excess carrying cost, non-moving stock recovery gaps, and supplier delay exposure.
Management opportunity analytics does not create accounting postings.
Reviewing or approving an estimate does not move stock, update valuation,
or write a financial journal.

## Running and reviewing estimates

Start a calculation with `POST /api/management-opportunities/runs`. Inspect its
status with `GET /api/management-opportunities/runs/{runId}` and the resulting
cases with `GET /api/management-opportunities/runs/{runId}/cases`. The calculation
uses the selected as-of date and target currency. Check source evidence, data
completeness, confidence, and normalized amount before submitting a case for
review. Only complete cases with a positive estimate and evidence can enter the
approval flow. A rejected case requires a new review; archive obsolete drafts.

## Operational checks

Compare each estimate with its linked source records and the policy in force for
the calculation date. If a source value or exchange rate is missing, correct the
source data and start another calculation run. Preserve earlier runs for audit
history. Treat approved cases as decision support; accounting and inventory
changes must use their own authorized workflows.
