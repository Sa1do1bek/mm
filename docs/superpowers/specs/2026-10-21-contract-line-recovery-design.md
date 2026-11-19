# Recover 1C contract specification lines

The user approved preserving source material-group lines and recovering exact material matches on 2026-10-21. The original migration imported one direct material line and excluded 21,429 lines from explicitly linked purchase registration documents. Of the excluded lines, 18,817 identify Catalog6295 groups (1,442 on ACTIVE contracts); 2,612 have neither a material nor group (304 on ACTIVE contracts). There is also one explicit goods-table line requiring source identity reconciliation. No material is inferred from a name, price, or a merely unique historical receipt.

## Data representation

- Preserve each source line exactly once, keyed by source table, registration document ID and line number. Keep existing line IDs and source manifests immutable.
- A MATERIAL specification can identify either an exact `materialId` or a `materialGroupId`. Preserve group identity even when it currently has one member. `unitOfMeasureId` records the source unit for group matching, with the existing `unit` as its display code.
- Preserve original raw fields, source amount/VAT, references and resolution evidence in the migration description. Do not change prices/quantities to force totals to agree.
- Lines lacking a resolvable reference or unit, with unposted/marked evidence, nonpositive quantity, or inconsistent source totals are retained as DRAFT for review. The dedicated migration operation can preserve unresolved draft lines; normal create/update does not permit an executable unresolved line.
- Valid identified lines are ACTIVE. Parent contract lifecycle and all parent business fields stay unchanged. Historical EXPIRED/CLOSED parents do not become available for receipt.

## Backend scope

Add nullable group/unit references to contract specifications, and a nullable contract-specification reference to stock movements. Use a new forward schema migration; never modify an installed migration, `.gitlab-ci.yml`, or `Dockerfile`.

Keep the original draft-only migration endpoint unchanged. Add ADMIN-only `/api/migration/contracts/lines` using the existing atomic chunk/idempotency mechanism. Advertise `contractLineVersion: 1`. Require original imported parent identity/marker via `originalPreparedManifestSha256`, exact tenant/company references, a bounded payload whitelist, source evidence and precision checks. Allow imported parent lifecycle states without changing them. Preserve row/chunk identity on retries, transactional rollback and audit.

The receipt context represents each group once, with its source quantity and price. Group lines include `materialGroupId`, `materialGroupCode`, `materialGroupName`, `unitOfMeasureId`, and `remainingReceiptQuantity`. A paginated GET at `/api/workspaces/warehouse-execution/manual/contracts/{contractId}/specifications/{specificationId}/materials?warehouseId=...` returns active warehouse-receivable member materials in the exact company, exact group and exact unit as normal context-line objects. A group with no eligible members has no executable receipt choice. No unit conversion is guessed.

Manual material receipt validates ACTIVE contract and ACTIVE group specification, actual material membership and unit. Lock the specification before checking cumulative posted receipt quantity across all group members/warehouses; set the specification ID on the posted stock movement. Exclude cancelled/reversed receipts from consumption using existing movement status/reversal semantics. Idempotent replay does not consume quantity twice. Keep concrete-material behavior compatible. Group material issue and equipment acceptance remain unavailable until their dedicated workflows support the same semantics; do not weaken their existing guards.

## Frontend scope

Show group identity in contract specification lists/details and the manual material receipt form. Selecting a group line enables a paginated material selector populated from its scoped endpoint; changing contract/specification clears dependent material, price and quantity selections. Use the selected member's context for submission and validate the remaining shared quantity. Show an explicit empty-members or review message. Preserve existing concrete material, issue, asset, stock-position, serial and file-upload behavior. Respect frontend AGENTS.md: no automatic Superpowers usage there.

## Migration and verification

Prepare a separate immutable package and journal, prioritize ACTIVE parents, resolve all identifiers using prior import journals and exact live checks. Include the original direct line in reconciliation but do not duplicate it. Do not mutate source data or replay historical stock.

Test tenant/company boundaries, invalid group/member/unit, DRAFT exclusion, shared limits, transactional failures and retry safety. Verify every imported line's source identity, references, decimals and status. Recheck parent contract fields, stock counts and receipt context; inspect an ACTIVE group contract in the browser without submitting a receipt. Merge/deploy through existing CI only after the concrete reviewed MRs and authorized production steps are ready.
